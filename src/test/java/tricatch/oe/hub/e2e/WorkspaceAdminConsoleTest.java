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
 * instance admin, and doesn't exist at all in self-hosted mode (which has exactly one workspace,
 * itself - design doc §2.7).
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkspaceAdminConsoleTest {

    private static final int PORT = 39927;
    // Deliberately not PORT+1: the primary workspace-mode server above keeps its H2 console bound on
    // PORT+1 for this whole test class's lifetime (E2eServer never stops it early), and this
    // self-hosted server (and its own H2 console, SELF_HOSTED_PORT+1) must not collide with that.
    private static final int SELF_HOSTED_PORT = 45210;
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
        instAdminPage.navigate(server.baseUrl() + "/adm/workspaces");
        // At least Alpha + Beta - not necessarily exactly 2: /setup itself creates its own
        // workspace for the instance admin regardless of mode (design doc §2.7), so this list can
        // legitimately contain more than the two founded here.
        assertThat(instAdminPage.locator("tbody#workspacesTbody tr[data-ws-no]")).not().hasCount(0,
            new com.microsoft.playwright.assertions.LocatorAssertions.HasCountOptions().setTimeout(5000));

        var workspaces = (List<?>) instAdminPage.evaluate(
            "async () => await (await fetch('/api/adm/workspaces')).json()");
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
            "async () => (await fetch('/adm/workspaces')).status");
        assertThat(pageStatus).isEqualTo(403);
        var apiStatus = (Integer) alphaPage.evaluate(
            "async () => (await fetch('/api/adm/workspaces')).status");
        assertThat(apiStatus).isEqualTo(403);
    }

    @Test
    @Order(4)
    void instanceAdminSuspendsAlpha_blockingItsMembersOnly() {
        instAdminPage.navigate(server.baseUrl() + "/adm/workspaces");
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
        instAdminPage.navigate(server.baseUrl() + "/adm/workspaces");
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
     * The console has no meaning in self-hosted mode (design doc §2.7 - a self-hosted instance IS
     * its one workspace) and must not exist there: a workspace's own wsa equivalent (the
     * instance 'adm', which passes isWorkspaceAdmin() in self-hosted) reaches the
     * "/adm/*"/"/api/adm/*" gate fine, but no route is registered behind it, so Javalin
     * falls through to a plain 404 - the same "unrouted" shape other workspace-only endpoints have when
     * hit under the opposite mode.
     */
    @Test
    @Order(6)
    void consoleDoesNotExistInSelfHostedMode() throws Exception {
        try (var selfHosted = new E2eServer(SELF_HOSTED_PORT)) {
            selfHosted.start();
            var page = browser.newPage();
            page.navigate(selfHosted.baseUrl() + "/setup");
            page.locator("form[action='/setup'] input[name=userId]").fill("wsConsoleSelfHostedAdmin");
            page.locator("form[action='/setup'] input[name=password]").fill("SelfHostedPass123!");
            page.locator("form[action='/setup'] input[name=confirm]").fill("SelfHostedPass123!");
            page.locator("form[action='/setup'] button[type=submit]").click();

            // Unlike workspace mode, self-hosted setup isn't complete until the CA step is also done
            // (design doc §2.6 - oeProxy, and therefore its CA, only exists in self-hosted) - until
            // then every non-/setup path redirects back to /setup (OeHubApplication's
            // "isSetupComplete" before-filter), which is why this step can't be skipped here even
            // though this test doesn't otherwise care about oeProxy/CA at all.
            page.locator("input[name=caName]").fill("WsConsole SelfHosted Test CA");
            page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();
            assertThat(page.locator("#btnGotoLogin")).isEnabled();

            page.navigate(selfHosted.baseUrl() + "/login");
            page.locator("input[name=userId]").fill("wsConsoleSelfHostedAdmin");
            page.locator("input[name=password]").fill("SelfHostedPass123!");
            page.locator("#btnLoginSubmit").click();
            page.waitForURL(selfHosted.baseUrl() + "/");

            var pageStatus = (Integer) page.evaluate(
                "async () => (await fetch('/adm/workspaces')).status");
            assertThat(pageStatus).isEqualTo(404);
            var apiStatus = (Integer) page.evaluate(
                "async () => (await fetch('/api/adm/workspaces')).status");
            assertThat(apiStatus).isEqualTo(404);

            // The allowed-domains list (forward proxy relay + PROXY_SVR lookups) belongs to oeProxy,
            // which only self-hosted has: the section is shown and its API works here.
            page.navigate(selfHosted.baseUrl() + "/adm/settings");
            assertThat(page.locator("#allowedDomains")).hasCount(1);
            var saveStatus = (Integer) page.evaluate(
                "async () => (await fetch('/api/adm/settings/allowed-domains', {method: 'POST', "
                    + "headers: {'Content-Type': 'application/json'}, body: JSON.stringify({domains: 'hub.example.com'})})).status");
            assertThat(saveStatus).isEqualTo(200);
            // Same for the upstream-address restriction: an oeProxy setting, offered only here.
            assertThat(page.locator("#internalOnlyUpstream")).hasCount(1);
            var upstreamStatus = (Integer) page.evaluate(
                "async () => (await fetch('/api/adm/settings/internal-only-upstream', {method: 'POST', "
                    + "headers: {'Content-Type': 'application/json'}, body: JSON.stringify({enabled: true})})).status");
            assertThat(upstreamStatus).isEqualTo(200);

            page.close();
        }
    }

    private Integer status(Page page, String path) {
        return (Integer) page.evaluate("async (u) => (await fetch(u)).status", path);
    }

    /**
     * The role-scoped URL prefixes (see Role): "/adm/*" and "/api/adm/*" belong to the instance
     * admin only, "/wsa/*" and "/api/wsa/*" to a workspace admin only - in workspace mode neither
     * role reaches the other's tree, and anonymous callers reach neither (401 for the API, a
     * redirect to the login page for pages).
     */
    @Test
    @Order(7)
    void roleUrlPrefixesAreEnforced() {
        // wsa: its own workspace's tree, never the instance-wide one - including the global
        // presets and settings API that used to be reachable through "/api/admin/*".
        assertThat(status(alphaPage, "/wsa/users")).isEqualTo(200);
        assertThat(status(alphaPage, "/api/wsa/users")).isEqualTo(200);
        assertThat(status(alphaPage, "/adm/settings")).isEqualTo(403);
        assertThat(status(alphaPage, "/api/adm/hosts/ua")).isEqualTo(403);
        assertThat(status(alphaPage, "/api/adm/hosts/url")).isEqualTo(403);

        // adm in workspace mode: the reverse - it must never reach a tenant's internals.
        assertThat(status(instAdminPage, "/adm/settings")).isEqualTo(200);
        assertThat(status(instAdminPage, "/api/adm/hosts/ua")).isEqualTo(200);
        assertThat(status(instAdminPage, "/wsa/users")).isEqualTo(403);
        assertThat(status(instAdminPage, "/api/wsa/users")).isEqualTo(403);

        var anon = playwright.request().newContext();
        try {
            assertThat(anon.get(server.baseUrl() + "/api/adm/hosts/ua").status()).isEqualTo(401);
            assertThat(anon.get(server.baseUrl() + "/api/wsa/users").status()).isEqualTo(401);
            var redirect = anon.get(server.baseUrl() + "/adm/settings",
                com.microsoft.playwright.options.RequestOptions.create().setMaxRedirects(0));
            assertThat(redirect.status()).isEqualTo(302);
            assertThat(redirect.headers().get("location")).startsWith("/login");
        } finally {
            anon.dispose();
        }
    }

    /** oeProxy (and with it the allowed-domains list) doesn't exist in workspace mode: the settings
     *  page has no such section, still loads and works without script errors, and the API is unrouted. */
    @Test
    @Order(9)
    void allowedDomainsSettingIsNotOfferedInWorkspaceMode() {
        var scriptErrors = new java.util.concurrent.CopyOnWriteArrayList<String>();
        instAdminPage.onPageError(scriptErrors::add);
        instAdminPage.navigate(server.baseUrl() + "/adm/settings");

        assertThat(instAdminPage.locator("#allowedDomains")).hasCount(0);
        assertThat(instAdminPage.locator("#btnSaveBackupInterval")).hasCount(1);
        var apiStatus = (Integer) instAdminPage.evaluate(
            "async () => (await fetch('/api/adm/settings/allowed-domains', {method: 'POST', "
                + "headers: {'Content-Type': 'application/json'}, body: JSON.stringify({domains: 'x.example.com'})})).status");
        assertThat(apiStatus).isEqualTo(404);
        assertThat(instAdminPage.locator("#internalOnlyUpstream")).hasCount(0);
        var upstreamStatus = (Integer) instAdminPage.evaluate(
            "async () => (await fetch('/api/adm/settings/internal-only-upstream', {method: 'POST', "
                + "headers: {'Content-Type': 'application/json'}, body: JSON.stringify({enabled: false})})).status");
        assertThat(upstreamStatus).isEqualTo(404);
        // The other oeProxy-only sections (CA, IP identifier, upstream certificate, forward proxy,
        // oeOID domains) are hidden too, and their save routes are unrouted.
        for (var id : new String[] {"identifierIp", "trustInternalCert", "fwdproxyEnabled",
                                    "oidDomainDefault", "btnSaveOidDomainDefault"}) {
            assertThat(instAdminPage.locator("#" + id)).hasCount(0);
        }
        assertThat(instAdminPage.locator("form[action='/adm/settings/ca/generate']")).hasCount(0);
        for (var path : new String[] {"identifier", "trust-internal-cert", "oid-domain-default"}) {
            var status = (Integer) instAdminPage.evaluate(
                "async () => (await fetch('/api/adm/settings/" + path + "', {method: 'POST', "
                    + "headers: {'Content-Type': 'application/json'}, body: '{}'})).status");
            assertThat(status).as(path).isEqualTo(404);
        }
        assertThat(scriptErrors).isEmpty();
    }

    /** The personal settings page (any logged-in account) has no oeOID domain section in workspace
     *  mode either, and the API behind it is unrouted. */
    @Test
    @Order(11)
    void personalSettingsHasNoOeoidSectionInWorkspaceMode() {
        var scriptErrors = new java.util.concurrent.CopyOnWriteArrayList<String>();
        instAdminPage.onPageError(scriptErrors::add);
        instAdminPage.navigate(server.baseUrl() + "/oehub/my/setting");

        assertThat(instAdminPage.locator("#uaPresetTable")).hasCount(1);
        assertThat(instAdminPage.locator("#myOidDomainList")).hasCount(0);
        assertThat(instAdminPage.locator("#btnSaveMyOidDomain")).hasCount(0);
        var status = (Integer) instAdminPage.evaluate(
            "async () => (await fetch('/api/oid/domain/my', {method: 'POST', "
                + "headers: {'Content-Type': 'application/json'}, body: JSON.stringify({domainList: 'x.oe'})})).status");
        assertThat(status).isEqualTo(404);
        assertThat(scriptErrors).isEmpty();
    }

    /** The setup-created "SYSTEM" workspace is reserved: registering a workspace with that name is
     *  refused whatever the letter case, so nobody can pose as it. */
    @Test
    @Order(10)
    void systemWorkspaceNameCannotBeRegisteredInAnyCase() {
        for (var name : new String[] {"SYSTEM", "system"}) {
            var page = browser.newPage();
            try {
                registerAndConfirmRecovery(page, name, "sysclash" + name.length(), "Sys-Clash-Pw-1");
                assertThat(page.locator(".alert-danger")).isVisible();
            } finally {
                page.close();
            }
        }
    }

    /** Suspending the workspace the instance admin belongs to would block their own next login
     *  with nobody left to reactivate it, so the server refuses and the console shows no button. */
    @Test
    @Order(8)
    @SuppressWarnings("unchecked")
    void instanceAdminCannotSuspendTheirOwnWorkspace() {
        instAdminPage.navigate(server.baseUrl() + "/adm/workspaces");
        var workspaces = (List<?>) instAdminPage.evaluate(
            "async () => await (await fetch('/api/adm/workspaces')).json()");
        var own = workspaces.stream().map(o -> (Map<String, Object>) o)
            .filter(m -> "SYSTEM".equals(m.get("wsName"))).findFirst().orElseThrow();
        var ownWsNo = String.valueOf(((Number) own.get("wsNo")).longValue());

        var patchStatus = (Integer) instAdminPage.evaluate(
            "async (n) => (await fetch('/api/adm/workspaces/' + n + '/status', {method: 'PATCH', "
                + "headers: {'Content-Type': 'application/json'}, body: JSON.stringify({status: 'suspended'})})).status",
            ownWsNo);
        assertThat(patchStatus).isEqualTo(400);

        assertThat(instAdminPage.locator("tbody#workspacesTbody tr[data-ws-no='" + ownWsNo + "']"))
            .hasCount(1);
        assertThat(instAdminPage.locator(
            "tbody#workspacesTbody tr[data-ws-no='" + ownWsNo + "'] .btn-toggle-ws-status")).hasCount(0);
    }
}
