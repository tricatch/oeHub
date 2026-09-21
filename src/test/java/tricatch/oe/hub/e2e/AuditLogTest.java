package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
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

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier-1 (security/access-control) audit trail: drives five of the recorded actions - CA
 * generation, membership approval, role change, password reset, and account deletion - through the
 * real self-hosted admin UI, then confirms every one of them shows up on the audit log page with the
 * right human-readable action label, target, and actor.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditLogTest {

    private static final int PORT = 39931;
    private static final String ADMIN_ID = "auditAdmin";
    private static final String ADMIN_PW = "AdminPass123!";
    private static final String USER_ID = "auditUser01";
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
    void bootstrapsAdminAndGeneratesCa() {
        // The wizard's own CA step (SetupController.generateCa -> settings.generateCaToDir) is a
        // one-time bootstrap with no prior admin session to attribute it to, so it's deliberately
        // NOT audited - only SettingsController.generateCa (a later regeneration from the Settings
        // page, once the instance is already up and an admin is logged in) is a Tier-1 action.
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill(ADMIN_ID);
        page.locator("form[action='/setup'] input[name=password]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] input[name=confirm]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page).hasURL(Pattern.compile(".*/setup"));

        page.locator("input[name=caName]").fill("Audit Test CA");
        page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
    }

    @Test
    @Order(2)
    void regeneratesCaFromSettingsPage() {
        // This is the one that should be audited (settings.ca.generate).
        page.navigate(server.baseUrl() + "/adm/settings");
        // The CA form is collapsed by default once a CA already exists - expand it first.
        page.locator("[data-bs-target='#caConfigureDiv']").click();
        assertThat(page.locator("#caConfigureDiv")).isVisible();
        page.locator("input[name=caName]").fill("Audit Test CA v2");
        page.locator("form[action='/adm/settings/ca/generate'] button[type=submit]").click();
        assertThat(page).hasURL(Pattern.compile(".*/adm/settings.*"));
    }

    @Test
    @Order(3)
    void registersAndApprovesASecondUser() {
        page.navigate(server.baseUrl() + "/logout");
        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(USER_PW);
        page.locator("input[name=confirmPassword]").fill(USER_PW);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page).hasURL(Pattern.compile(".*/login\\?registered=pending"));

        page.locator("input[name=userId]").fill(ADMIN_ID);
        page.locator("input[name=password]").fill(ADMIN_PW);
        page.locator("form[action='/login'] button[type=submit]").click();
        // Wait for the login's own navigation to land before issuing another one - otherwise the
        // next navigate() can race the POST /login response and fire before its Set-Cookie applies.
        assertThat(page).hasURL(server.baseUrl() + "/");

        page.navigate(server.baseUrl() + "/wsa/users");
        var pendingRow = page.locator("#pendingTbody tr[data-user-no]");
        assertThat(pendingRow).hasCount(1);
        pendingRow.locator(".btn-approve-pending").click();
        // The section stays on screen with nobody waiting - it just shows its empty-state row.
        assertThat(page.locator("#pendingSection")).isVisible();
        assertThat(page.locator("#pendingTbody tr[data-user-no]")).hasCount(0);
        assertThat(page.locator("#pendingTbody tr")).hasCount(1);
    }

    @Test
    @Order(4)
    void changesRoleResetsPasswordAndDeletesTheUser() {
        var userRow = page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(USER_ID));
        assertThat(userRow).hasCount(1);

        userRow.locator(".btn-toggle-role").click();
        userRow.locator(".btn-reset-password").click();
        assertThat(page.locator("#pwResetModal.show")).isVisible();
        page.locator("#pwResetModal .btn-close").click();

        userRow.locator(".btn-delete-user").click();
        assertThat(page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(USER_ID))).hasCount(0);
    }

    @Test
    @Order(5)
    void auditLogShowsEveryRecordedActionWithReadableLabels() {
        page.navigate(server.baseUrl() + "/wsa/audit-log");
        var rows = page.locator("#auditLogTbody tr");
        // settings.ca.generate, user.approve, user.role_change, user.password_reset, user.delete
        assertThat(rows).hasCount(5);

        var tbody = page.locator("#auditLogTbody");
        assertThat(tbody).containsText("Generated root CA");
        assertThat(tbody).containsText("Approved membership");
        assertThat(tbody).containsText("Changed role");
        assertThat(tbody).containsText("Reset password");
        assertThat(tbody).containsText("Deleted account");
        // Every one of these was performed by the admin - never the departed user, who by now has
        // no way to be confused with the actor column.
        assertThat(tbody).containsText(ADMIN_ID);
        // The deleted user's own userId is still readable from the detail column of the role
        // change / password reset / deletion rows (AuditLogger.detail("userId", ...)), even though
        // their account and user_no no longer resolve to anything.
        assertThat(tbody).containsText(USER_ID);
    }
}
