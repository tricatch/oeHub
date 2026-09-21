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
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A regular member (role usr) of a workspace can bring in new people, not only the workspace admin:
 * on "/oehub/members" they issue invite codes - into any team - and approve sign-ups. They see only
 * the codes they issued themselves, cannot reject a sign-up, and everything else about people
 * (roles, removal, teams) stays behind "/api/wsa/*". They can also see who else is in their workspace
 * (a read-only list of user id, admin or not, team and join date). Workspace mode.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemberInviteApprovalTest {

    private static final int PORT = 39949;
    private static final String FOUNDER_ID = "miFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String MEMBER_ID = "miMember";
    private static final String MEMBER_PW = "MemberPass123!";
    private static final String JOINER_ID = "miJoiner";
    private static final String JOINER_PW = "JoinerPass123!";
    private static final String OTHER_ID = "miOtherFounder";
    private static final String OTHER_PW = "OtherPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page founderPage;
    private Page memberPage;
    private Page otherPage;
    private String founderInviteCode;
    private String memberInviteCode;
    private long teamNo;

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
        if (otherPage != null) otherPage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private void registerFounder(Page page, String wsName, String id, String pw) {
        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill(wsName);
        page.locator("input[name=userId]").fill(id);
        page.locator("input[name=password]").fill(pw);
        page.locator("input[name=confirmPassword]").fill(pw);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/login$"));
        login(page, id, pw);
    }

    private void registerWithInvite(Page page, String code, String id, String pw) {
        page.navigate(server.baseUrl() + "/register?invite=" + code);
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

    @Test
    @Order(1)
    void bootstrapsInstanceAdmin() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("miInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
        page.close();
    }

    @Test
    @Order(2)
    void founderSetsUpATeamAndAdmitsTheFirstMember() throws Exception {
        founderPage = browser.newPage();
        founderPage.onDialog(Dialog::accept);
        registerFounder(founderPage, "Members Co", FOUNDER_ID, FOUNDER_PW);

        var team = api(founderPage, "POST", "/api/wsa/teams", "{\"teamName\":\"Sales\"}");
        assertThat((Integer) team.get(0)).isEqualTo(201);
        teamNo = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) team.get(1)).get("teamNo").asLong();

        var invite = api(founderPage, "POST", "/api/wsa/invites", "{}");
        assertThat((Integer) invite.get(0)).isEqualTo(201);
        founderInviteCode = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree((String) invite.get(1)).get("inviteCode").asText();

        memberPage = browser.newPage();
        memberPage.onDialog(Dialog::accept);
        registerWithInvite(memberPage, founderInviteCode, MEMBER_ID, MEMBER_PW);

        founderPage.navigate(server.baseUrl() + "/wsa/users");
        var pending = founderPage.locator("#pendingTbody tr[data-user-no]");
        assertThat(pending).hasCount(1);
        pending.locator(".btn-approve-pending").click();
        assertThat(founderPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);

        login(memberPage, MEMBER_ID, MEMBER_PW);
    }

    @Test
    @Order(3)
    void memberSeesTheInvitesAndApprovalsPage_butNotTheAdminOnes() {
        memberPage.navigate(server.baseUrl() + "/oehub/members");
        assertThat(memberPage.locator("#invitesTbody")).isVisible();
        assertThat(memberPage.locator("#pendingSection")).isVisible();
        assertThat(memberPage.locator("a[href='/oehub/members']")).hasCount(1);   // menu entry
        assertThat(memberPage.locator("a[href='/wsa/users']")).hasCount(0);       // no admin menu

        // The teams to pick from are offered, and the member sees none of the admin's own codes.
        assertThat(memberPage.locator("#inviteTeamSelect option")).hasCount(2);   // "no team" + Sales
        assertThat(memberPage.locator("#invitesTbody tr[data-invite-code]")).hasCount(0);
        assertThat((Integer) api(memberPage, "GET", "/wsa/users", null).get(0)).isEqualTo(403);
        assertThat((Integer) api(memberPage, "GET", "/api/wsa/users", null).get(0)).isEqualTo(403);
        assertThat((Integer) api(memberPage, "GET", "/api/wsa/invites", null).get(0)).isEqualTo(403);
    }

    @Test
    @Order(4)
    void memberIssuesAnInviteIntoATeam_andOnlyTheirOwnCodesAreListed() {
        memberPage.locator("#inviteTeamSelect").selectOption(new com.microsoft.playwright.options.SelectOption().setLabel("Sales"));
        memberPage.locator("#btnCreateInvite").click();
        var row = memberPage.locator("#invitesTbody tr[data-invite-code]");
        assertThat(row).hasCount(1);
        assertThat(row).containsText("Sales");
        memberInviteCode = row.getAttribute("data-invite-code");
        assertThat(memberInviteCode).isNotBlank().isNotEqualTo(founderInviteCode);

        // The founder sees the whole workspace's outstanding codes - the member's one included.
        assertThat((String) api(founderPage, "GET", "/api/wsa/invites", null).get(1)).contains(memberInviteCode);
        // The member's own list never contains a code somebody else issued.
        assertThat((String) api(memberPage, "GET", "/api/members/invites", null).get(1)).doesNotContain(founderInviteCode);
        // A member cannot pick a team from another workspace.
        assertThat((Integer) api(memberPage, "POST", "/api/members/invites", "{\"teamNo\":999999}").get(0)).isEqualTo(400);
    }

    @Test
    @Order(5)
    void joinerUsesTheMembersCode_andTheMemberApprovesButCannotReject() {
        var joinerPage = browser.newPage();
        try {
            registerWithInvite(joinerPage, memberInviteCode, JOINER_ID, JOINER_PW);
        } finally {
            joinerPage.close();
        }

        memberPage.navigate(server.baseUrl() + "/oehub/members");
        var pending = memberPage.locator("#pendingTbody tr[data-user-no]");
        assertThat(pending).hasCount(1);
        assertThat(pending).containsText(JOINER_ID);
        assertThat(pending.locator(".btn-reject-pending")).hasCount(0);            // approve only
        var joinerNo = pending.getAttribute("data-user-no");
        assertThat((Integer) api(memberPage, "POST", "/api/wsa/users/" + joinerNo + "/reject", null).get(0)).isEqualTo(403);

        pending.locator(".btn-approve-pending").click();
        assertThat(memberPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);
        assertThat(memberPage.locator("#pendingSection")).isVisible();             // empty state, not hidden

        // The joiner is now a usr of the workspace, assigned to the invite's team; the audit log names the
        // approving member, not the founder.
        @SuppressWarnings("unchecked")
        var users = (List<Map<String, Object>>) founderPage.evaluate("() => fetch('/api/wsa/users').then(r => r.json())");
        var joiner = users.stream().filter(u -> JOINER_ID.equals(u.get("userId"))).findFirst().orElseThrow();
        assertThat(joiner.get("role")).isEqualTo("usr");
        assertThat(joiner.get("teamName")).isEqualTo("Sales");
        var audit = (String) api(founderPage, "GET", "/api/wsa/audit-log", null).get(1);
        assertThat(audit).contains("user.approve").contains("\"actor\":\"" + MEMBER_ID + "\"");
    }

    @Test
    @Order(6)
    void memberSeesWhoElseIsInTheirWorkspace_readOnly() {
        memberPage.navigate(server.baseUrl() + "/oehub/members");
        var rows = memberPage.locator("#membersTbody tr[data-user-id]");
        assertThat(rows).hasCount(3);                                             // founder, member, joiner
        assertThat(memberPage.locator("#membersTbody tr[data-user-id='" + FOUNDER_ID + "']")).isVisible();
        assertThat(memberPage.locator("#membersTbody tr[data-user-id='" + JOINER_ID + "']")).containsText("Sales");
        assertThat(memberPage.locator("#membersTbody button")).hasCount(0);         // nothing to click

        // The search box narrows the list.
        memberPage.locator("#memberSearch").fill("joiner");
        assertThat(memberPage.locator("#membersTbody tr[data-user-id]")).hasCount(1);

        // Only who they are, not how they behave: no last login, no keys, no user numbers.
        var body = (String) api(memberPage, "GET", "/api/members/users", null).get(1);
        assertThat(body).contains(FOUNDER_ID).doesNotContain("lastLoginAt").doesNotContain("publicKey").doesNotContain("userNo");
    }

    @Test
    @Order(7)
    void otherWorkspacesAndOutsidersStayOut() {
        otherPage = browser.newPage();
        registerFounder(otherPage, "Other Members Co", OTHER_ID, OTHER_PW);
        // Another workspace has its own (empty) lists, and cannot approve this workspace's people.
        assertThat((String) api(otherPage, "GET", "/api/members/pending", null).get(1)).isEqualTo("[]");
        var others = (String) api(otherPage, "GET", "/api/members/users", null).get(1);
        assertThat(others).contains(OTHER_ID).doesNotContain(FOUNDER_ID).doesNotContain(MEMBER_ID);
        assertThat((String) api(otherPage, "GET", "/api/members/invites", null).get(1)).doesNotContain(memberInviteCode);
        assertThat((Integer) api(otherPage, "POST", "/api/members/pending/1/approve",
            "{\"wrappedWsKey\":\"x\"}").get(0)).isEqualTo(404);

        var anon = playwright.request().newContext();
        try {
            assertThat(anon.get(server.baseUrl() + "/api/members/invites").status()).isEqualTo(401);
            assertThat(anon.get(server.baseUrl() + "/api/members/users").status()).isEqualTo(401);
            var redirect = anon.get(server.baseUrl() + "/oehub/members",
                com.microsoft.playwright.options.RequestOptions.create().setMaxRedirects(0));
            assertThat(redirect.status()).isEqualTo(302);
        } finally {
            anon.dispose();
        }
    }
}
