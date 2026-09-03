package tricatch.oe.proxy.cert;

import io.github.tricatch.gotpache.cert.RootCertificateCreator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.exception.UntrustedUpstreamCertificateException;
import tricatch.oe.proxy.util.SocketUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocketUtilsUpstreamTrustTest {

    @BeforeAll
    static void initProvider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void createHttps_rejectsSelfSignedCert_unlessFingerprintIsAllowlisted() throws Exception {
        var rootCert = new RootCertificateCreator().generateRootCertificate("self-signed-upstream-test");
        X509Certificate cert = rootCert.getCertificate();

        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", rootCert.getPrivateKey(), "changeit".toCharArray(), new Certificate[]{cert});

        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());

        var serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = serverSocket.getLocalPort();

            try {
                // Not yet allowlisted -> rejected, with the specific reason preserved in the cause chain.
                acceptOnceAsync(serverSocket);
                assertThatThrownBy(() -> SocketUtils.createHttps("test", "localhost", port, 3000, 3000))
                        .isInstanceOf(IOException.class)
                        .matches(SocketUtilsUpstreamTrustTest::hasUntrustedCertificateCause);

                // Admin approves this exact fingerprint for "localhost" -> the same cert is now accepted.
                String fingerprint = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
                TrustedUpstreamCerts.apply("localhost " + fingerprint + " test-note");

                acceptOnceAsync(serverSocket);
                try (Socket socket = SocketUtils.createHttps("test", "localhost", port, 3000, 3000)) {
                    assertThat(socket.isConnected()).isTrue();
                }
            } finally {
                TrustedUpstreamCerts.apply(""); // don't leak allowlist state into other tests
            }
        }
    }

    private static void acceptOnceAsync(SSLServerSocket serverSocket) {
        CompletableFuture.runAsync(() -> {
            try (Socket accepted = serverSocket.accept()) {
                ((SSLSocket) accepted).startHandshake();
            } catch (IOException ignored) {
            }
        });
    }

    private static boolean hasUntrustedCertificateCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UntrustedUpstreamCertificateException) return true;
        }
        return false;
    }
}
