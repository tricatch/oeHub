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

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies oeHub's forward (upstream) proxy - {@code ForwardProxyServer}, a fixed-port (36980)
 * plain HTTP proxy authenticated against oeHub accounts - actually relays traffic and applies
 * its most distinctive feature: overriding a hostname's resolved IP with whatever the
 * authenticated user's currently-selected oeHosts profile maps it to (see
 * ForwardProxyServer.overrideFor()). Drives setup and the oeHosts profile over plain HTTP
 * (java.net.http.HttpClient) rather than Playwright, since this test is about the proxy's wire
 * behavior, not the UI.
 *
 * <p>Unlike {@link ProxyRelayTest} this never touches TLS/port 443 - the forward proxy itself is
 * plain HTTP on port 36980 (also fixed, not isolated per test the way the app port is). Every
 * request here goes through 127.0.0.1 explicitly (not "localhost") for the same reason as
 * ProxyRelayTest: the app and the forward proxy must see the same client IP / auth identity.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ForwardProxyRelayTest {

    private static final int APP_PORT = 39915;
    private static final int FWDPROXY_PORT = 36980;
    private static final String BASE_URL = "http://127.0.0.1:" + APP_PORT;
    private static final String ADMIN_ID = "e2efwdproxy";
    private static final String ADMIN_PW = "FwdProxyPass123!";
    private static final String OVERRIDE_DOMAIN = "e2e-fwdproxy.oe.test";
    private static final String STUB_BODY = "hello-from-fwdproxy-stub-backend";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private E2eServer server;
    private HttpServer stubBackend;
    private HttpClient http;
    private CookieManager cookieManager;
    private String csrfToken;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(APP_PORT);
        server.start();

        stubBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubBackend.createContext("/", exchange -> {
            var body = STUB_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stubBackend.start();

        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        http = HttpClient.newBuilder().cookieHandler(cookieManager).build();
    }

    @AfterAll
    void stopAll() {
        if (stubBackend != null) stubBackend.stop(0);
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void setupAdminAccountAndCaOverHttp() throws Exception {
        // The forward proxy itself never terminates TLS (a CONNECT tunnel is opaque bytes end to
        // end), so it has no direct use for the CA - but OeHubApplication's global before-filter
        // gates every non-/setup route (including the /api/hosts calls below) behind
        // SetupController.isSetupComplete(), which is admin *and* CA together, not just admin.
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/setup")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        csrfToken = cookieManager.getCookieStore().getCookies().stream()
            .filter(c -> c.getName().equals("oe_csrf"))
            .map(HttpCookie::getValue)
            .findFirst().orElseThrow(() -> new IllegalStateException("oe_csrf cookie not set"));

        // publicKey/wrappedPrivateKey/wrappedPrivateKeyRecovery/founderWrappedWsKey are normally
        // generated client-side by setup.pebble's JS (e2eEncryption design doc §3/§4) - this test
        // posts raw HTTP with no browser/WebCrypto involved, and doesn't exercise encryption at
        // all, so placeholder opaque strings are enough to satisfy processSetup's presence check.
        var setupResponse = postForm("/setup", Map.of(
            "userId", ADMIN_ID, "password", ADMIN_PW, "confirm", ADMIN_PW, "_csrf", csrfToken,
            "publicKey", "test-public-key", "wrappedPrivateKey", "test-wrapped-private-key",
            "wrappedPrivateKeyRecovery", "test-wrapped-private-key-recovery",
            "founderWrappedWsKey", "test-founder-wrapped-ws-key"));
        assertThat(setupResponse.statusCode()).isEqualTo(302);

        var caResponse = postForm("/setup/ca/generate", Map.of(
            "caName", "oeHub FwdProxy Test CA", "_csrf", csrfToken));
        assertThat(caResponse.statusCode()).isEqualTo(302);
    }

    @Test
    @Order(2)
    void savesAndSelectsHostsProfileOverridingTestDomain() throws Exception {
        var createResponse = postJson("/api/hosts", "");
        assertThat(createResponse.statusCode()).isEqualTo(201);
        var hostsId = (String) objectMapper.readValue(createResponse.body(), Map.class).get("hostsId");
        assertThat(hostsId).isNotBlank();

        // "${PROXY_SVR}" is oeHosts' special IP-field token meaning "oeHub's own IP as seen by
        // the client" (ForwardProxyServer.PROXY_SVR_PLACEHOLDER) - a literal loopback IP here
        // (e.g. "127.0.0.1") is deliberately blocked instead as an SSRF guard (overrideFor()'s
        // isLoopbackTarget(ip) check), which this placeholder is explicitly exempted from.
        var content = "${PROXY_SVR} " + OVERRIDE_DOMAIN + "\n";
        var contentResponse = patchJson("/api/hosts/" + hostsId + "/content",
            objectMapper.writeValueAsString(Map.of("content", content)));
        assertThat(contentResponse.statusCode()).isEqualTo(200);

        // Toggling "selected" is what feeds this into ForwardProxyServer's per-user host map (see
        // HostsController.apiToggleSelected -> ForwardProxyServer.refreshUserHosts()); the same
        // refresh also happens on every successful forward-proxy auth, so this isn't strictly
        // required for the test below, but mirrors what a real user does in the oeHosts UI.
        var selectResponse = patchJson("/api/hosts/" + hostsId + "/selected", null);
        assertThat(selectResponse.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void wrongCredentialsAreRejectedWithProxyAuthRequired() throws Exception {
        try (var socket = new Socket("127.0.0.1", FWDPROXY_PORT)) {
            var response = HttpRelay.send(socket,
                "GET http://" + OVERRIDE_DOMAIN + ":" + stubBackend.getAddress().getPort() + "/ HTTP/1.1",
                Map.of(
                    "Host", OVERRIDE_DOMAIN,
                    "Proxy-Authorization", basicAuth(ADMIN_ID, "wrong-password"),
                    "Proxy-Connection", "close"));
            assertThat(response.status()).isEqualTo(407);
        }
    }

    @Test
    @Order(4)
    void forwardProxyRelaysToHostsOverrideTarget() throws Exception {
        try (var socket = new Socket("127.0.0.1", FWDPROXY_PORT)) {
            var response = HttpRelay.send(socket,
                "GET http://" + OVERRIDE_DOMAIN + ":" + stubBackend.getAddress().getPort() + "/ HTTP/1.1",
                Map.of(
                    "Host", OVERRIDE_DOMAIN,
                    "Proxy-Authorization", basicAuth(ADMIN_ID, ADMIN_PW),
                    "Proxy-Connection", "close"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(STUB_BODY);
        }
    }

    @Test
    @Order(5)
    void forwardProxyTunnelsConnectRequests() throws Exception {
        // A real browser reaches an HTTPS site through this proxy via CONNECT (establish an
        // opaque tunnel, then the client does its own TLS handshake through it) rather than the
        // absolute-URI GET used above - a genuinely different code path in LittleProxy. CONNECT
        // itself doesn't care what protocol flows through the tunnel, so a plain HTTP request
        // over it is enough to prove the tunnel actually relays bytes end to end without needing
        // a second TLS layer just for this test.
        try (var socket = new Socket("127.0.0.1", FWDPROXY_PORT)) {
            var targetAuthority = OVERRIDE_DOMAIN + ":" + stubBackend.getAddress().getPort();
            var connectResponse = HttpRelay.send(socket, "CONNECT " + targetAuthority + " HTTP/1.1", Map.of(
                "Host", targetAuthority,
                "Proxy-Authorization", basicAuth(ADMIN_ID, ADMIN_PW),
                "Proxy-Connection", "keep-alive"));
            assertThat(connectResponse.status()).isEqualTo(200);

            var tunneled = HttpRelay.send(socket, "GET / HTTP/1.1", Map.of(
                "Host", OVERRIDE_DOMAIN, "Connection", "close"));
            assertThat(tunneled.status()).isEqualTo(200);
            assertThat(new String(tunneled.body(), StandardCharsets.UTF_8)).isEqualTo(STUB_BODY);
        }
    }

    private static String basicAuth(String userId, String password) {
        var token = Base64.getEncoder().encodeToString((userId + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    // ── HTTP helpers (setup + hosts API, over plain HTTP to the app port) ──────────────────

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
}
