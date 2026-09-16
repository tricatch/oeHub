package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Personal API tokens (HUB_API_TOKEN): creates one through the real my-info UI (Playwright - the
 * create/reveal/list/revoke flow is plain DOM+fetch with no client-side crypto involved, unlike
 * account setup), then proves the issued token authenticates a completely cookie-less HTTP request
 * (AuthController.resolveUserFromApiToken) and that revoking it takes effect immediately.
 *
 * Disabled (2026-09-16): the feature itself is disabled (unrouted in OeHubApplication, UI removed
 * from my-info.pebble) pending a clearer real-world use case. Re-enable this test alongside the
 * feature.
 */
@Disabled("API token feature is disabled - see class javadoc")
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiTokenTest {

    private static final int PORT = 39930;
    private static final String ADMIN_ID = "apiTokenAdmin";
    private static final String ADMIN_PW = "AdminPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String issuedToken;
    private String tokenId;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT);
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        page.onDialog(Dialog::accept);
    }

    @AfterAll
    void stopAll() {
        if (page != null) page.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void bootstrapsAdmin() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill(ADMIN_ID);
        page.locator("form[action='/setup'] input[name=password]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] input[name=confirm]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page).hasURL(Pattern.compile(".*/setup"));

        // standalone needs a CA configured too, or every route keeps redirecting to /setup
        // (SetupController.isSetupComplete) - same as SetupToOeProxyScenarioTest's flow.
        page.locator("input[name=caName]").fill("ApiToken Test CA");
        page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
    }

    @Test
    @Order(2)
    void createsTokenAndListsIt() {
        // processSetup already logged the admin in for this browser context - straight to my-info.
        page.navigate(server.baseUrl() + "/oehub/my/info");
        page.locator("#btnCreateApiToken").click();
        page.locator("#apiTokenNameInput").fill("ci-script");
        page.locator("#btnSubmitApiTokenCreate").click();

        assertThat(page.locator("#apiTokenRevealModal.show")).isVisible();
        issuedToken = page.locator("#apiTokenRevealValue").inputValue();
        assertThat(issuedToken).startsWith("oeh_");

        page.locator("#apiTokenRevealModal .btn-close").click();
        var row = page.locator("#apiTokenTbody tr[data-token-id]");
        assertThat(row).hasCount(1);
        assertThat(row).containsText("ci-script");
        tokenId = row.getAttribute("data-token-id");
        assertThat(tokenId).isNotBlank();
    }

    @Test
    @Order(3)
    void tokenAuthenticatesACookieLessRequest() throws Exception {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var request = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/user/api-tokens"))
            .header("Authorization", "Bearer " + issuedToken)
            .GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ci-script");
    }

    @Test
    @Order(4)
    void missingOrWrongTokenIsRejected() throws Exception {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        var withoutAuth = http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/user/api-tokens")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(withoutAuth.statusCode()).isEqualTo(401);

        var badToken = http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/user/api-tokens"))
                .header("Authorization", "Bearer oeh_not-a-real-token")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(badToken.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(5)
    void revokingTokenInvalidatesItImmediately() throws Exception {
        page.locator("tr[data-token-id='" + tokenId + "'] .btn-revoke-api-token").click();
        assertThat(page.locator("#apiTokenTbody tr[data-token-id]")).hasCount(0);

        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var response = http.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/user/api-tokens"))
                .header("Authorization", "Bearer " + issuedToken)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
