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
 * Proves the "last ws_adm" safety net (cloudGroupService design doc §2.5 "안전장치", §3 item 1):
 * a workspace must never be left with zero admins. While it's the sole admin, demoting or deleting
 * it must be refused; once a second admin exists, either one becomes free to demote/delete again
 * (going from N to N-1 admins is fine, only reaching zero is blocked).
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LastAdminGuardTest {

    private static final int PORT = 39926;
    private static final String FOUNDER_ID = "guardFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String MEMBER_ID = "guardMember";
    private static final String MEMBER_PW = "MemberPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page founderPage;
    private Page memberPage;
    private Long founderUserNo;
    private Long memberUserNo;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopAll() {
        if (founderPage != null) founderPage.close();
        if (memberPage != null) memberPage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private void registerAndConfirmRecovery(Page page, String invite, String userId, String pw, boolean withWsName) {
        page.navigate(server.baseUrl() + "/register" + (invite != null ? "?invite=" + invite : ""));
        if (withWsName) page.locator("input[name=wsName]").fill("Guard Co");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> findMember(Page adminPage, String userId) {
        var users = (List<?>) adminPage.evaluate(
            "async () => await (await fetch('/api/admin/users')).json()");
        return users.stream()
            .map(o -> (Map<String, Object>) o)
            .filter(m -> userId.equals(m.get("userId")))
            .findFirst().orElseThrow();
    }

    // Values are interpolated straight into the JS source (rather than passed as an evaluate()
    // argument) because Playwright's Java binding cannot serialize a boxed Long as an argument -
    // same pattern WorkspaceModeKeyWrapTest/WorkspaceKeyRotationTest use for this kind of call.
    @SuppressWarnings("unchecked")
    private Map<String, Object> patchRole(Page adminPage, Long userNo, String role) {
        return (Map<String, Object>) adminPage.evaluate(String.format(
            """
            async () => {
                const r = await fetch('/api/admin/users/%d/role', {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ role: '%s' })
                });
                let body = null;
                try { body = await r.json(); } catch (e) {}
                return { status: r.status, body: body };
            }
            """, userNo, role));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deleteUser(Page adminPage, Long userNo) {
        return (Map<String, Object>) adminPage.evaluate(String.format(
            """
            async () => {
                const r = await fetch('/api/admin/users/%d', { method: 'DELETE' });
                let body = null;
                try { body = await r.json(); } catch (e) {}
                return { status: r.status, body: body };
            }
            """, userNo));
    }

    @Test
    @Order(1)
    void bootstrapsWorkspaceWithSoleFounderAdmin() {
        var instAdmin = browser.newPage();
        instAdmin.navigate(server.baseUrl() + "/setup");
        instAdmin.locator("form[action='/setup'] input[name=userId]").fill("guardInstanceAdmin");
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

        var invite = createInvite(founderPage);
        memberPage = browser.newPage();
        registerAndConfirmRecovery(memberPage, invite, MEMBER_ID, MEMBER_PW, false);
        approveOnlyPending(founderPage);
        login(memberPage, MEMBER_ID, MEMBER_PW);

        founderPage.navigate(server.baseUrl() + "/oehub/admin/users");
        var founder = findMember(founderPage, FOUNDER_ID);
        var member = findMember(founderPage, MEMBER_ID);
        assertThat((String) founder.get("role")).isEqualTo("ws_adm");
        assertThat((String) member.get("role")).isEqualTo("usr");
        founderUserNo = ((Number) founder.get("userNo")).longValue();
        memberUserNo = ((Number) member.get("userNo")).longValue();
    }

    @Test
    @Order(2)
    void soleAdminCannotDemoteSelf() {
        var result = patchRole(founderPage, founderUserNo, "usr");
        assertThat((Integer) result.get("status")).isEqualTo(400);
        var body = (Map<?, ?>) result.get("body");
        assertThat(body).isNotNull();
        assertThat((String) body.get("error")).isEqualTo("last_ws_admin");

        var founder = findMember(founderPage, FOUNDER_ID);
        assertThat((String) founder.get("role")).isEqualTo("ws_adm");
    }

    @Test
    @Order(3)
    void soleAdminCannotDeleteSelf() {
        var result = deleteUser(founderPage, founderUserNo);
        assertThat((Integer) result.get("status")).isEqualTo(400);

        var users = (List<?>) founderPage.evaluate(
            "async () => await (await fetch('/api/admin/users')).json()");
        assertThat(users.stream().map(o -> (Map<?, ?>) o).anyMatch(m -> FOUNDER_ID.equals(m.get("userId")))).isTrue();
    }

    @Test
    @Order(4)
    void oncePromoted_eitherAdminCanBeDemotedFreely() {
        var promote = patchRole(founderPage, memberUserNo, "ws_adm");
        assertThat((Integer) promote.get("status")).isEqualTo(200);
        assertThat((String) findMember(founderPage, MEMBER_ID).get("role")).isEqualTo("ws_adm");

        // Now two admins exist - a NON-self demotion of the founder, issued from the member's own
        // authenticated session (not the founder's - a self-attempt would hit a different guard
        // entirely, see soleAdminCannotDemoteSelf above), must succeed: 2 -> 1 admins is fine, only
        // reaching 0 is blocked.
        var demoteFounder = patchRole(memberPage, founderUserNo, "usr");
        assertThat((Integer) demoteFounder.get("status")).isEqualTo(200);
        // The founder just lost ws_adm, so the member's session (the only one left with admin
        // API access) is used to verify both roles from here on.
        assertThat((String) findMember(memberPage, FOUNDER_ID).get("role")).isEqualTo("usr");
        assertThat((String) findMember(memberPage, MEMBER_ID).get("role")).isEqualTo("ws_adm");
    }

    @Test
    @Order(5)
    void oncePromoted_eitherAdminCanBeDeletedFreely() {
        // Re-promote the founder (member is now the sole ws_adm, so this call is issued from the
        // member's session) so two admins exist again, then have the founder delete the member -
        // a non-self deletion that must succeed outright now that neither one is the sole admin.
        var rePromote = patchRole(memberPage, founderUserNo, "ws_adm");
        assertThat((Integer) rePromote.get("status")).isEqualTo(200);
        assertThat((String) findMember(founderPage, FOUNDER_ID).get("role")).isEqualTo("ws_adm");

        var delete = deleteUser(founderPage, memberUserNo);
        assertThat((Integer) delete.get("status")).isEqualTo(200);

        var users = (List<?>) founderPage.evaluate(
            "async () => await (await fetch('/api/admin/users')).json()");
        assertThat(users.stream().map(o -> (Map<?, ?>) o).anyMatch(m -> MEMBER_ID.equals(m.get("userId")))).isFalse();
        assertThat((String) findMember(founderPage, FOUNDER_ID).get("role")).isEqualTo("ws_adm");
    }
}
