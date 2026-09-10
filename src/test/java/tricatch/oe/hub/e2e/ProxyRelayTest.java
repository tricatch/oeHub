package tricatch.oe.hub.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that oeProxy doesn't just save vhost config through the UI/API - it actually relays
 * live traffic to the configured backend, across the three body-transfer modes the proxy
 * handles differently (see {@code RelayBody}): a plain Content-Length response, a chunked
 * Server-Sent-Events stream, and a WebSocket upgrade. Drives setup and vhost config over plain
 * HTTP (java.net.http.HttpClient, mirroring the browser's cookie/CSRF handshake) rather than
 * Playwright, since this test is about the proxy's wire behavior, not the UI.
 *
 * <p>The SSL reverse proxy's HTTPS listener ({@code ReverseProxyServer}) binds a fixed,
 * non-configurable port 443 (unlike the app port, which {@link E2eServer} isolates per test via
 * {@code -Dport}) and routes by client IP (falling back from the X-OeHub-Oid header - see
 * {@code ReverseProxyServer.resolveOid()}). So this test always binds 443 on the machine it runs
 * on, and every request here - setup, vhost API calls, and the raw TLS relay checks - must go
 * through 127.0.0.1 explicitly (not "localhost", which can resolve to ::1) so they're all seen
 * as the same client IP.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyRelayTest {

    private static final int APP_PORT = 39914;
    private static final String BASE_URL = "http://127.0.0.1:" + APP_PORT;
    private static final String ADMIN_ID = "e2erelay";
    private static final String ADMIN_PW = "RelayPass123!";
    private static final String TEST_DOMAIN = "e2e-relay.oe.test";
    private static final String STUB_BODY = "hello-from-e2e-stub-backend";
    private static final String STUB_HEADER = "X-Stub-Marker";
    private static final String STUB_HEADER_VALUE = "e2e-stub-12345";
    private static final int SSE_EVENT_COUNT = 3;
    private static final String SSE_EVENT_PREFIX = "e2e-sse-event-";
    private static final String WEBSOCKET_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String HEADER_RULE_DOMAIN = "e2e-header.oe.test";
    private static final String NOT_FOUND_DOMAIN = "e2e-notfound.oe.test";
    private static final String BAD_GATEWAY_DOMAIN = "e2e-badgw.oe.test";
    private static final String MERGE_DOMAIN_A = "e2e-merge-a.oe.test";
    private static final String MERGE_DOMAIN_B = "e2e-merge-b.oe.test";
    private static final String SELF_DOMAIN = "e2e-self.oe.test";
    private static final String MONITOR_DOMAIN = "e2e-monitor.oe.test";
    private static final String GATEWAY_TIMEOUT_DOMAIN = "e2e-gwtimeout.oe.test";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private E2eServer server;
    private HttpServer httpStubBackend;
    private ServerSocket wsStubBackend;
    private HttpClient http;
    private CookieManager cookieManager;
    private String csrfToken;
    private String vhostId;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(APP_PORT);
        server.start();

        httpStubBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpStubBackend.createContext("/", exchange -> {
            byte[] body = STUB_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(STUB_HEADER, STUB_HEADER_VALUE);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpStubBackend.createContext("/echo-headers", exchange -> {
            // Echoes back whether specific request headers arrived, as its own response headers -
            // lets a test assert on the *upstream* request the proxy actually sent, not just what
            // the client itself sent, without needing to parse a full request dump.
            var reqHeaders = exchange.getRequestHeaders();
            if (reqHeaders.containsKey("X-Injected-By-Proxy")) {
                exchange.getResponseHeaders().add("X-Echo-Injected", reqHeaders.getFirst("X-Injected-By-Proxy"));
            }
            if (reqHeaders.containsKey("X-Should-Be-Removed")) {
                exchange.getResponseHeaders().add("X-Echo-Removed-Present", "true");
            }
            byte[] body = STUB_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        httpStubBackend.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // responseLength 0 => chunked transfer-encoding (no fixed length known up front),
            // the same framing a real SSE endpoint streaming a live/unbounded feed would use.
            exchange.sendResponseHeaders(200, 0);
            try (var os = exchange.getResponseBody()) {
                for (int i = 1; i <= SSE_EVENT_COUNT; i++) {
                    os.write(("data: " + SSE_EVENT_PREFIX + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        });
        httpStubBackend.start();

        // JDK's HttpServer can't hijack a connection for a raw protocol upgrade, so the WebSocket
        // backend is a minimal hand-rolled one: accept a connection, do the RFC 6455 handshake,
        // then echo back whatever frames the client sends.
        wsStubBackend = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread wsAcceptLoop = new Thread(this::runWebSocketEchoBackend, "e2e-ws-stub-backend");
        wsAcceptLoop.setDaemon(true);
        wsAcceptLoop.start();

        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        http = HttpClient.newBuilder().cookieHandler(cookieManager).build();
    }

    @AfterAll
    void stopAll() throws IOException {
        if (httpStubBackend != null) httpStubBackend.stop(0);
        if (wsStubBackend != null) wsStubBackend.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void setupAdminAccountAndCaOverHttp() throws Exception {
        // First GET sets the oe_csrf cookie (double-submit CSRF: the same value is echoed back
        // as the _csrf form field / X-CSRF-Token header on every state-changing request below).
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/setup")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        csrfToken = cookieManager.getCookieStore().getCookies().stream()
            .filter(c -> c.getName().equals("oe_csrf"))
            .map(HttpCookie::getValue)
            .findFirst().orElseThrow(() -> new IllegalStateException("oe_csrf cookie not set"));

        // publicKey/wrappedPrivateKey/wrappedPrivateKeyRecovery/recoveryVerifier/
        // founderWrappedWsKey are normally generated client-side by setup.pebble's JS
        // (e2eEncryption design doc §3/§4) - this test posts raw HTTP with no browser/WebCrypto
        // involved, and doesn't exercise encryption at all, so placeholder opaque strings are
        // enough to satisfy processSetup's presence check.
        var setupResponse = postForm("/setup", Map.of(
            "userId", ADMIN_ID, "password", ADMIN_PW, "confirm", ADMIN_PW, "_csrf", csrfToken,
            "publicKey", "test-public-key", "wrappedPrivateKey", "test-wrapped-private-key",
            "wrappedPrivateKeyRecovery", "test-wrapped-private-key-recovery",
            "recoveryVerifier", "test-recovery-verifier",
            "founderWrappedWsKey", "test-founder-wrapped-ws-key"));
        assertThat(setupResponse.statusCode()).isEqualTo(302);

        var caResponse = postForm("/setup/ca/generate", Map.of(
            "caName", "oeHub Relay Test CA", "_csrf", csrfToken));
        assertThat(caResponse.statusCode()).isEqualTo(302);

        assertThat(Files.exists(server.homeDir().resolve("root-ca").resolve("ca.cer"))).isTrue();
    }

    @Test
    @Order(2)
    void createsAndSelectsVhostRoutingToStubBackends() throws Exception {
        var createResponse = postJson("/api/proxy/vhosts", "");
        assertThat(createResponse.statusCode()).isEqualTo(201);
        vhostId = (String) objectMapper.readValue(createResponse.body(), Map.class).get("vhostId");
        assertThat(vhostId).isNotBlank();

        // Two locations under one domain, most-specific first (same convention as
        // example/vhost_example_en.yaml): "/ws" goes to the raw WebSocket echo backend,
        // everything else (including "/sse") falls through to the plain HTTP stub.
        var yaml = "virtual:\n"
            + "  - domain: " + TEST_DOMAIN + "\n"
            + "    location:\n"
            + "      - host: http://127.0.0.1:" + wsStubBackend.getLocalPort() + "\n"
            + "        path:\n"
            + "          - /ws\n"
            + "      - host: http://127.0.0.1:" + httpStubBackend.getAddress().getPort() + "\n"
            + "        path:\n"
            + "          - /**\n";
        var contentResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/content",
            objectMapper.writeValueAsString(Map.of("content", yaml)));
        assertThat(contentResponse.statusCode()).isEqualTo(200);

        // Toggling "selected" is what actually pushes the merged config live into
        // ReverseProxyServer (see ProxyController.apiToggleSelected -> applyMergedConfig()).
        var selectResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/selected", null);
        assertThat(selectResponse.statusCode()).isEqualTo(200);

        // ReverseProxyServer.resolveOid() has no loopback/sole-account shortcut - a raw TLS
        // client (no X-OeHub-Oid header, see openRelaySocket()) is only ever attributed to this
        // account because this test explicitly claims 127.0.0.1 via ClaimedIpRegistry first,
        // exactly like clicking "Use This IP" on the oeProxy page would.
        var takeIpResponse = postJson("/api/proxy/take-ip", null);
        assertThat(takeIpResponse.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void reverseProxyRelaysPlainHttpTraffic() throws Exception {
        try (var socket = openRelaySocket(TEST_DOMAIN)) {
            var response = sendGet(socket, TEST_DOMAIN, "/");
            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(STUB_BODY);
            assertThat(response.headers().get(STUB_HEADER.toLowerCase())).isEqualTo(STUB_HEADER_VALUE);
        }
    }

    @Test
    @Order(4)
    void reverseProxyRelaysChunkedServerSentEvents() throws Exception {
        try (var socket = openRelaySocket(TEST_DOMAIN)) {
            var response = sendGet(socket, TEST_DOMAIN, "/sse");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers().get("transfer-encoding")).isEqualToIgnoringCase("chunked");
            var body = new String(response.body(), StandardCharsets.UTF_8);
            for (int i = 1; i <= SSE_EVENT_COUNT; i++) {
                assertThat(body).contains("data: " + SSE_EVENT_PREFIX + i);
            }
        }
    }

    @Test
    @Order(5)
    void reverseProxyRelaysWebSocketFrames() throws Exception {
        try (var socket = openRelaySocket(TEST_DOMAIN)) {
            var wsKeyBytes = new byte[16];
            new SecureRandom().nextBytes(wsKeyBytes);
            var wsKey = Base64.getEncoder().encodeToString(wsKeyBytes);

            var request = "GET /ws HTTP/1.1\r\n"
                + "Host: " + TEST_DOMAIN + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var lineReader = new LineReader(socket.getInputStream());
            var statusLine = lineReader.readLine();
            assertThat(statusLine).contains(" 101 ");
            String acceptHeader = null;
            String line;
            while (!(line = lineReader.readLine()).isEmpty()) {
                if (line.regionMatches(true, 0, "Sec-WebSocket-Accept:", 0, 21)) {
                    acceptHeader = line.substring(21).trim();
                }
            }
            assertThat(acceptHeader).isEqualTo(computeWebSocketAccept(wsKey));

            // Client-to-server frames must be masked per RFC 6455; the proxy just relays raw
            // frames byte-for-byte (see RelayWebSocket), so this and the echo below prove both
            // directions of the upgraded connection actually carry data through it.
            var payload = "ping-e2e-relay".getBytes(StandardCharsets.UTF_8);
            writeWebSocketFrame(socket.getOutputStream(), 0x1, payload, true);

            var echoed = readWebSocketFrame(lineReader);
            assertThat(echoed).isNotNull();
            assertThat(echoed.opcode()).isEqualTo(0x1);
            assertThat(new String(echoed.payload(), StandardCharsets.UTF_8)).isEqualTo("ping-e2e-relay");

            writeWebSocketFrame(socket.getOutputStream(), 0x8, new byte[0], true);
        }
    }

    @Test
    @Order(6)
    void reverseProxyKeepsConnectionAliveAcrossRequests() throws Exception {
        // See this class's earlier debugging note: the proxy decides whether to keep a
        // connection open purely from the *backend's* response Connection header
        // (HttpResponse.shouldCloseConnection()), not the client's request - our stub backend
        // never sends "Connection: close", so one socket should carry both requests below
        // without either side reconnecting.
        try (var socket = openRelaySocket(TEST_DOMAIN)) {
            var first = sendKeepAliveGet(socket, TEST_DOMAIN, "/");
            assertThat(first.status()).isEqualTo(200);
            assertThat(new String(first.body(), StandardCharsets.UTF_8)).isEqualTo(STUB_BODY);
            assertThat(socket.isClosed()).isFalse();

            // Same socket, second (and differently-shaped: chunked) request - proves the proxy
            // actually kept the connection open for reuse rather than us silently reconnecting.
            var second = sendKeepAliveGet(socket, TEST_DOMAIN, "/sse");
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.headers().get("transfer-encoding")).isEqualToIgnoringCase("chunked");
            var body = new String(second.body(), StandardCharsets.UTF_8);
            for (int i = 1; i <= SSE_EVENT_COUNT; i++) {
                assertThat(body).contains("data: " + SSE_EVENT_PREFIX + i);
            }

            // A third request confirms the connection survives more than just one reuse.
            var third = sendKeepAliveGet(socket, TEST_DOMAIN, "/");
            assertThat(third.status()).isEqualTo(200);
            assertThat(new String(third.body(), StandardCharsets.UTF_8)).isEqualTo(STUB_BODY);
        }
    }

    @Test
    @Order(7)
    void reverseProxyAppliesPerLocationHeaderRules() throws Exception {
        // A location's "header" list adds a plain "Name: value" entry to the request the proxy
        // forwards upstream, and removes one named by a "--Name" entry (see
        // PassRequestExecutor.applyHeaderRules() / VirtualHostUtil's "--" prefix convention).
        // Reuses vhostId from @Order(2) - nothing later in this class depends on that vhost's
        // previous content, so each of these later tests just repoints it to whatever it needs.
        var yaml = "virtual:\n"
            + "  - domain: " + HEADER_RULE_DOMAIN + "\n"
            + "    location:\n"
            + "      - host: http://127.0.0.1:" + httpStubBackend.getAddress().getPort() + "\n"
            + "        path:\n"
            + "          - /**\n"
            + "        header:\n"
            + "          - \"X-Injected-By-Proxy: e2e-added-value\"\n"
            + "          - \"--X-Should-Be-Removed\"\n";
        var contentResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/content",
            objectMapper.writeValueAsString(Map.of("content", yaml)));
        assertThat(contentResponse.statusCode()).isEqualTo(200);

        try (var socket = openRelaySocket(HEADER_RULE_DOMAIN)) {
            var response = HttpRelay.send(socket, "GET /echo-headers HTTP/1.1", Map.of(
                "Host", HEADER_RULE_DOMAIN,
                "Connection", "close",
                "X-Should-Be-Removed", "client-value"));
            assertThat(response.status()).isEqualTo(200);
            // The backend only sees X-Injected-By-Proxy because the proxy added it - the client
            // (above) never sent it.
            assertThat(response.headers().get("x-echo-injected")).isEqualTo("e2e-added-value");
            // The client did send X-Should-Be-Removed, but the location's "--" rule strips it
            // before the request reaches the backend.
            assertThat(response.headers()).doesNotContainKey("x-echo-removed-present");
        }
    }

    @Test
    @Order(8)
    void reverseProxyRendersNotFoundVhostPageForUnregisteredDomain() throws Exception {
        // A domain that was never listed in any of this account's vhost content still passes TLS
        // (see MultiDomainCertKeyManager - certs are generated per-SNI unconditionally); the
        // "not found" behavior is purely an HTTP-layer routing miss (getVirtualPath() throwing
        // NotFoundVhostException), rendered as proxy-not-found-vhost.pebble.
        try (var socket = openRelaySocket(NOT_FOUND_DOMAIN)) {
            var response = sendGet(socket, NOT_FOUND_DOMAIN, "/");
            assertThat(response.status()).isEqualTo(404);
        }
    }

    @Test
    @Order(9)
    void reverseProxyRendersBadGatewayPageWhenBackendUnreachable() throws Exception {
        int deadPort;
        try (var probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        // probe is closed again immediately - deadPort is (barring a startling coincidence)
        // nothing listening, so the proxy's own connect attempt fails with connection-refused.

        var yaml = "virtual:\n"
            + "  - domain: " + BAD_GATEWAY_DOMAIN + "\n"
            + "    location:\n"
            + "      - host: http://127.0.0.1:" + deadPort + "\n"
            + "        path:\n"
            + "          - /**\n";
        var contentResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/content",
            objectMapper.writeValueAsString(Map.of("content", yaml)));
        assertThat(contentResponse.statusCode()).isEqualTo(200);

        try (var socket = openRelaySocket(BAD_GATEWAY_DOMAIN)) {
            var response = sendGet(socket, BAD_GATEWAY_DOMAIN, "/");
            assertThat(response.status()).isEqualTo(502);
        }
    }

    @Test
    @Order(10)
    void reverseProxyMergesMultipleSelectedVhosts() throws Exception {
        // Vhost 1 (from @Order(2), already selected) - repoint it to a fresh domain.
        var yamlA = "virtual:\n"
            + "  - domain: " + MERGE_DOMAIN_A + "\n"
            + "    location:\n"
            + "      - host: http://127.0.0.1:" + httpStubBackend.getAddress().getPort() + "\n"
            + "        path:\n"
            + "          - /**\n";
        var contentAResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/content",
            objectMapper.writeValueAsString(Map.of("content", yamlA)));
        assertThat(contentAResponse.statusCode()).isEqualTo(200);

        // Vhost 2 - a separate vhost profile (its own DB row), also selected.
        var createResponse = postJson("/api/proxy/vhosts", "");
        assertThat(createResponse.statusCode()).isEqualTo(201);
        var vhost2Id = (String) objectMapper.readValue(createResponse.body(), Map.class).get("vhostId");
        var yamlB = "virtual:\n"
            + "  - domain: " + MERGE_DOMAIN_B + "\n"
            + "    location:\n"
            + "      - host: http://127.0.0.1:" + httpStubBackend.getAddress().getPort() + "\n"
            + "        path:\n"
            + "          - /**\n";
        var contentBResponse = patchJson("/api/proxy/vhosts/" + vhost2Id + "/content",
            objectMapper.writeValueAsString(Map.of("content", yamlB)));
        assertThat(contentBResponse.statusCode()).isEqualTo(200);
        // Toggling this second vhost's selection is what forces ProxyController.applyMergedConfig()
        // to recompute from *all* currently-selected vhosts (vhostService.listSelected()) - both
        // this one and vhost 1 above - rather than just this one replacing the other.
        var selectBResponse = patchJson("/api/proxy/vhosts/" + vhost2Id + "/selected", null);
        assertThat(selectBResponse.statusCode()).isEqualTo(200);

        try (var socketA = openRelaySocket(MERGE_DOMAIN_A)) {
            var responseA = sendGet(socketA, MERGE_DOMAIN_A, "/");
            assertThat(responseA.status()).isEqualTo(200);
            assertThat(new String(responseA.body(), StandardCharsets.UTF_8)).isEqualTo(STUB_BODY);
        }
        try (var socketB = openRelaySocket(MERGE_DOMAIN_B)) {
            var responseB = sendGet(socketB, MERGE_DOMAIN_B, "/");
            assertThat(responseB.status()).isEqualTo(200);
            assertThat(new String(responseB.body(), StandardCharsets.UTF_8)).isEqualTo(STUB_BODY);
        }
    }

    @Test
    @Order(11)
    void reverseProxyRoutesToOeHubsOwnAppPort() throws Exception {
        // Mirrors the primary real-world use case in example/vhost_example_en.yaml: pointing a
        // friendly HTTPS domain straight at oeHub's own app port, so the reverse proxy's
        // auto-generated SSL cert fronts the web UI itself instead of a raw http://ip:port URL.
        // Uses this test's own isolated app port (APP_PORT, via E2eServer's -Dport) rather than
        // the literal default 36912 - that number is just APP_PORT's default value, nothing the
        // proxy treats specially.
        var yaml = "virtual:\n"
            + "  - domain: " + SELF_DOMAIN + "\n"
            + "    location:\n"
            + "      - host: http://127.0.0.1:" + APP_PORT + "\n"
            + "        path:\n"
            + "          - /**\n";
        var contentResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/content",
            objectMapper.writeValueAsString(Map.of("content", yaml)));
        assertThat(contentResponse.statusCode()).isEqualTo(200);

        try (var socket = openRelaySocket(SELF_DOMAIN)) {
            // A static asset (unauthenticated, exempt from the setup-redirect filter - see
            // OeHubApplication's before-filters) so this only proves the relay path itself works,
            // independent of session cookies or setup state.
            var response = sendGet(socket, SELF_DOMAIN, "/css/brand.css");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers().get("content-type")).contains("css");
            assertThat(response.body().length).isGreaterThan(0);
        }
    }

    @Test
    @Order(12)
    void reverseProxyLiveMonitorStreamsRequestEvents() throws Exception {
        // Fresh domain, independent of whatever an earlier test left vhostId pointed at.
        var yaml = "virtual:\n"
            + "  - domain: " + MONITOR_DOMAIN + "\n"
            + "    location:\n"
            + "      - host: http://127.0.0.1:" + httpStubBackend.getAddress().getPort() + "\n"
            + "        path:\n"
            + "          - /**\n";
        var contentResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/content",
            objectMapper.writeValueAsString(Map.of("content", yaml)));
        assertThat(contentResponse.statusCode()).isEqualTo(200);

        var authCookie = cookieValue("oe_auth");
        try (var monitorSocket = new Socket("127.0.0.1", APP_PORT)) {
            var request = "GET /api/proxy/monitor/event HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + APP_PORT + "\r\n"
                + "Cookie: oe_auth=" + authCookie + "\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
            monitorSocket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            monitorSocket.getOutputStream().flush();
            monitorSocket.setSoTimeout(500);

            var monitorReader = new LineReader(monitorSocket.getInputStream());
            var statusLine = monitorReader.readLine();
            assertThat(statusLine).contains(" 200 ");
            String line;
            while (!(line = monitorReader.readLine()).isEmpty()) {
                // drain response headers (Content-Type: text/event-stream, Transfer-Encoding, ...)
            }

            // Trigger a real request through the reverse proxy while the monitor is listening -
            // ProxyController.monitorEvent()'s SSE stream is a live feed of HttpEvents for this
            // account's own traffic (see PassRequestExecutor/PassResponseExecutor enqueueing
            // REQ_HEADER/RES_HEADER events tagged by ownerOid), not a snapshot.
            try (var relaySocket = openRelaySocket(MONITOR_DOMAIN)) {
                var relayResponse = sendGet(relaySocket, MONITOR_DOMAIN, "/");
                assertThat(relayResponse.status()).isEqualTo(200);
            }

            // The monitor response is chunked (no declared Content-Length for a stream that never
            // ends), and unlike HttpRelay.readResponse() this must NOT wait for a terminating
            // 0-length chunk - the stream stays open indefinitely, so read chunk-by-chunk until
            // the triggered event's own domain shows up, or give up after a few seconds.
            var collected = new StringBuilder();
            var deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !collected.toString().contains(MONITOR_DOMAIN)) {
                try {
                    var sizeLine = monitorReader.readLine();
                    if (sizeLine.isBlank()) continue;
                    int size = Integer.parseInt(sizeLine.trim(), 16);
                    collected.append(new String(monitorReader.readExact(size), StandardCharsets.UTF_8));
                    monitorReader.readLine(); // CRLF after each chunk's data
                } catch (java.net.SocketTimeoutException timeout) {
                    // no chunk arrived within this read's window - keep polling until the deadline
                }
            }
            assertThat(collected.toString()).contains(MONITOR_DOMAIN);
            assertThat(collected.toString()).contains("REQ_HEADER");
        }
    }

    @Test
    @Order(13)
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void reverseProxyRendersGatewayTimeoutPageWhenBackendNeverResponds() throws Exception {
        // A backend that accepts the TCP connection but never writes anything back forces the
        // proxy's response-side read to block until its own hardcoded 30s read-timeout
        // (ReverseProxyServer's config.getHttps().setReadTimeout()) fires - that's specifically
        // what produces a 504 rather than some other error, and there's no faster way to trigger
        // it. The @Timeout above is just a safety net in case that assumption is ever wrong.
        try (var hangingBackend = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> {
                try (var ignored = hangingBackend.accept()) {
                    Thread.sleep(35_000);
                } catch (Exception ignoredEx) {
                    // hangingBackend closing at the end of this test interrupts accept()/sleep()
                }
            });

            var yaml = "virtual:\n"
                + "  - domain: " + GATEWAY_TIMEOUT_DOMAIN + "\n"
                + "    location:\n"
                + "      - host: http://127.0.0.1:" + hangingBackend.getLocalPort() + "\n"
                + "        path:\n"
                + "          - /**\n";
            var contentResponse = patchJson("/api/proxy/vhosts/" + vhostId + "/content",
                objectMapper.writeValueAsString(Map.of("content", yaml)));
            assertThat(contentResponse.statusCode()).isEqualTo(200);

            try (var socket = openRelaySocket(GATEWAY_TIMEOUT_DOMAIN)) {
                var response = sendGet(socket, GATEWAY_TIMEOUT_DOMAIN, "/");
                assertThat(response.status()).isEqualTo(504);
            }
        }
    }

    // ── HTTP helpers (setup + vhost API, over plain HTTP to the app port) ──────────────────

    private String cookieValue(String name) {
        return cookieManager.getCookieStore().getCookies().stream()
            .filter(c -> c.getName().equals(name))
            .map(HttpCookie::getValue)
            .findFirst().orElseThrow(() -> new IllegalStateException(name + " cookie not set"));
    }

    private HttpResponse<String> postForm(String path, Map<String, String> params) throws Exception {
        var body = params.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String jsonBody) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
            .header("Content-Type", "application/json")
            .header("X-CSRF-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(String path, String jsonBody) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
            .header("Content-Type", "application/json")
            .header("X-CSRF-Token", csrfToken)
            .method("PATCH", jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ── Raw TLS relay client (bypasses the app entirely - talks straight to :443) ──────────

    /**
     * Connects a raw TCP socket to a literal IP, then layers TLS over it naming {@code domain}
     * as the SNI server name - decouples the SNI/Host value the proxy routes on from DNS, which
     * would otherwise need an actual (or hosts-file) entry for a throwaway test domain.
     */
    private SSLSocket openRelaySocket(String domain) throws Exception {
        var caCertPath = server.homeDir().resolve("root-ca").resolve("ca.cer");
        Certificate caCert;
        try (var in = Files.newInputStream(caCertPath)) {
            caCert = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("oehub-e2e-test-ca", caCert);
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        var rawSocket = new Socket("127.0.0.1", 443);
        var sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(rawSocket, domain, 443, true);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private HttpRelay.Response sendGet(Socket socket, String host, String path) throws IOException {
        return HttpRelay.send(socket, "GET " + path + " HTTP/1.1",
            Map.of("Host", host, "Connection", "close"));
    }

    private HttpRelay.Response sendKeepAliveGet(Socket socket, String host, String path) throws IOException {
        return HttpRelay.send(socket, "GET " + path + " HTTP/1.1",
            Map.of("Host", host, "Connection", "keep-alive"));
    }

    // ── WebSocket: minimal RFC 6455 codec + a raw-socket echo backend ──────────────────────

    private record WebSocketFrame(int opcode, byte[] payload) {}

    private static String computeWebSocketAccept(String key) throws Exception {
        var sha1 = MessageDigest.getInstance("SHA-1");
        var digest = sha1.digest((key + WEBSOCKET_ACCEPT_GUID).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static void writeWebSocketFrame(OutputStream out, int opcode, byte[] payload, boolean mask) throws IOException {
        out.write(0x80 | (opcode & 0x0F)); // FIN=1, no extensions
        var len = payload.length;
        var maskBit = mask ? 0x80 : 0x00;
        if (len <= 125) {
            out.write(maskBit | len);
        } else if (len <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(maskBit | 127);
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) ((long) len >> shift) & 0xFF);
        }
        if (mask) {
            var maskKey = new byte[4];
            new SecureRandom().nextBytes(maskKey);
            out.write(maskKey);
            var masked = new byte[len];
            for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            out.write(masked);
        } else {
            out.write(payload);
        }
        out.flush();
    }

    private static WebSocketFrame readWebSocketFrame(LineReader in) throws IOException {
        int b1 = in.readByte();
        if (b1 == -1) return null;
        var opcode = b1 & 0x0F;

        int b2 = in.readByte();
        if (b2 == -1) return null;
        var masked = (b2 & 0x80) != 0;
        var len = b2 & 0x7F;
        if (len == 126) {
            var ext = in.readExact(2);
            len = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
        } else if (len == 127) {
            var ext = in.readExact(8);
            long l = 0;
            for (byte value : ext) l = (l << 8) | (value & 0xFF);
            len = (int) l;
        }
        byte[] maskKey = masked ? in.readExact(4) : null;
        var payload = in.readExact(len);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];
        }
        return new WebSocketFrame(opcode, payload);
    }

    private void runWebSocketEchoBackend() {
        while (!wsStubBackend.isClosed()) {
            Socket socket;
            try {
                socket = wsStubBackend.accept();
            } catch (IOException e) {
                return; // wsStubBackend.close() during @AfterAll
            }
            Thread.ofVirtual().start(() -> handleWebSocketConnection(socket));
        }
    }

    private void handleWebSocketConnection(Socket socket) {
        try (socket) {
            var in = new LineReader(socket.getInputStream());
            in.readLine(); // request line, e.g. "GET /ws HTTP/1.1" - path routing already happened in the proxy
            String wsKey = null;
            String line;
            while (!(line = in.readLine()).isEmpty()) {
                if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) {
                    wsKey = line.substring(18).trim();
                }
            }
            var response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + computeWebSocketAccept(wsKey) + "\r\n"
                + "\r\n";
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            while (true) {
                var frame = readWebSocketFrame(in);
                if (frame == null || frame.opcode() == 0x8) {
                    if (frame != null) writeWebSocketFrame(socket.getOutputStream(), 0x8, new byte[0], false);
                    return;
                }
                // Echo back unmasked (server-to-client frames must not be masked).
                writeWebSocketFrame(socket.getOutputStream(), frame.opcode(), frame.payload(), false);
            }
        } catch (Exception e) {
            // Best-effort test double - a broken connection here just fails the test's own assertions.
        }
    }
}
