package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the whole first-run journey against a real oeHub process (via {@link E2eServer}):
 * setup wizard (admin account + CA) → logout → self-registration → login → oeHosts UI →
 * oeProxy UI. One browser page is reused across the ordered steps so session/cookie state
 * (and each step's on-screen result) carries forward exactly as it would for a real user.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SetupToOeProxyScenarioTest {

    private static final int PORT = 39913;
    private static final String ADMIN_ID = "e2eadmin";
    private static final String ADMIN_PW = "AdminPass123!";
    private static final String USER_ID = "e2euser01";
    private static final String USER_PW = "UserPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT);
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        // oeHosts/oeProxy's delete button goes through a native confirm() dialog - without a
        // handler Playwright leaves it open and every later action on the page just times out.
        page.onDialog(com.microsoft.playwright.Dialog::accept);
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
    void freshInstanceRedirectsToSetupWizard() {
        page.navigate(server.baseUrl() + "/");
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/setup"));
    }

    @Test
    @Order(2)
    void setupCreatesAdminAccount() {
        page.locator("form[action='/setup'] input[name=userId]").fill(ADMIN_ID);
        page.locator("form[action='/setup'] input[name=password]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] input[name=confirm]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] button[type=submit]").click();

        // setup.pebble intercepts submit to generate the bootstrap admin's keypair client-side
        // (e2eEncryption design doc §3) before actually posting - self-hosted skips the
        // recovery-code modal entirely (dummy identity crypto, nothing ever decrypts it) and
        // submits immediately.
        // processSetup logs the new admin in immediately and redirects back to /setup with the
        // admin step now showing a "configured" badge - that badge is our success signal.
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/setup"));
        assertThat(page.locator(".badge.bg-success")).hasCount(1);
    }

    @Test
    @Order(3)
    void setupGeneratesCaCertificate() {
        page.locator("input[name=caName]").fill("oeHub Test CA");
        page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();

        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/setup"));
        // Admin + CA sections are both configured now.
        assertThat(page.locator(".badge.bg-success")).hasCount(2);
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
    }

    @Test
    @Order(4)
    void loggedOutAdminReachesRegistrationPage() {
        page.navigate(server.baseUrl() + "/logout");
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/login"));

        page.navigate(server.baseUrl() + "/register");
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/register"));
    }

    @Test
    @Order(5)
    void registersNewRegularUser() {
        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(USER_PW);
        page.locator("input[name=confirmPassword]").fill(USER_PW);
        page.locator("form[action='/register'] button[type=submit]").click();

        // register.pebble intercepts submit to generate a keypair client-side (e2eEncryption
        // design doc §3) before actually posting the form - self-hosted skips the recovery-code
        // modal entirely (dummy identity crypto, nothing ever decrypts it) and submits
        // immediately.
        // Self-registration now starts as 'pending' (cloudGroupService design doc §2.3) and can't
        // log in until a workspace admin approves it - see order(6) below.
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/login\\?registered=pending"));
    }

    @Test
    @Order(6)
    void adminApprovesTheNewUser() {
        page.locator("input[name=userId]").fill(ADMIN_ID);
        page.locator("input[name=password]").fill(ADMIN_PW);
        page.locator("form[action='/login'] button[type=submit]").click();
        assertThat(page).hasURL(server.baseUrl() + "/");

        page.navigate(server.baseUrl() + "/oehub/admin/users");
        var pendingRow = page.locator("#pendingTbody tr[data-user-no]");
        assertThat(pendingRow).hasCount(1);
        assertThat(pendingRow).containsText(USER_ID);

        pendingRow.locator(".btn-approve-pending").click();
        assertThat(page.locator("#pendingSection")).isHidden();

        page.navigate(server.baseUrl() + "/logout");
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/login"));
    }

    @Test
    @Order(7)
    void logsInAsTheNewUser() {
        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(USER_PW);
        page.locator("form[action='/login'] button[type=submit]").click();

        // "/" renders templates/layout.pebble, which has no navbar - just the landing hero and
        // tool cards. The navbar (with the logged-in username) only appears on /oehub/* pages.
        assertThat(page).hasURL(server.baseUrl() + "/");
        assertThat(page.locator(".oe-hero-title")).hasText("oeHub");
    }

    @Test
    @Order(8)
    void oeHostsAddsRenamesEditsAndDeletesProfile() {
        page.locator("a.oe-tool-card[href='/oehub/hosts']").click();
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/oehub/hosts"));
        assertThat(page.locator(".oe-navbar")).containsText(USER_ID);
        dismissGuideModalIfShown();

        // Add
        page.locator("#btnAdd").click();
        var listItems = page.locator("#profileList .oe-list-item");
        assertThat(listItems).hasCount(1);
        assertThat(page.locator("#hostsEditorBody")).isVisible();

        // Rename (double-click the sidebar name, same as a user renaming a profile)
        page.locator("#profileList .oe-list-name").first().dblclick();
        page.keyboard().press("Control+A");
        page.keyboard().type("E2E Profile");
        page.keyboard().press("Enter");
        assertThat(page.locator("#editorTitle")).hasText("E2E Profile");
        assertThat(page.locator("#profileList .oe-list-name").first()).hasText("E2E Profile");

        // Edit content
        page.locator("#cmHostsEditor").click();
        page.keyboard().type("127.0.0.1 e2e.test.local");
        // Content auto-saves 2s after the last keystroke (see hosts.pebble's scheduleSave()).
        assertThat(page.locator("#saveStatus")).containsText("Saved", new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        // Share (opens the public /share/{id}/oelink page in a new tab)
        Page sharePopup = page.waitForPopup(() -> page.locator("#btnShare").click());
        assertThat(sharePopup).hasURL(java.util.regex.Pattern.compile(".*/share/.*/oelink.*"));
        assertThat(sharePopup.locator(".share-title")).hasText("E2E Profile");
        sharePopup.close();

        // Download (the export button builds a Blob and clicks a synthetic <a download>)
        Download download = page.waitForDownload(() -> page.locator("#btnDownload").click());
        org.assertj.core.api.Assertions.assertThat(download.suggestedFilename()).isEqualTo("E2E_Profile.txt");

        // Delete (goes through a native confirm() - auto-accepted, see startAll())
        page.locator("#btnDelete").click();
        assertThat(listItems).hasCount(0);
    }

    @Test
    @Order(9)
    void oeProxyAddsRenamesAndDeletesVirtualHost() {
        page.locator("#navItem-proxy").click();
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/oehub/proxy"));
        dismissGuideModalIfShown();

        // Add
        page.locator("#btnAdd").click();
        var listItems = page.locator("#vhostList .oe-list-item");
        assertThat(listItems).hasCount(1);

        // Rename (double-click the sidebar name, same as a user renaming a vhost)
        page.locator("#vhostList .oe-list-name").first().dblclick();
        page.keyboard().press("Control+A");
        page.keyboard().type("E2E Vhost");
        page.keyboard().press("Enter");
        assertThat(page.locator("#editorTitle")).hasText("E2E Vhost");
        assertThat(page.locator("#vhostList .oe-list-name").first()).hasText("E2E Vhost");

        // Share (opens the public /share/proxy/{id}/view page in a new tab)
        Page sharePopup = page.waitForPopup(() -> page.locator("#btnShare").click());
        assertThat(sharePopup).hasURL(java.util.regex.Pattern.compile(".*/share/proxy/.*"));
        assertThat(sharePopup.locator(".share-title")).hasText("E2E Vhost");
        sharePopup.close();

        // Download (the export button builds a Blob and clicks a synthetic <a download>)
        Download download = page.waitForDownload(() -> page.locator("#btnDownload").click());
        org.assertj.core.api.Assertions.assertThat(download.suggestedFilename()).isEqualTo("E2E_Vhost.yaml");

        // Delete (goes through a native confirm() - auto-accepted, see startAll())
        page.locator("#btnDelete").click();
        assertThat(listItems).hasCount(0);
    }

    /**
     * Both oeHosts and oeProxy auto-show a first-visit "install guide" modal
     * (see util.js's initGuideModal(), wired up in hosts.pebble/proxy.pebble) unless the
     * viewer already dismissed it before (tracked in localStorage) - a fresh browser profile
     * always sees it. Bootstrap adds the "show" class asynchronously (after the page's load
     * event, not on it), so checking for the modal right after navigating can race it; wait
     * for it briefly instead. While shown it intercepts pointer events for the rest of the
     * page, so a real user (and this test) has to close it before doing anything else here.
     * Clicking its own close button (rather than pressing Escape) is what actually dismisses
     * it reliably - Bootstrap's Escape handling here depends on document focus state, which a
     * script-driven click into the page doesn't reproduce the way a real keypress would.
     */
    private void dismissGuideModalIfShown() {
        var modal = page.locator(".modal.show");
        try {
            modal.waitFor(new Locator.WaitForOptions().setTimeout(3000));
        } catch (TimeoutError noGuideModalThisTime) {
            return;
        }
        modal.locator(".btn-close").click();
        assertThat(modal).hasCount(0);
    }
}
