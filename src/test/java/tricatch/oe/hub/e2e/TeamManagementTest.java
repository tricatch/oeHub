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
 * Covers the "teams" (department) feature end to end under oe.mode=workspace (cloudGroupService design
 * doc §2.9): a ws_adm creates/renames a team from the member-management screen, an invite code can
 * pre-assign a team so a joiner is auto-assigned at signup, a ws_adm can reassign a member's team
 * manually (including clearing it back to "no team"), team deletion is refused while a member still
 * references it and succeeds once that member is reassigned away, and a team belongs to exactly one
 * workspace - a different workspace's ws_adm can neither touch nor delete it.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TeamManagementTest {

    private static final int PORT = 39930;
    private static final String FOUNDER_ID = "tmFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String JOINER_ID = "tmJoiner";
    private static final String JOINER_PW = "JoinerPass123!";
    private static final String OTHER_FOUNDER_ID = "tmOtherFounder";
    private static final String OTHER_FOUNDER_PW = "OtherPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page founderPage;
    private Page joinerPage;
    private Page otherFounderPage;
    private String inviteCode;
    private long engineeringTeamNo;
    private long marketingTeamNo;
    private long joinerUserNo;

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
        if (joinerPage != null) joinerPage.close();
        if (otherFounderPage != null) otherFounderPage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void bootstrapsInstanceAdmin_noCaNeededInWorkspaceMode() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("tmInstanceAdmin");
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
    void founderRegistersNewWorkspace_andLogsIn() {
        founderPage = browser.newPage();
        // Several flows below (approve pending, delete team) go through a native confirm().
        founderPage.onDialog(Dialog::accept);
        founderPage.navigate(server.baseUrl() + "/register");
        founderPage.locator("input[name=wsName]").fill("Team Co");
        founderPage.locator("input[name=userId]").fill(FOUNDER_ID);
        founderPage.locator("input[name=password]").fill(FOUNDER_PW);
        founderPage.locator("input[name=confirmPassword]").fill(FOUNDER_PW);
        founderPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(founderPage.locator("#recoveryCodeModal.show")).isVisible();
        founderPage.locator("#btnRecoveryCodeContinue").click();
        founderPage.waitForURL(Pattern.compile(".*/login$"));

        founderPage.locator("input[name=userId]").fill(FOUNDER_ID);
        founderPage.locator("input[name=password]").fill(FOUNDER_PW);
        founderPage.locator("#btnLoginSubmit").click();
        founderPage.waitForURL(server.baseUrl() + "/");
    }

    @Test
    @Order(3)
    void founderCreatesTwoTeams_theyAppearInTheTeamListAndSelectors() {
        founderPage.navigate(server.baseUrl() + "/oehub/admin/users");
        assertThat(founderPage.locator("#teamsTbody tr[data-team-no]")).hasCount(0);

        createTeamViaUi("Engineering");
        assertThat(founderPage.locator("#teamsTbody tr[data-team-no]")).hasCount(1);
        assertThat(founderPage.locator("#teamsTbody .team-name-text")).hasText("Engineering");

        createTeamViaUi("Marketing");
        assertThat(founderPage.locator("#teamsTbody tr[data-team-no]")).hasCount(2);

        // Both teams must also have reached the invite modal's team dropdown (design doc §2.8/§2.9).
        var inviteOptions = founderPage.locator("#inviteTeamSelect option");
        assertThat(inviteOptions).hasCount(3); // "no team" + the two teams
    }

    private void createTeamViaUi(String name) {
        founderPage.locator("#newTeamName").fill(name);
        founderPage.locator("#btnCreateTeam").click();
        assertThat(founderPage.locator("#teamsTbody .team-name-text", new Page.LocatorOptions().setHasText(name))).hasCount(1);
    }

    @Test
    @Order(4)
    void founderRenamesEngineeringTeam() {
        var nameSpan = founderPage.locator("#teamsTbody tr", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Engineering"))
            .locator(".team-name-text");
        nameSpan.dblclick();
        founderPage.keyboard().press("Control+A");
        founderPage.keyboard().type("Eng Team");
        founderPage.keyboard().press("Tab");
        assertThat(founderPage.locator("#teamsTbody tr", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Eng Team"))).hasCount(1);
        // The renamed row's text no longer matches the old name at all (not just "renamed to
        // something containing it") - "Eng Team" does not contain the substring "Engineering".
        assertThat(founderPage.locator("#teamsTbody tr", new com.microsoft.playwright.Page.LocatorOptions().setHasText("Engineering"))).hasCount(0);

        // Fetch the teamNo values now, for the API-level assertions further down.
        @SuppressWarnings("unchecked")
        var teams = (List<Map<String, Object>>) founderPage.evaluate("() => fetch('/api/admin/teams').then(r => r.json())");
        for (var t : teams) {
            if ("Eng Team".equals(t.get("teamName"))) engineeringTeamNo = ((Number) t.get("teamNo")).longValue();
            if ("Marketing".equals(t.get("teamName"))) marketingTeamNo = ((Number) t.get("teamNo")).longValue();
        }
        assertThat(engineeringTeamNo).isNotZero();
        assertThat(marketingTeamNo).isNotZero();
    }

    @Test
    @Order(5)
    void founderGeneratesInviteWithTeamPreSelected() {
        founderPage.locator("#inviteTeamSelect").selectOption(new com.microsoft.playwright.options.SelectOption().setLabel("Eng Team"));
        founderPage.locator("#btnCreateInvite").click();
        var row = founderPage.locator("#invitesTbody tr[data-invite-code]").first();
        assertThat(row).hasCount(1);
        assertThat(row).containsText("Eng Team");
        inviteCode = row.getAttribute("data-invite-code");
        assertThat(inviteCode).isNotBlank();
    }

    @Test
    @Order(6)
    void joinerRegistersWithInviteCode_startsPending() {
        joinerPage = browser.newPage();
        joinerPage.navigate(server.baseUrl() + "/register?invite=" + inviteCode);
        assertThat(joinerPage.locator("input[name=inviteCode]")).hasValue(inviteCode);
        joinerPage.locator("input[name=userId]").fill(JOINER_ID);
        joinerPage.locator("input[name=password]").fill(JOINER_PW);
        joinerPage.locator("input[name=confirmPassword]").fill(JOINER_PW);
        joinerPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(joinerPage.locator("#recoveryCodeModal.show")).isVisible();
        joinerPage.locator("#btnRecoveryCodeContinue").click();
        assertThat(joinerPage).hasURL(Pattern.compile(".*/login\\?registered=pending"));
    }

    @Test
    @Order(7)
    void founderApprovesJoiner_whoEndsUpAssignedToTheInvitesTeam() {
        founderPage.navigate(server.baseUrl() + "/oehub/admin/users");
        var pendingRow = founderPage.locator("#pendingTbody tr[data-user-no]");
        assertThat(pendingRow).hasCount(1);
        assertThat(pendingRow).containsText(JOINER_ID);
        pendingRow.locator(".btn-approve-pending").click();
        assertThat(founderPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);

        // The invite's team_no (cloudGroupService design doc §2.8/§2.9) must have carried through
        // to the new HUB_USR row automatically - no manual assignment step should be needed.
        @SuppressWarnings("unchecked")
        var users = (List<Map<String, Object>>) founderPage.evaluate("() => fetch('/api/admin/users').then(r => r.json())");
        var joiner = users.stream().filter(u -> JOINER_ID.equals(u.get("userId"))).findFirst().orElseThrow();
        assertThat(joiner.get("teamName")).isEqualTo("Eng Team");
        joinerUserNo = ((Number) joiner.get("userNo")).longValue();

        // The member table's own dropdown must reflect the same assignment.
        var joinerRow = founderPage.locator("#usersTbody tr[data-user-no='" + joinerUserNo + "']");
        assertThat(joinerRow.locator(".user-team-select")).hasValue(String.valueOf(engineeringTeamNo));
    }

    @Test
    @Order(8)
    void founderManuallyReassignsJoinersTeam_includingClearingBackToNoTeam() {
        var joinerRow = founderPage.locator("#usersTbody tr[data-user-no='" + joinerUserNo + "']");
        selectTeamAndWaitForUpdate(joinerRow.locator(".user-team-select"), String.valueOf(marketingTeamNo));
        assertThat(joinerRow.locator(".user-team-select")).hasValue(String.valueOf(marketingTeamNo));
        assertTeamNoViaApi(joinerUserNo, marketingTeamNo);

        // Clear back to "no team" - the first (empty-value) option.
        selectTeamAndWaitForUpdate(joinerRow.locator(".user-team-select"), "");
        assertThat(joinerRow.locator(".user-team-select")).hasValue("");
        assertTeamNoViaApi(joinerUserNo, null);
    }

    // The dropdown's own value updates synchronously on selectOption, but the server-side PATCH it
    // triggers (onChangeUserTeam in users.pebble) is async - waiting for that response (rather than
    // just the DOM value) avoids racing the API-level assertions that follow.
    private void selectTeamAndWaitForUpdate(com.microsoft.playwright.Locator select, String optionValue) {
        founderPage.waitForResponse(
            resp -> resp.url().contains("/team") && "PATCH".equals(resp.request().method()),
            () -> select.selectOption(optionValue));
    }

    private void assertTeamNoViaApi(long userNo, Long expectedTeamNo) {
        @SuppressWarnings("unchecked")
        var users = (List<Map<String, Object>>) founderPage.evaluate("() => fetch('/api/admin/users').then(r => r.json())");
        var user = users.stream().filter(u -> ((Number) u.get("userNo")).longValue() == userNo).findFirst().orElseThrow();
        Object teamNo = user.get("teamNo");
        if (expectedTeamNo == null) {
            assertThat(teamNo).isNull();
        } else {
            assertThat(((Number) teamNo).longValue()).isEqualTo(expectedTeamNo);
        }
    }

    @Test
    @Order(9)
    void deletingATeam_isRefusedWhileAMemberReferencesIt_andSucceedsOnceReassigned() {
        // Put the joiner back into Eng Team so the deletion attempt below has a real reference to
        // refuse against (cloudGroupService design doc §2.9 - the deletion choice this
        // implementation makes: refuse rather than silently null out team_no elsewhere).
        var joinerRow = founderPage.locator("#usersTbody tr[data-user-no='" + joinerUserNo + "']");
        selectTeamAndWaitForUpdate(joinerRow.locator(".user-team-select"), String.valueOf(engineeringTeamNo));
        assertTeamNoViaApi(joinerUserNo, engineeringTeamNo);

        int statusWhileInUse = ((Number) founderPage.evaluate(
            "(teamNo) => fetch('/api/admin/teams/' + teamNo, { method: 'DELETE' }).then(r => r.status)",
            String.valueOf(engineeringTeamNo))).intValue();
        assertThat(statusWhileInUse).isEqualTo(409);
        // Still present in the team list and the member's dropdown after the refused delete.
        assertThat(founderPage.locator("#teamsTbody tr[data-team-no='" + engineeringTeamNo + "']")).hasCount(1);

        // Reassign the only referencing member away, then deletion must succeed.
        selectTeamAndWaitForUpdate(joinerRow.locator(".user-team-select"), "");
        assertTeamNoViaApi(joinerUserNo, null);

        int statusAfterReassign = ((Number) founderPage.evaluate(
            "(teamNo) => fetch('/api/admin/teams/' + teamNo, { method: 'DELETE' }).then(r => r.status)",
            String.valueOf(engineeringTeamNo))).intValue();
        assertThat(statusAfterReassign).isEqualTo(204);
    }

    @Test
    @Order(10)
    void aTeamFromOneWorkspace_cannotBeTouchedByAnotherWorkspacesAdmin() {
        otherFounderPage = browser.newPage();
        otherFounderPage.onDialog(Dialog::accept);
        otherFounderPage.navigate(server.baseUrl() + "/register");
        otherFounderPage.locator("input[name=wsName]").fill("Other Team Co");
        otherFounderPage.locator("input[name=userId]").fill(OTHER_FOUNDER_ID);
        otherFounderPage.locator("input[name=password]").fill(OTHER_FOUNDER_PW);
        otherFounderPage.locator("input[name=confirmPassword]").fill(OTHER_FOUNDER_PW);
        otherFounderPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(otherFounderPage.locator("#recoveryCodeModal.show")).isVisible();
        otherFounderPage.locator("#btnRecoveryCodeContinue").click();
        otherFounderPage.waitForURL(Pattern.compile(".*/login$"));
        otherFounderPage.locator("input[name=userId]").fill(OTHER_FOUNDER_ID);
        otherFounderPage.locator("input[name=password]").fill(OTHER_FOUNDER_PW);
        otherFounderPage.locator("#btnLoginSubmit").click();
        otherFounderPage.waitForURL(server.baseUrl() + "/");

        // Marketing belongs to "Team Co" (the first founder's workspace) - the second workspace's
        // ws_adm must not be able to rename, delete, or assign a member to it.
        int renameStatus = ((Number) otherFounderPage.evaluate(
            "(teamNo) => fetch('/api/admin/teams/' + teamNo, { method: 'PATCH', headers: {'Content-Type':'application/json'}, body: JSON.stringify({teamName: 'Hijacked'}) }).then(r => r.status)",
            String.valueOf(marketingTeamNo))).intValue();
        assertThat(renameStatus).isEqualTo(404);

        int deleteStatus = ((Number) otherFounderPage.evaluate(
            "(teamNo) => fetch('/api/admin/teams/' + teamNo, { method: 'DELETE' }).then(r => r.status)",
            String.valueOf(marketingTeamNo))).intValue();
        assertThat(deleteStatus).isEqualTo(404);

        // Marketing must still exist, untouched, from the original workspace's point of view.
        @SuppressWarnings("unchecked")
        var teams = (List<Map<String, Object>>) founderPage.evaluate("() => fetch('/api/admin/teams').then(r => r.json())");
        assertThat(teams).anySatisfy(t -> {
            assertThat(((Number) t.get("teamNo")).longValue()).isEqualTo(marketingTeamNo);
            assertThat(t.get("teamName")).isEqualTo("Marketing");
        });
    }
}
