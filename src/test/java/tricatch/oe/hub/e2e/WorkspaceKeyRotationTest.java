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
 * Proves the automatic workspace-key rotation wired into the "delete member" action
 * (e2eEncryption design doc §7): when a ws_adm removes a member, the remaining member's
 * HUB_WS_KEY wrap and every shared (public/collabo) row's wrapped_content_key across the WHOLE
 * workspace (not just the ws_adm's own rows) get replaced with fresh wraps of a newly-generated
 * workspace key - all computed in the ws_adm's browser (server-blind, same as approval, §5) -
 * and the remaining member can still decrypt everything correctly on their next login, using
 * only the new key. The departing member's account is also actually gone afterward.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkspaceKeyRotationTest {

    private static final int PORT = 39921;
    private static final String FOUNDER_ID = "rotFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String STAY_ID = "rotStay";
    private static final String STAY_PW = "StayPass123!";
    private static final String LEAVE_ID = "rotLeave";
    private static final String LEAVE_PW = "LeavePass123!";
    private static final String PROBE_LINE = "127.0.0.1 rotation-probe.oe";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page founderPage;
    private Page stayPage;
    private Page leavePage;
    private String hostsId;
    private String wrappedWsKeyBefore;
    private String wrappedContentKeyBefore;
    private Long leaveUserNo;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.mode=group"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopAll() {
        if (founderPage != null) founderPage.close();
        if (stayPage != null) stayPage.close();
        if (leavePage != null) leavePage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private void registerAndConfirmRecovery(Page page, String invite, String userId, String pw, boolean withWsName) {
        page.navigate(server.baseUrl() + "/register" + (invite != null ? "?invite=" + invite : ""));
        if (withWsName) page.locator("input[name=wsName]").fill("Rotation Co");
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

    private String createInvite(Page adminPage) {
        adminPage.navigate(server.baseUrl() + "/oehub/admin/users");
        Object code = adminPage.evaluate("""
            async () => {
                const r = await fetch('/api/admin/invites', { method: 'POST' });
                return (await r.json()).inviteCode;
            }
            """);
        return (String) code;
    }

    private void approveOnlyPending(Page adminPage) {
        adminPage.navigate(server.baseUrl() + "/oehub/admin/users");
        var pendingRow = adminPage.locator("#pendingTbody tr[data-user-no]");
        assertThat(pendingRow).hasCount(1);
        pendingRow.locator(".btn-approve-pending").click();
        assertThat(adminPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);
    }

    @Test
    @Order(1)
    void bootstrapsWorkspaceWithFounderAndTwoMembers() {
        var instAdmin = browser.newPage();
        instAdmin.navigate(server.baseUrl() + "/setup");
        instAdmin.locator("form[action='/setup'] input[name=userId]").fill("rotInstanceAdmin");
        instAdmin.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        instAdmin.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        instAdmin.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(instAdmin.locator("#recoveryCodeModal.show")).isVisible();
        instAdmin.locator("#btnRecoveryCodeContinue").click();
        assertThat(instAdmin.locator("#btnGotoLogin")).isEnabled();
        instAdmin.close();

        founderPage = browser.newPage();
        founderPage.onDialog(com.microsoft.playwright.Dialog::accept);
        registerAndConfirmRecovery(founderPage, null, FOUNDER_ID, FOUNDER_PW, true);
        founderPage.waitForURL(Pattern.compile(".*/login$"));
        login(founderPage, FOUNDER_ID, FOUNDER_PW);

        var stayInvite = createInvite(founderPage);
        stayPage = browser.newPage();
        registerAndConfirmRecovery(stayPage, stayInvite, STAY_ID, STAY_PW, false);
        approveOnlyPending(founderPage);
        login(stayPage, STAY_ID, STAY_PW);

        var leaveInvite = createInvite(founderPage);
        leavePage = browser.newPage();
        registerAndConfirmRecovery(leavePage, leaveInvite, LEAVE_ID, LEAVE_PW, false);
        approveOnlyPending(founderPage);
        login(leavePage, LEAVE_ID, LEAVE_PW);
    }

    @Test
    @Order(2)
    void stayCreatesASharedPublicProfile() {
        stayPage.addInitScript("try { localStorage.setItem('hostsGuideHidden', 'true'); } catch (e) {}");
        stayPage.navigate(server.baseUrl() + "/oehub/hosts");
        stayPage.locator("#btnAdd").click();
        assertThat(stayPage.locator("#hostsEditorBody")).isVisible();
        hostsId = (String) stayPage.evaluate(
            "() => document.querySelector('#profileList .oe-list-item').dataset.hostsId");
        assertThat(hostsId).isNotBlank();

        stayPage.locator("#cmHostsEditor").click();
        stayPage.keyboard().press("Control+A");
        stayPage.keyboard().type(PROBE_LINE);
        assertThat(stayPage.locator("#saveStatus")).containsText("Saved",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        var raw = (Map<?, ?>) stayPage.evaluate(
            "async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }",
            hostsId);
        wrappedContentKeyBefore = (String) raw.get("wrappedContentKey");
        assertThat(wrappedContentKeyBefore).isNotBlank();

        wrappedWsKeyBefore = (String) stayPage.evaluate(
            "async () => (await (await fetch('/api/user/crypto-keys')).json()).wrappedWsKey");
        assertThat(wrappedWsKeyBefore).isNotBlank();
    }

    @Test
    @Order(3)
    void founderDeletesLeave_rotatingTheWorkspaceKey() {
        founderPage.navigate(server.baseUrl() + "/oehub/admin/users");
        var leaveRow = founderPage.locator("tbody tr").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(LEAVE_ID));
        assertThat(leaveRow).hasCount(1);
        leaveUserNo = Long.parseLong(leaveRow.getAttribute("data-user-no"));

        leaveRow.locator(".btn-delete-user").click();
        assertThat(founderPage.locator("tbody tr").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(LEAVE_ID)))
                .hasCount(0, new com.microsoft.playwright.assertions.LocatorAssertions.HasCountOptions().setTimeout(5000));
    }

    @Test
    @Order(4)
    void wrapsActuallyChanged_serverSide() {
        var rows = (List<?>) founderPage.evaluate(
            "async () => await (await fetch('/api/admin/workspace/rotation-rows')).json()");
        var row = rows.stream()
            .map(o -> (Map<?, ?>) o)
            .filter(m -> hostsId.equals(m.get("hostsId")))
            .findFirst().orElseThrow();
        assertThat((String) row.get("wrappedContentKey")).isNotEqualTo(wrappedContentKeyBefore);
    }

    @Test
    @Order(5)
    void stayCanStillDecryptEverything_afterReLoggingInWithTheNewKey() {
        login(stayPage, STAY_ID, STAY_PW);

        var wrappedWsKeyAfter = (String) stayPage.evaluate(
            "async () => (await (await fetch('/api/user/crypto-keys')).json()).wrappedWsKey");
        assertThat(wrappedWsKeyAfter).isNotBlank().isNotEqualTo(wrappedWsKeyBefore);

        stayPage.navigate(server.baseUrl() + "/oehub/hosts");
        var raw = (Map<?, ?>) stayPage.evaluate(
            "async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }",
            hostsId);
        assertThat((String) raw.get("wrappedContentKey")).isNotEqualTo(wrappedContentKeyBefore);

        var decrypted = (String) stayPage.evaluate(
            "(row) => OE_CONTENT_CRYPTO.decrypt(row.wrappedContentKey, row.hostsContent, row.visibility)",
            raw);
        assertThat(decrypted).contains(PROBE_LINE);
    }

    @Test
    @Order(6)
    void leaveAccountIsActuallyGone() {
        leavePage.navigate(server.baseUrl() + "/login");
        leavePage.locator("input[name=userId]").fill(LEAVE_ID);
        leavePage.locator("input[name=password]").fill(LEAVE_PW);
        leavePage.locator("#btnLoginSubmit").click();
        assertThat(leavePage.locator("#loginErrorBox")).not().hasClass("d-none");
    }
}
