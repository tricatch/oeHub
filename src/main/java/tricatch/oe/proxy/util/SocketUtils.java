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
                            // Chain-validated against the JVM's default trust store, but that alone
                            // doesn't confirm the certificate is actually for `domain` - a CA will
                            // happily issue a valid chain for any domain its owner controls. Without
                            // this check, an attacker who can redirect the TCP connection (DNS/ARP
                            // spoofing, a compromised router) could present any CA-trusted cert for a
                            // domain *they* own and MITM the upstream connection. The fingerprint-pinned
                            // path below is intentionally exempt: an admin who pins an exact SHA-256
                            // fingerprint has already vouched for that specific certificate, and
                            // self-signed/internal certs commonly lack a SAN matching the vhost's domain.
                            verifyHostname(chain[0], domain);
                            return;
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

    /** Confirms cert's Subject Alternative Names include a dNSName entry matching hostname
     *  (case-insensitive, single-leftmost-label wildcards only - RFC 6125 style). CN is
     *  deliberately not consulted as a fallback, matching current browser/CA practice where a
     *  SAN is required; a cert without any matching dNSName SAN is rejected outright. */
    private static void verifyHostname(X509Certificate cert, String hostname) throws CertificateException {
        java.util.Collection<java.util.List<?>> sans;
        try {
            sans = cert.getSubjectAlternativeNames();
        } catch (java.security.cert.CertificateParsingException e) {
            throw new CertificateException("Failed to parse certificate SAN entries", e);
        }
        if (sans != null) {
            for (var san : sans) {
                if (san.size() < 2 || !(san.get(0) instanceof Integer type) || type != 2) continue; // 2 = dNSName
                if (san.get(1) instanceof String dnsName && matchesHostname(hostname, dnsName)) return;
            }
        }
        throw new CertificateException("Certificate for " + subjectOf(cert) + " does not match hostname " + hostname);
    }

    private static boolean matchesHostname(String hostname, String pattern) {
        var h = hostname.toLowerCase();
        var p = pattern.toLowerCase();
        if (p.startsWith("*.")) {
            var suffix = p.substring(1); // ".example.com"
            return h.length() > suffix.length() && h.endsWith(suffix) && h.substring(0, h.length() - suffix.length()).indexOf('.') < 0;
        }
        return h.equals(p);
    }

    private static String subjectOf(X509Certificate cert) {
        try {
            return cert.getSubjectX500Principal().getName();
        } catch (Exception e) {
            return "<unknown>";
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
