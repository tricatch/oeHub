package tricatch.oe.proxy.cert;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.X509ExtendedKeyManager;

import io.github.tricatch.gotpache.cert.CertificateKeyPair;
import io.github.tricatch.gotpache.cert.SSLCertificateCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MultiDomainCertKeyManager extends X509ExtendedKeyManager {

	private static final Logger logger = LoggerFactory.getLogger(MultiDomainCertKeyManager.class);

    // A client can present any SNI hostname it likes, and every distinct one seen here triggers a
    // fresh RSA keygen + CA signature (CPU-expensive) plus a permanent cache entry (memory) — so
    // an unbounded cache lets a flood of random SNIs exhaust CPU/memory. Cap it and evict the
    // oldest entries once over the cap; see MAX_CACHED_CERTIFICATES.
    private static final int MAX_CACHED_CERTIFICATES = 1000;

    // ConcurrentHashMap.computeIfAbsent() locks only the bucket for the domain being generated,
    // so a handshake for an already-cached domain never blocks behind another domain's (CPU-bound
    // RSA keygen + signing) generation — unlike a single manager-wide lock, which would serialize
    // every concurrent handshake behind whichever one happens to be generating a cert.
    // ConcurrentHashMap forbids null keys, so the no-SNI case (domain == null) is handled
    // separately via noSniCertificate below instead of as a map entry.
    private final Map<String, CertificateKeyPair> certificates = new ConcurrentHashMap<>();
    // Insertion order for the (best-effort, approximately-FIFO) eviction below. Appended to only
    // from inside the computeIfAbsent mapping function, so it grows exactly once per distinct
    // domain — repeated lookups of an already-cached domain never touch it.
    private final Queue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private volatile CertificateKeyPair noSniCertificate;
    private final Object noSniLock = new Object();

    private final X509Certificate rootCertificate;
    private final PrivateKey rootPrivateKey;

    public MultiDomainCertKeyManager(X509Certificate rootCertificate, PrivateKey rootPrivateKey){
        this.rootCertificate = rootCertificate;
        this.rootPrivateKey = rootPrivateKey;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] principals, Socket socket) {

        SSLSocket sslSocket = (SSLSocket)socket;

        //Get host name from SSL handshake
        ExtendedSSLSession session = (ExtendedSSLSession) sslSocket.getHandshakeSession();
        String domain = null;
        for (SNIServerName name : session.getRequestedServerNames()) {
            if (name.getType() == StandardConstants.SNI_HOST_NAME) {
                domain = ((SNIHostName) name).getAsciiName();
                break;
            }
        }

        if (domain == null) {
            ensureNoSniCertificate();
            return null;
        }

        try {
            certificates.computeIfAbsent(domain, d -> {
                CertificateKeyPair pair = generateCertificate(d);
                insertionOrder.add(d);
                return pair;
            });
            evictOldestIfOverCapacity();
            return domain;
        } catch (GenerationFailedException e) {
            logger.error("errorGenCert-" + e.getCause().getMessage(), e.getCause());
            return null;
        }
    }

    private void evictOldestIfOverCapacity() {
        // Best-effort trim, not an exact bound: under concurrent generation, size() and the
        // poll/remove pair below can race a little. That's fine here — the goal is just to keep
        // the cache roughly bounded, not to enforce MAX_CACHED_CERTIFICATES precisely.
        while (certificates.size() > MAX_CACHED_CERTIFICATES) {
            String oldest = insertionOrder.poll();
            if (oldest == null) break;
            certificates.remove(oldest);
        }
    }

    private void ensureNoSniCertificate() {
        if (noSniCertificate != null) return;
        synchronized (noSniLock) {
            if (noSniCertificate != null) return;
            try {
                noSniCertificate = generateCertificate(null);
            } catch (GenerationFailedException e) {
                logger.error("errorGenCert-" + e.getCause().getMessage(), e.getCause());
            }
        }
    }

    private CertificateKeyPair generateCertificate(String domain) {
        try {
            return new SSLCertificateCreator().generateSSLCertificate(domain, rootCertificate, rootPrivateKey);
        } catch (Exception e) {
            throw new GenerationFailedException(e);
        }
    }

    private static class GenerationFailedException extends RuntimeException {
        GenerationFailedException(Throwable cause) { super(cause); }
    }

    public String[] getServerAliases(String keyType, Principal[] issuers) {
        throw new UnsupportedOperationException("Method getServerAliases() not yet implemented.");
    }

    public String[] getClientAliases(String keyType, Principal[] issuers) {
        throw new UnsupportedOperationException("Method getClientAliases() not yet implemented.");
    }

    public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
        throw new UnsupportedOperationException("Method chooseClientAlias() not yet implemented.");
    }


    public X509Certificate[] getCertificateChain(String alias) {
        CertificateKeyPair pair = alias == null ? noSniCertificate : certificates.get(alias);
        if (pair == null) return null;
        return new X509Certificate[]{ pair.getCertificate() };
    }

	@Override
	public PrivateKey getPrivateKey(String alias) {
		CertificateKeyPair pair = alias == null ? noSniCertificate : certificates.get(alias);
		return pair == null ? null : pair.getPrivateKey();
	}

}
