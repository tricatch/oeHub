package tricatch.oe.hub.e2e;

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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scan blocking (ScanBlocker) and the client address it keys on (ClientIp): ten anonymous 404s
 * within a minute block that address for every request, while other addresses, logged-in users
 * and loopback itself are never blocked. The server runs with -Doe.client.ip.header, standing in
 * for a local tunnel such as cloudflared: every request here comes from loopback and says which
 * client it is for in that header.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScanBlockTest {

    private static final int PORT = 39970;
    private static final String IP_HEADER = "X-Test-Client-Ip";
    private static final String ADMIN_ID = "scanAdmin";
    private static final String ADMIN_PW = "ScanBlockPass123!";

    private E2eServer server;
    private HttpClient anonymous;
    private HttpClient admin;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.client.ip.header=" + IP_HEADER));
        server.start();
        anonymous = HttpClient.newHttpClient();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        admin = HttpClient.newBuilder().cookieHandler(cookies).build();

        // Setup over plain HTTP (same as ForwardProxyRelayTest) - until it is done every route
        // redirects to /setup, and finishing it leaves the admin client logged in.
        admin.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/setup")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        var csrf = cookies.getCookieStore().getCookies().stream()
            .filter(c -> c.getName().equals("oe_csrf")).map(HttpCookie::getValue).findFirst().orElseThrow();
        assertThat(postForm(admin, "/setup", Map.of(
            "userId", ADMIN_ID, "password", ADMIN_PW, "confirm", ADMIN_PW, "_csrf", csrf,
            "publicKey", "test-public-key", "wrappedPrivateKey", "test-wrapped-private-key",
            "wrappedPrivateKeyRecovery", "test-wrapped-private-key-recovery",
            "recoveryVerifier", "test-recovery-verifier",
            "founderWrappedWsKey", "test-founder-wrapped-ws-key")).statusCode()).isEqualTo(302);
        assertThat(postForm(admin, "/setup/ca/generate", Map.of("caName", "oeHub Scan Test CA", "_csrf", csrf))
            .statusCode()).isEqualTo(302);
    }

    @AfterAll
    void stopAll() {
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void tenAnonymous404s_blockThatAddress_forEveryRequest() throws Exception {
        var scanner = "203.0.113.10";
        for (int i = 0; i < 10; i++) {
            assertThat(get(anonymous, "/no-such-path-" + i, scanner).statusCode()).isEqualTo(404);
        }
        assertThat(get(anonymous, "/login", scanner).statusCode()).isEqualTo(403);
        assertThat(get(anonymous, "/no-such-path", scanner).statusCode()).isEqualTo(403);

        // Another address is unaffected.
        assertThat(get(anonymous, "/login", "203.0.113.11").statusCode()).isEqualTo(200);

        var log = Files.readString(server.homeDir().resolve("server.log"));
        assertThat(log).contains("Blocking " + scanner + " for 10 min");
    }

    @Test
    @Order(2)
    void nineAnonymous404s_doNotBlock() throws Exception {
        var visitor = "203.0.113.20";
        for (int i = 0; i < 9; i++) {
            assertThat(get(anonymous, "/no-such-path-" + i, visitor).statusCode()).isEqualTo(404);
        }
        assertThat(get(anonymous, "/login", visitor).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void loggedInUsers404s_areNotCounted() throws Exception {
        var user = "203.0.113.30";
        for (int i = 0; i < 12; i++) {
            assertThat(get(admin, "/no-such-path-" + i, user).statusCode()).isEqualTo(404);
        }
        assertThat(get(admin, "/oehub/hosts", user).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(4)
    void loopbackWithoutTheHeader_isNeverBlocked() throws Exception {
        for (int i = 0; i < 12; i++) {
            assertThat(get(anonymous, "/no-such-path-" + i, null).statusCode()).isEqualTo(404);
        }
        assertThat(get(anonymous, "/login", null).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(5)
    void faviconIco_isNotA404() throws Exception {
        var response = get(anonymous, "/favicon.ico", "203.0.113.40");
        assertThat(response.statusCode()).isEqualTo(301);
        assertThat(response.headers().firstValue("Location")).hasValue("/icon/oe_logo_favicon.svg");
    }

    private HttpResponse<String> get(HttpClient client, String path, String clientIp) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET();
        if (clientIp != null) builder.header(IP_HEADER, clientIp);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(HttpClient client, String path, Map<String, String> params) throws Exception {
        var body = params.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
