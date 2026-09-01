package tricatch.oe.fwdproxy;

import io.github.tricatch.gotpache.cert.KeyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.cert.MultiDomainCertKeyManager;
import tricatch.oe.proxy.util.HtmlUtil;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Internal-only HTTPS server that the forward proxy redirects non-whitelisted CONNECT targets to
 * (see ForwardProxyServer#overrideFor), so a client blocked mid-TLS-handshake still gets a real
 * TLS response instead of a bare tunnel failure. Loopback-only, on a dedicated port distinct from
 * both the forward proxy (36980) and oeProxy's reverse-proxy HTTPS port (443) — routing through
 * the reverse proxy instead would run into its owner/vhost matching (no OID header, no IP->OID
 * mapping for a loopback redirect) and produce a misleading 503 "no vhosts configured" page rather
 * than a 403.
 *
 * Reuses oeHub's own root CA (the same one ReverseProxyServer/SSLPassServer signs virtual-host
 * certs with) via MultiDomainCertKeyManager to mint a certificate for whatever SNI hostname the
 * client requests — since the client already trusts that CA for oeProxy, the handshake succeeds
 * and every request, regardless of path, gets the same styled 403 whitelist page.
 */
public class BlockedPageServer {

    private static final Logger logger = LoggerFactory.getLogger(BlockedPageServer.class);

    private static final int PORT = 36981;

    private static volatile SSLServerSocket serverSocket;
    private static volatile boolean running;

    public static int getPort() {
        return PORT;
    }

    public static boolean isRunning() {
        return running;
    }

    public static synchronized void start(Path caCertPath, Path caKeyPath) {
        if (running) return;
        if (!Files.exists(caCertPath) || !Files.exists(caKeyPath)) {
            logger.info("Forward-proxy blocked-page server not started: CA not configured yet.");
            return;
        }
        try {
            var keyTool = new KeyTool();
            var rootCert = keyTool.readCertificate(caCertPath.getParent().toString(), caCertPath.getFileName().toString());
            var rootKey = keyTool.readPrivateKey(caKeyPath.getParent().toString(), caKeyPath.getFileName().toString());

            var ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            KeyManager[] kms = { new MultiDomainCertKeyManager(rootCert, rootKey) };
            var tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);

            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kms, tmf.getTrustManagers(), null);

            SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
            var socket = (SSLServerSocket) ssf.createServerSocket(PORT, 50, InetAddress.getLoopbackAddress());
            socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            serverSocket = socket;
            running = true;

            Thread.ofVirtual().name("oe-fwdproxy-blocked-page").start(() -> acceptLoop(socket));
            logger.info("Forward-proxy blocked-page server started on 127.0.0.1:{}", PORT);
        } catch (Exception e) {
            running = false;
            logger.warn("Failed to start forward-proxy blocked-page server: {}", e.getMessage(), e);
        }
    }

    public static synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
        }
    }

    private static void acceptLoop(SSLServerSocket socket) {
        while (running) {
            try {
                var client = socket.accept();
                Thread.ofVirtual().start(() -> handle(client));
            } catch (Exception e) {
                if (running) logger.debug("Blocked-page server accept error: {}", e.getMessage());
            }
        }
    }

    private static void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));

            String host = null;
            String cookie = null;
            String acceptLanguage = null;
            reader.readLine(); // request line, unused - every path gets the same response
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx < 0) continue;
                var name = line.substring(0, idx).trim();
                var value = line.substring(idx + 1).trim();
                if (name.equalsIgnoreCase("Host")) host = value;
                else if (name.equalsIgnoreCase("Cookie")) cookie = value;
                else if (name.equalsIgnoreCase("Accept-Language")) acceptLanguage = value;
            }

            var locale = HtmlUtil.resolveLocale(cookie, acceptLanguage);
            var html = HtmlUtil.renderFwdProxyForbidden(ForwardProxyServer.hostOnly(host), locale);
            var body = html.getBytes(StandardCharsets.UTF_8);

            var out = socket.getOutputStream();
            out.write(("HTTP/1.1 403 Forbidden\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: " + body.length + "\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Exception e) {
            logger.debug("Blocked-page server connection error: {}", e.getMessage());
        }
    }
}
