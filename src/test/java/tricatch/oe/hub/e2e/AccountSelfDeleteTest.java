package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Dialog;
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
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-service account deletion ("withdrawal") on the My Info page, plus the privacy policy page
 * it is promised in. A member just leaves; the last workspace admin is refused while other members
 * remain, and takes the whole workspace with them when they are alone. Workspace mode.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountSelfDeleteTest {

    private static final int PORT = 39950;
    private static final String WS_NAME = "Withdraw Co";
    private static final String FOUNDER_ID = "sdFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String MEMBER_ID = "sdMember";
    private static final String MEMBER_PW = "MemberPass123!";
    private static final String SECOND_ID = "sdSecond";
    private static final String SECOND_PW = "SecondPass123!";
    private static final String NEWCOMER_ID = "sdNewcomer";
    private static final String NEWCOMER_PW = "NewcomerPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page founderPage;

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
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private void registerFounder(Page page, String wsName, String id, String pw) {
        page.navigate(server.baseUrl() + "/register");
        page.locator("#agreeNotice").check();   // the form stays disabled until the notice is agreed to
        page.locator("input[name=wsName]").fill(wsName);
        page.locator("input[name=userId]").fill(id);
        page.locator("input[name=password]").fill(pw);
        page.locator("input[name=confirmPassword]").fill(pw);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/login$"));
    }

    private void registerWithInvite(Page page, String code, String id, String pw) {
        page.navigate(server.baseUrl() + "/register?invite=" + code);
        page.locator("#agreeNotice").check();
        page.locator("input[name=userId]").fill(id);
        page.locator("input[name=password]").fill(pw);
        page.locator("input[name=confirmPassword]").fill(pw);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        assertThat(page).hasURL(Pattern.compile(".*/login\\?registered=pending"));
    }

    private void login(Page page, String id, String pw) {
        page.navigate(server.baseUrl() + "/login");
        page.locator("input[name=userId]").fill(id);
        page.locator("input[name=password]").fill(pw);
        page.locator("#btnLoginSubmit").click();
    }

    private void loginAndWait(Page page, String id, String pw) {
        login(page, id, pw);
        page.waitForURL(server.baseUrl() + "/");
    }

    /** One JSON API call from inside the page, so it carries that account's session; returns {status, body}. */
    @SuppressWarnings("unchecked")
    private List<Object> api(Page page, String method, String path, String jsonBody) {
        return (List<Object>) page.evaluate(
            "async ([m, p, b]) => { const r = await fetch(p, {method: m, headers: {'Content-Type': 'application/json'},"
                + " body: b}); return [r.status, await r.text()]; }",
            java.util.Arrays.asList(method, path, jsonBody));
    }

    /** Calls delete-account with the authKey derived from pw, on a page that has OE_AUTH loaded (My Info). */
    @SuppressWarnings("unchecked")
    private List<Object> deleteAccountApi(Page page, String pw) {
        return (List<Object>) page.evaluate(
            "async (pw) => { const s = await OE_AUTH.currentSecrets(pw);"
                + " const r = await fetch('/api/user/delete-account', {method: 'POST',"
                + " headers: {'Content-Type': 'application/json'}, body: JSON.stringify({currentPassword: s.authKey})});"
                + " return [r.status, await r.text()]; }", pw);
    }

    /** Invites a new person into the founder's workspace and approves them; returns nothing, they can log in after. */
    private void admit(String id, String pw) throws Exception {
        var invite = api(founderPage, "POST", "/api/wsa/invites", "{}");
        assertThat((Integer) invite.get(0)).isEqualTo(201);
        var code = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) invite.get(1)).get("inviteCode").asText();
        var page = browser.newPage();
        try {
            registerWithInvite(page, code, id, pw);
        } finally {
            page.close();
        }
        founderPage.navigate(server.baseUrl() + "/wsa/users");
        var pending = founderPage.locator("#pendingTbody tr[data-user-no]");
        assertThat(pending).hasCount(1);
        pending.locator(".btn-approve-pending").click();
        assertThat(founderPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);
    }

    /** Opens My Info, the delete dialog, types pw and confirms. */
    private void deleteViaUi(Page page, String pw) {
        page.navigate(server.baseUrl() + "/oehub/my/info");
        page.locator("#btnDeleteAccount").click();
        assertThat(page.locator("#deleteAccountModal.show")).isVisible();
        assertThat(page.locator("#btnDeleteAccountConfirm")).isDisabled();    // nothing typed yet
        page.locator("#daCurrentPassword").fill(pw);
        assertThat(page.locator("#btnDeleteAccountConfirm")).isEnabled();
        page.locator("#btnDeleteAccountConfirm").click();
    }

    @Test
    @Order(1)
    void privacyPolicyIsPublicInWorkspaceMode_andLinkedFromSignUp() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("sdInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
        page.close();

        var anon = playwright.request().newContext();
        try {
            var res = anon.get(server.baseUrl() + "/privacy");
            assertThat(res.status()).isEqualTo(200);
            var body = res.text();
            assertThat(body).contains("tricatch@naver.com").contains("mailto:tricatch@naver.com");
            assertThat(body.contains("Privacy Policy") || body.contains("개인정보 처리방침")).isTrue();
        } finally {
            anon.dispose();
        }

        var reg = browser.newPage();
        try {
            reg.navigate(server.baseUrl() + "/register");
            var link = reg.locator("a.register-notice-link");
            assertThat(link).hasAttribute("href", "/privacy");
            assertThat(link).hasAttribute("target", "_blank");
        } finally {
            reg.close();
        }
    }

    @Test
    @Order(2)
    void wrongPasswordIsRefused_andTheAccountSurvives() throws Exception {
        founderPage = browser.newPage();
        founderPage.onDialog(Dialog::accept);
        registerFounder(founderPage, WS_NAME, FOUNDER_ID, FOUNDER_PW);
        loginAndWait(founderPage, FOUNDER_ID, FOUNDER_PW);
        admit(MEMBER_ID, MEMBER_PW);

        var memberPage = browser.newPage();
        try {
            loginAndWait(memberPage, MEMBER_ID, MEMBER_PW);
            deleteViaUi(memberPage, "WrongPassword999!");
            assertThat(memberPage.locator("#daError")).isVisible();
            assertThat(memberPage).hasURL(Pattern.compile(".*/oehub/my/info$"));

            // A value that is not an authKey at all is just another wrong password.
            var direct = api(memberPage, "POST", "/api/user/delete-account", "{\"currentPassword\":\"nope\"}");
            assertThat((Integer) direct.get(0)).isEqualTo(400);
            assertThat((String) direct.get(1)).contains("current_password_invalid");
        } finally {
            memberPage.close();
        }

        // Still a member: the founder's list has both, and the member can log in.
        assertThat((String) api(founderPage, "GET", "/api/wsa/users", null).get(1)).contains(MEMBER_ID);
        var again = browser.newPage();
        try {
            loginAndWait(again, MEMBER_ID, MEMBER_PW);
        } finally {
            again.close();
        }
    }

    @Test
    @Order(3)
    void memberWithdraws_sessionEnds_loginNoLongerWorks_andTheAdminSeesItInTheAuditLog() throws Exception {
        // Member number, read while the member still exists.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String memberNo = null;
        for (var u : mapper.readTree((String) api(founderPage, "GET", "/api/wsa/users", null).get(1))) {
            if (MEMBER_ID.equals(u.path("userId").asText())) memberNo = u.path("userNo").asText();
        }
        assertThat(memberNo).isNotNull();
        assertThat((String) api(founderPage, "GET", "/api/wsa/audit-log", null).get(1)).contains("user.approve").contains(MEMBER_ID);
        var memberPage = browser.newPage();
        try {
            loginAndWait(memberPage, MEMBER_ID, MEMBER_PW);
            memberPage.navigate(server.baseUrl() + "/oehub/my/info");
            assertThat(memberPage.locator(".oe-danger-desc")).isVisible();
            deleteViaUi(memberPage, MEMBER_PW);
            assertThat(memberPage).hasURL(Pattern.compile(".*/login\\?deleted=1$"));
            assertThat(memberPage.locator(".alert-info")).isVisible();

            login(memberPage, MEMBER_ID, MEMBER_PW);
            assertThat(memberPage.locator("#loginErrorBox")).isVisible();
            assertThat(memberPage).hasURL(Pattern.compile(".*/login$"));
        } finally {
            memberPage.close();
        }

        assertThat((String) api(founderPage, "GET", "/api/wsa/users", null).get(1)).doesNotContain(MEMBER_ID);
        var audit = (String) api(founderPage, "GET", "/api/wsa/audit-log", null).get(1);
        // Withdrawal entry present, the ID string gone everywhere (including the older approval
        // entry), the member number kept as the target.
        assertThat(audit).contains("user.withdraw").contains("user.approve");
        assertThat(audit).doesNotContain(MEMBER_ID);
        assertThat(audit).contains("\"targetId\":\"" + memberNo + "\"");
    }

    @Test
    @Order(4)
    void lastAdminWithOtherMembersIsBlocked() throws Exception {
        admit(SECOND_ID, SECOND_PW);

        founderPage.navigate(server.baseUrl() + "/oehub/my/info");
        assertThat(founderPage.locator("#btnDeleteAccount")).isDisabled();
        assertThat(founderPage.locator(".oe-danger-desc")).containsText("only admin");

        var res = deleteAccountApi(founderPage, FOUNDER_PW);
        assertThat((Integer) res.get(0)).isEqualTo(400);
        assertThat((String) res.get(1)).contains("last_ws_admin");
        assertThat((String) api(founderPage, "GET", "/api/wsa/users", null).get(1)).contains(SECOND_ID);
    }

    @Test
    @Order(5)
    void soleFounderWithdrawing_deletesTheWholeWorkspace() {
        // The other member leaves first, which frees the founder to take the workspace with them.
        var secondPage = browser.newPage();
        try {
            loginAndWait(secondPage, SECOND_ID, SECOND_PW);
            deleteViaUi(secondPage, SECOND_PW);
            assertThat(secondPage).hasURL(Pattern.compile(".*/login\\?deleted=1$"));
        } finally {
            secondPage.close();
        }

        founderPage.navigate(server.baseUrl() + "/oehub/my/info");
        assertThat(founderPage.locator("#btnDeleteAccount")).isEnabled();
        assertThat(founderPage.locator(".oe-danger-desc")).containsText("whole workspace");
        deleteViaUi(founderPage, FOUNDER_PW);
        assertThat(founderPage).hasURL(Pattern.compile(".*/login\\?deleted=1$"));

        // ws_name is UNIQUE, so the same name can only be founded again once the workspace is really gone.
        var page = browser.newPage();
        try {
            registerFounder(page, WS_NAME, NEWCOMER_ID, NEWCOMER_PW);
            loginAndWait(page, NEWCOMER_ID, NEWCOMER_PW);
        } finally {
            page.close();
        }

        // The old founder's account is gone with it.
        var old = browser.newPage();
        try {
            login(old, FOUNDER_ID, FOUNDER_PW);
            assertThat(old.locator("#loginErrorBox")).isVisible();
        } finally {
            old.close();
        }
    }

    @Test
    @Order(6)
    void withoutASessionTheEndpointIsUnauthorized() {
        // A fresh browser with no session; the register page loads util.js, which adds the CSRF header
        // so the request gets past the CSRF check and reaches the login check.
        var page = browser.newPage();
        try {
            page.navigate(server.baseUrl() + "/register");
            var res = api(page, "POST", "/api/user/delete-account", "{\"currentPassword\":\"x\"}");
            assertThat((Integer) res.get(0)).isEqualTo(401);
        } finally {
            page.close();
        }
    }
}
