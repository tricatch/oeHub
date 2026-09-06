package tricatch.oe.proxy.cert;

import io.github.tricatch.gotpache.cert.CertificateKeyPair;
import io.github.tricatch.gotpache.cert.RootCertificateCreator;
import org.junit.jupiter.api.Test;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MultiDomainCertKeyManagerTest {

    /** Only getRequestedServerNames() is ever touched by the code under test. */
    private static class FakeSniSession extends ExtendedSSLSession {
        private final String hostname;
        FakeSniSession(String hostname) { this.hostname = hostname; }

        @Override
        public List<SNIServerName> getRequestedServerNames() {
            return Collections.singletonList(new SNIHostName(hostname));
        }

        @Override public byte[] getId() { return new byte[0]; }
        @Override public SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return 0; }
        @Override public long getLastAccessedTime() { return 0; }
        @Override public void invalidate() { }
        @Override public boolean isValid() { return true; }
        @Override public void putValue(String name, Object value) { }
        @Override public Object getValue(String name) { return null; }
        @Override public void removeValue(String name) { }
        @Override public String[] getValueNames() { return new String[0]; }
        @Override public Certificate[] getPeerCertificates() { return new Certificate[0]; }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override @SuppressWarnings("removal") public javax.security.cert.X509Certificate[] getPeerCertificateChain() { return new javax.security.cert.X509Certificate[0]; }
        @Override public Principal getPeerPrincipal() { return null; }
        @Override public Principal getLocalPrincipal() { return null; }
        @Override public String getCipherSuite() { return "TLS_NONE"; }
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getPeerHost() { return hostname; }
        @Override public int getPeerPort() { return 443; }
        @Override public int getPacketBufferSize() { return 16384; }
        @Override public int getApplicationBufferSize() { return 16384; }
        @Override public String[] getPeerSupportedSignatureAlgorithms() { return new String[0]; }
        @Override public String[] getLocalSupportedSignatureAlgorithms() { return new String[0]; }
    }

    /** Simulates a handshake with no SNI extension at all (an old client, or a scanner). */
    private static class FakeNoSniSession extends FakeSniSession {
        FakeNoSniSession() { super("unused"); }
        @Override public List<SNIServerName> getRequestedServerNames() { return Collections.emptyList(); }
    }

    /** SSLSocket's own methods have default (UnsupportedOperationException) bodies since JDK 9; only the handshake session is needed here. */
    private static class FakeSslSocket extends SSLSocket {
        private final SSLSession handshakeSession;
        FakeSslSocket(SSLSession handshakeSession) { this.handshakeSession = handshakeSession; }
        @Override public SSLSession getHandshakeSession() { return handshakeSession; }
        @Override public boolean getEnableSessionCreation() { return true; }
        @Override public void setEnableSessionCreation(boolean flag) { }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) { }
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] protocols) { }
        @Override public SSLSession getSession() { return handshakeSession; }
        @Override public void addHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) { }
        @Override public void removeHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) { }
        @Override public void startHandshake() { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return false; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getWantClientAuth() { return false; }
    }

    @Test
    void concurrentHandshakesForTheSameNewDomain_neverExposeAMissingCertificate() throws Exception {
        CertificateKeyPair root = new RootCertificateCreator().generateRootCertificate("test-root-ca");
        MultiDomainCertKeyManager manager = new MultiDomainCertKeyManager(root.getCertificate(), root.getPrivateKey());

        String domain = "race.example.com";
        int threadCount = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        Set<String> aliasesReturned = ConcurrentHashMap.newKeySet();
        AtomicInteger missingChainAfterAlias = new AtomicInteger(0);
        AtomicInteger missingKeyAfterAlias = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        SSLSocket socket = new FakeSslSocket(new FakeSniSession(domain));
                        String alias = manager.chooseServerAlias("RSA", null, socket);
                        if (alias != null) {
                            aliasesReturned.add(alias);
                            // This is exactly the failure mode the fix closes: a reader seeing the
                            // domain "chosen" but the cert/key not yet visible for it.
                            if (manager.getCertificateChain(alias) == null) missingChainAfterAlias.incrementAndGet();
                            if (manager.getPrivateKey(alias) == null) missingKeyAfterAlias.incrementAndGet();
                        }
                    } catch (Exception e) {
                        unexpectedErrors.incrementAndGet();
                    }
                }));
            }

            ready.await();
            go.countDown();
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpectedErrors.get()).isEqualTo(0);
        assertThat(aliasesReturned).containsExactly(domain);
        assertThat(missingChainAfterAlias.get()).isEqualTo(0);
        assertThat(missingKeyAfterAlias.get()).isEqualTo(0);
    }

    @Test
    void noSniHandshake_failsGracefully_withoutThrowingOutOfChooseServerAlias() throws Exception {
        // SSLCertificateCreator can't build a certificate for a null domain (BouncyCastle rejects
        // a null SAN) — that's pre-existing, not something this change alters. What this change
        // must preserve is that the failure is caught and logged inside chooseServerAlias rather
        // than propagating out and crashing the handshake thread. getCertificateChain(null) and
        // getPrivateKey(null) both stay null, exactly as before this class switched to
        // ConcurrentHashMap (which forbids null keys) for the real per-domain cache.
        CertificateKeyPair root = new RootCertificateCreator().generateRootCertificate("test-root-ca");
        MultiDomainCertKeyManager manager = new MultiDomainCertKeyManager(root.getCertificate(), root.getPrivateKey());

        SSLSocket socket = new FakeSslSocket(new FakeNoSniSession());
        String alias = manager.chooseServerAlias("RSA", null, socket);

        assertThat(alias).isNull();
        assertThat(manager.getCertificateChain(null)).isNull();
        assertThat(manager.getPrivateKey(null)).isNull();
    }
}
