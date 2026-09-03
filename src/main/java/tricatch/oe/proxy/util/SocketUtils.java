package tricatch.oe.proxy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.cert.TrustedUpstreamCerts;
import tricatch.oe.proxy.exception.UntrustedUpstreamCertificateException;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

public class SocketUtils {

    private static final Logger logger = LoggerFactory.getLogger(SocketUtils.class);

    public static Socket createHttp(String host, int port, int connectTimeout, int readTimeout) throws IOException {

        InetSocketAddress endpoint = new InetSocketAddress(host, port);

        Socket socket = new Socket();
        try {
            socket.setSoTimeout(readTimeout);
            socket.connect(endpoint, connectTimeout);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }

        return socket;
    }

    public static Socket createHttps(String domain, String host, int port, int connectTimeout, int readTimeout) throws IOException {

        InetSocketAddress endpoint = new InetSocketAddress(host, port);

        TrustManager[] pinningTrustManagers = new TrustManager[]{
                new X509TrustManager() {
                    private final X509TrustManager defaultTrustManager = loadDefaultTrustManager();

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        try {
                            defaultTrustManager.checkServerTrusted(chain, authType);
                            return; // chain-validated against the JVM's default trust store
                        } catch (CertificateException chainValidationFailure) {
                            String fingerprint = sha256Fingerprint(chain[0]);
                            if (TrustedUpstreamCerts.isTrusted(host, fingerprint)) {
                                return; // admin-approved pinned fingerprint for this host
                            }
                            logger.warn("Rejected untrusted upstream certificate - host={}, sha256={} " +
                                            "(not in the trusted-upstream-certs allowlist; approve it in oeHub settings if this is expected)",
                                    host, fingerprint);
                            throw new UntrustedUpstreamCertificateException(host, fingerprint);
                        }
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext sc = null;

        try {
            sc = SSLContext.getInstance("TLS");
            sc.init(null, pinningTrustManagers, new SecureRandom());
        } catch (Exception e) {
            throw new IOException(e);
        }

        SSLSocketFactory socketFactory = sc.getSocketFactory();

        Socket tcpSocket = new Socket();
        Socket socket = null;
        try {
            tcpSocket.setSoTimeout(readTimeout);
            tcpSocket.connect(endpoint, connectTimeout);

            // autoClose=false: closing the SSLSocket layer below does not close tcpSocket, so
            // both must be closed explicitly on failure.
            socket = socketFactory.createSocket(tcpSocket, domain, endpoint.getPort(), false);

            ((SSLSocket) socket).startHandshake();

            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            closeQuietly(tcpSocket);
            throw e;
        }
    }

    private static X509TrustManager loadDefaultTrustManager() {
        try {
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null); // null = JVM's default cacerts trust store
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x509) return x509;
            }
            throw new IllegalStateException("No X509TrustManager in default TrustManagerFactory");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load default trust manager", e);
        }
    }

    private static String sha256Fingerprint(X509Certificate cert) throws CertificateException {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new CertificateException("Failed to compute certificate fingerprint", e);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
