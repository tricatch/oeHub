package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the instance-admin workspace console (cloudGroupService design doc §2.5 "인스턴스
 * admin과의 격리", §3 item 2): the instance admin can list every workspace and suspend/reactivate
 * one - suspension blocks login for every member of THAT workspace only, with a distinct error,
 * while leaving other workspaces unaffected - and the screen/API is unreachable by anyone but the
 * instance admin, and doesn't exist at all in standalone mode (which has exactly one workspace,
 * itself - design doc §2.7).
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkspaceAdminConsoleTest {

    private static final int PORT = 39927;
    // Deliberately not PORT+1: the primary workspace-mode server above keeps its H2 console bound on
    // PORT+1 for this whole test class's lifetime (E2eServer never stops it early), and this
    // standalone server (and its own H2 console, STANDALONE_PORT+1) must not collide with that.
    private static final int STANDALONE_PORT = 45210;
    private static final String INSTANCE_ADMIN_ID = "wsConsoleInstAdmin";
    private static final String INSTANCE_ADMIN_PW = "InstAdminPass123!";
    private static final String ALPHA_WS_NAME = "Alpha Workspace";
    private static final String ALPHA_FOUNDER_ID = "wsConsoleAlpha";
    private static final String ALPHA_FOUNDER_PW = "AlphaPass123!";
    private static final String BETA_WS_NAME = "Beta Workspace";
    private static final String BETA_FOUNDER_ID = "wsConsoleBeta";
    private static final String BETA_FOUNDER_PW = "BetaPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page instAdminPage;
    private Page alphaPage;
    private Page betaPage;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopAll() {
        if (instAdminPage != null) instAdminPage.close();
        if (alphaPage != null) alphaPage.close();
        if (betaPage != null) betaPage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private void registerAndConfirmRecovery(Page page, String wsName, String userId, String pw) {
        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill(wsName);
        page.locator("input[name=userId]").fill(userId);
        page.locator("input[name=password]").fill(pw);
        page.locator("input[name=confirmPassword]").fill(pw);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
    }

    private void login(Page page, String userId, String pw) {
        page.navigate(server.baseUrl() + "/login");
        page.locator("input[name=userId]").fill(userId);
        page.locator("input[name=password]").fill(pw);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");
    }

    /** Attempts a login and returns the error text shown in #loginErrorBox (login.pebble's JS
     *  pulls it straight out of the re-rendered response, see AuthController.processLogin).
     *  Waits for actual visibility (the box starts "d-none") rather than a class-value check,
     *  since "not equal to the literal string 'd-none'" is trivially true for the full
     *  multi-class attribute either way and would race ahead of the async fetch/DOM update. */
    private String attemptLoginExpectingFailure(Page page, String userId, String pw) {
        page.navigate(server.baseUrl() + "/login");
        page.locator("input[name=userId]").fill(userId);
        page.locator("input[name=password]").fill(pw);
        page.locator("#btnLoginSubmit").click();
        assertThat(page.locator("#loginErrorBox")).isVisible(
            new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
        return page.locator("#loginErrorBox").textContent().trim();
    }

    @Test
    @Order(1)
    void bootstrapsInstanceAdminAndTwoIndependentWorkspaces() {
        instAdminPage = browser.newPage();
        instAdminPage.navigate(server.baseUrl() + "/setup");
        instAdminPage.locator("form[action='/setup'] input[name=userId]").fill(INSTANCE_ADMIN_ID);
        instAdminPage.locator("form[action='/setup'] input[name=password]").fill(INSTANCE_ADMIN_PW);
        instAdminPage.locator("form[action='/setup'] input[name=confirm]").fill(INSTANCE_ADMIN_PW);
        instAdminPage.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(instAdminPage.locator("#recoveryCodeModal.show")).isVisible();
        instAdminPage.locator("#btnRecoveryCodeContinue").click();
        assertThat(instAdminPage.locator("#btnGotoLogin")).isEnabled();
        login(instAdminPage, INSTANCE_ADMIN_ID, INSTANCE_ADMIN_PW);

        alphaPage = browser.newPage();
        registerAndConfirmRecovery(alphaPage, ALPHA_WS_NAME, ALPHA_FOUNDER_ID, ALPHA_FOUNDER_PW);
        alphaPage.waitForURL(Pattern.compile(".*/login$"));
        login(alphaPage, ALPHA_FOUNDER_ID, ALPHA_FOUNDER_PW);

        betaPage = browser.newPage();
        registerAndConfirmRecovery(betaPage, BETA_WS_NAME, BETA_FOUNDER_ID, BETA_FOUNDER_PW);
        betaPage.waitForURL(Pattern.compile(".*/login$"));
        login(betaPage, BETA_FOUNDER_ID, BETA_FOUNDER_PW);
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void instanceAdminSeesBothWorkspacesListed() {
        instAdminPage.navigate(server.baseUrl() + "/oehub/admin/workspaces");
        // At least Alpha + Beta - not necessarily exactly 2: /setup itself creates its own
        // workspace for the instance admin regardless of mode (design doc §2.7), so this list can
        // legitimately contain more than the two founded here.
        assertThat(instAdminPage.locator("tbody#workspacesTbody tr[data-ws-no]")).not().hasCount(0,
            new com.microsoft.playwright.assertions.LocatorAssertions.HasCountOptions().setTimeout(5000));

        var workspaces = (List<?>) instAdminPage.evaluate(
            "async () => await (await fetch('/api/admin/workspaces')).json()");
        var byName = workspaces.stream().map(o -> (Map<String, Object>) o)
            .collect(java.util.stream.Collectors.toMap(m -> (String) m.get("wsName"), m -> m));
        assertThat(byName).containsKey(ALPHA_WS_NAME);
        assertThat(byName).containsKey(BETA_WS_NAME);
        assertThat((String) byName.get(ALPHA_WS_NAME).get("status")).isEqualTo("active");
        assertThat((String) byName.get(BETA_WS_NAME).get("status")).isEqualTo("active");
        // A count only, never member names/details (design doc §2.5 isolation).
        assertThat(byName.get(ALPHA_WS_NAME)).doesNotContainKey("users");
        assertThat(byName.get(ALPHA_WS_NAME)).doesNotContainKey("members");
    }

    @Test
    @Order(3)
    void nonInstanceAdminCannotReachTheConsole() {
        var pageStatus = (Integer) alphaPage.evaluate(
            "async () => (await fetch('/oehub/admin/workspaces')).status");
        assertThat(pageStatus).isEqualTo(403);
        var apiStatus = (Integer) alphaPage.evaluate(
            "async () => (await fetch('/api/admin/workspaces')).status");
        assertThat(apiStatus).isEqualTo(403);
    }

    @Test
    @Order(4)
    void instanceAdminSuspendsAlpha_blockingItsMembersOnly() {
        instAdminPage.navigate(server.baseUrl() + "/oehub/admin/workspaces");
        instAdminPage.onDialog(com.microsoft.playwright.Dialog::accept);
        var alphaRow = instAdminPage.locator("tbody#workspacesTbody tr").filter(
            new com.microsoft.playwright.Locator.FilterOptions().setHasText(ALPHA_WS_NAME));
        assertThat(alphaRow).hasCount(1);
        alphaRow.locator(".btn-toggle-ws-status").click();

        assertThat(instAdminPage.locator("#toast")).containsText("Workspace status updated.",
            new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
        assertThat(alphaRow).hasAttribute("data-status", "suspended");

        // Suspended workspace's own member is blocked, with a distinct error - not the generic
        // invalid-credentials or pending-approval message (design doc §2.5).
        var alphaError = attemptLoginExpectingFailure(alphaPage, ALPHA_FOUNDER_ID, ALPHA_FOUNDER_PW);
        assertThat(alphaError).isEqualTo("This workspace has been suspended.");

        // The other workspace is completely unaffected.
        login(betaPage, BETA_FOUNDER_ID, BETA_FOUNDER_PW);
    }

    @Test
    @Order(5)
    void reactivatingAlphaRestoresLogin() {
        instAdminPage.navigate(server.baseUrl() + "/oehub/admin/workspaces");
        var alphaRow = instAdminPage.locator("tbody#workspacesTbody tr").filter(
            new com.microsoft.playwright.Locator.FilterOptions().setHasText(ALPHA_WS_NAME));
        assertThat(alphaRow).hasCount(1);
        alphaRow.locator(".btn-toggle-ws-status").click();

        assertThat(instAdminPage.locator("#toast")).containsText("Workspace status updated.",
            new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
        assertThat(alphaRow).hasAttribute("data-status", "active");

        login(alphaPage, ALPHA_FOUNDER_ID, ALPHA_FOUNDER_PW);
    }

    /**
     * The console has no meaning in standalone mode (design doc §2.7 - a standalone instance IS
     * its one workspace) and must not exist there: a workspace's own ws_adm equivalent (the
     * instance 'adm', which passes isWorkspaceAdmin() in standalone) reaches the generic
     * "/oehub/admin/*"/"/api/admin/*" gate fine, but no route is registered behind it, so Javalin
     * falls through to a plain 404 - the same "unrouted" shape other group-only endpoints have when
     * hit under the opposite mode.
     */
    @Test
    @Order(6)
    void consoleDoesNotExistInStandaloneMode() throws Exception {
        try (var standalone = new E2eServer(STANDALONE_PORT)) {
            standalone.start();
            var page = browser.newPage();
            page.navigate(standalone.baseUrl() + "/setup");
            page.locator("form[action='/setup'] input[name=userId]").fill("wsConsoleStandaloneAdmin");
            page.locator("form[action='/setup'] input[name=password]").fill("StandalonePass123!");
            page.locator("form[action='/setup'] input[name=confirm]").fill("StandalonePass123!");
            page.locator("form[action='/setup'] button[type=submit]").click();
            assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
            page.locator("#btnRecoveryCodeContinue").click();

            // Unlike workspace mode, standalone setup isn't complete until the CA step is also done
            // (design doc §2.6 - oeProxy, and therefore its CA, only exists in standalone) - until
            // then every non-/setup path redirects back to /setup (OeHubApplication's
            // "isSetupComplete" before-filter), which is why this step can't be skipped here even
            // though this test doesn't otherwise care about oeProxy/CA at all.
            page.locator("input[name=caName]").fill("WsConsole Standalone Test CA");
            page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();
            assertThat(page.locator("#btnGotoLogin")).isEnabled();

            page.navigate(standalone.baseUrl() + "/login");
            page.locator("input[name=userId]").fill("wsConsoleStandaloneAdmin");
            page.locator("input[name=password]").fill("StandalonePass123!");
            page.locator("#btnLoginSubmit").click();
            page.waitForURL(standalone.baseUrl() + "/");

            var pageStatus = (Integer) page.evaluate(
                "async () => (await fetch('/oehub/admin/workspaces')).status");
            assertThat(pageStatus).isEqualTo(404);
            var apiStatus = (Integer) page.evaluate(
                "async () => (await fetch('/api/admin/workspaces')).status");
            assertThat(apiStatus).isEqualTo(404);

            page.close();
        }
    }
}
