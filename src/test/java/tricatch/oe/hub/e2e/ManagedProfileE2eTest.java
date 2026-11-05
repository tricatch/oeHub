package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 'managed' hosts profiles in workspace mode, through the real page: the workspace admin publishes
 * an encrypted profile, a member who joins afterwards gets it unselected and read-only (and can
 * still decrypt it with the workspace key), the member can't delete it, and the admin's delete
 * removes it from the member's list too.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManagedProfileE2eTest {

    private static final int PORT = 39975;
    private static final String ADMIN_ID = "mgdAdmin";
    private static final String ADMIN_PW = "AdminPass123!";
    private static final String MEMBER_ID = "mgdMember";
    private static final String MEMBER_PW = "MemberPass123!";
    private static final String PROBE_LINE = "127.0.0.1 managed-probe.oe";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page adminPage;
    private Page memberPage;
    private String adminHostsId;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopAll() {
        if (adminPage != null) adminPage.close();
        if (memberPage != null) memberPage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private void login(Page page, String userId, String pw) {
        page.navigate(server.baseUrl() + "/login");
        page.locator("input[name=userId]").fill(userId);
        page.locator("input[name=password]").fill(pw);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");
    }

    private Page newPage() {
        var page = browser.newPage();
        page.onDialog(com.microsoft.playwright.Dialog::accept);
        page.addInitScript("try { localStorage.setItem('hostsGuideHidden', 'true'); } catch (e) {}");
        return page;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hostsList(Page page) {
        return (List<Map<String, Object>>) page.evaluate("async () => await (await fetch('/api/hosts')).json()");
    }

    @Test
    @Order(1)
    void bootstrapsInstanceAdminAndWorkspace() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("mgdInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
        page.close();

        adminPage = newPage();
        adminPage.navigate(server.baseUrl() + "/register");
        adminPage.locator("#agreeNotice").check();
        adminPage.locator("input[name=wsName]").fill("Managed Co");
        adminPage.locator("input[name=userId]").fill(ADMIN_ID);
        adminPage.locator("input[name=password]").fill(ADMIN_PW);
        adminPage.locator("input[name=confirmPassword]").fill(ADMIN_PW);
        adminPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(adminPage.locator("#recoveryCodeModal.show")).isVisible();
        adminPage.locator("#btnRecoveryCodeContinue").click();
        adminPage.waitForURL(Pattern.compile(".*/login$"));
        login(adminPage, ADMIN_ID, ADMIN_PW);
    }

    @Test
    @Order(2)
    void adminPublishesAnEncryptedProfileToEveryone() {
        adminPage.navigate(server.baseUrl() + "/oehub/hosts");
        adminPage.locator("#btnAdd").click();
        assertThat(adminPage.locator("#hostsEditorBody")).isVisible();
        adminHostsId = (String) adminPage.evaluate(
            "() => document.querySelector('#profileList .oe-list-item').dataset.hostsId");
        adminPage.locator("#cmHostsEditor").click();
        adminPage.keyboard().press("Control+A");
        adminPage.keyboard().type(PROBE_LINE);
        assertThat(adminPage.locator("#saveStatus")).containsText("Saved",
            new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        adminPage.locator("#shareScopeBtn").click();
        adminPage.locator("#shareScopeMenu [data-value=managed]").click();
        assertThat(adminPage.locator("#collaboConfirmModal.show")).isVisible();
        adminPage.locator("#btnCollaboConfirm").click();
        adminPage.waitForTimeout(500);

        var row = hostsList(adminPage).stream().filter(p -> adminHostsId.equals(p.get("hostsId"))).findFirst().orElseThrow();
        assertThat(row.get("shareScope")).isEqualTo("managed");
        assertThat((String) row.get("wrappedContentKey")).isNotBlank();
        assertThat((String) row.get("hostsContent")).doesNotContain("managed-probe");
        // The admin may still edit and delete it.
        assertThat(adminPage.locator("#btnDelete")).isEnabled();
        assertThat((Boolean) adminPage.evaluate("() => ace.edit('cmHostsEditor').getReadOnly()")).isFalse();
    }

    @Test
    @Order(3)
    void laterMember_getsItUnselectedReadOnlyAndUndeletable() {
        adminPage.navigate(server.baseUrl() + "/wsa/users");
        var inviteCode = (String) adminPage.evaluate(
            "async () => (await (await fetch('/api/wsa/invites', { method: 'POST' })).json()).inviteCode");

        memberPage = newPage();
        memberPage.navigate(server.baseUrl() + "/register?invite=" + inviteCode);
        memberPage.locator("#agreeNotice").check();
        memberPage.locator("input[name=userId]").fill(MEMBER_ID);
        memberPage.locator("input[name=password]").fill(MEMBER_PW);
        memberPage.locator("input[name=confirmPassword]").fill(MEMBER_PW);
        memberPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(memberPage.locator("#recoveryCodeModal.show")).isVisible();
        memberPage.locator("#btnRecoveryCodeContinue").click();

        adminPage.navigate(server.baseUrl() + "/wsa/users");
        adminPage.locator("#pendingTbody tr[data-user-no] .btn-approve-pending").click();
        assertThat(adminPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);

        login(memberPage, MEMBER_ID, MEMBER_PW);
        memberPage.navigate(server.baseUrl() + "/oehub/hosts");
        var item = memberPage.locator("#profileList .oe-list-item");
        assertThat(item).hasCount(1);
        item.locator(".oe-list-name").click();

        var ref = hostsList(memberPage).get(0);
        assertThat(ref.get("shareScope")).isEqualTo("managed");
        assertThat(ref.get("selected")).isEqualTo(false);
        assertThat(ref.get("userId")).isEqualTo(ADMIN_ID);
        var decrypted = (String) memberPage.evaluate(
            "(row) => OE_CONTENT_CRYPTO.decrypt(row.wrappedContentKey, row.hostsContent, row.shareScope)", ref);
        assertThat(decrypted).contains(PROBE_LINE);

        assertThat(memberPage.locator("#btnDelete")).isDisabled();
        assertThat((Boolean) memberPage.evaluate("() => ace.edit('cmHostsEditor').getReadOnly()")).isTrue();
        assertThat(memberPage.locator("#shareScopeMenu [data-value=managed]").locator("xpath=..")).hasClass(Pattern.compile("d-none"));

        // The server refuses too, whatever the page allows.
        var refId = (String) ref.get("hostsId");
        var status = (Integer) memberPage.evaluate(
            "async (id) => (await fetch('/api/hosts/' + id, {method: 'DELETE'})).status", refId);
        assertThat(status).isEqualTo(403);
        status = (Integer) memberPage.evaluate(
            "async (id) => (await fetch('/api/hosts/' + id + '/content', {method: 'PATCH', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({content: 'x'})})).status", refId);
        assertThat(status).isEqualTo(404);
        status = (Integer) memberPage.evaluate(
            "async (id) => (await fetch('/api/wsa/hosts/' + id + '/managed', {method: 'DELETE'})).status", refId);
        assertThat(status).isEqualTo(403);
    }

    @Test
    @Order(4)
    void adminDelete_removesItFromTheMemberToo() {
        adminPage.navigate(server.baseUrl() + "/oehub/hosts");
        adminPage.locator("#profileList .oe-list-item .oe-list-name").first().click();
        adminPage.locator("#btnDelete").click();   // the "removed from everyone" confirm is accepted
        assertThat(adminPage.locator("#profileList .oe-list-item")).hasCount(0);

        assertThat(hostsList(memberPage)).isEmpty();
    }
}
