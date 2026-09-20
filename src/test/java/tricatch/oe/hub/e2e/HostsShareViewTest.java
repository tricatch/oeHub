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

import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the /share and /api/hosts/{id}/view wiring added on top of hosts.pebble's content
 * encryption (e2eEncryption design doc §9's reinterpretation of cloudGroupService doc §2.4):
 * under oe.mode=workspace a share link is only viewable by someone already logged in to the SAME
 * workspace (an anonymous visitor is redirected to /login, a different-workspace member gets
 * 403), the viewer's browser decrypts with its own cached workspace key, another member can view
 * (read-only) a public profile from search results via the new /view endpoint the same way, and
 * the old synchronous /share/.../text route refuses (rather than trying and failing) to serve
 * encrypted content it structurally cannot decrypt server-side.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HostsShareViewTest {

    private static final int PORT = 39920;
    private static final String FOUNDER_ID = "hsvFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String MEMBER_ID = "hsvMember";
    private static final String MEMBER_PW = "MemberPass123!";
    private static final String OUTSIDER_ID = "hsvOutsider";
    private static final String OUTSIDER_PW = "OutsiderPass123!";
    private static final String PROBE_LINE = "127.0.0.1 share-view-probe.oe";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page founderPage;
    private Page memberPage;
    private Page outsiderPage;
    private String hostsId;
    private String shareUrl;
    private String firstCopyHostsId;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, java.util.List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopAll() {
        if (founderPage != null) founderPage.close();
        if (memberPage != null) memberPage.close();
        if (outsiderPage != null) outsiderPage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void bootstrapsInstanceAdmin() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("hsvInstanceAdmin");
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
    void founderRegistersWorkspace_createsPublicProfile_andEditsIt() {
        founderPage = browser.newPage();
        founderPage.onDialog(com.microsoft.playwright.Dialog::accept);
        founderPage.addInitScript("try { localStorage.setItem('hostsGuideHidden', 'true'); } catch (e) {}");

        founderPage.navigate(server.baseUrl() + "/register");
        founderPage.locator("input[name=wsName]").fill("HSV Co");
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

        founderPage.navigate(server.baseUrl() + "/oehub/hosts");
        founderPage.locator("#btnAdd").click();
        assertThat(founderPage.locator("#hostsEditorBody")).isVisible();
        hostsId = (String) founderPage.evaluate(
            "() => document.querySelector('#profileList .oe-list-item').dataset.hostsId");
        assertThat(hostsId).isNotBlank();
        shareUrl = server.baseUrl() + "/share/" + hostsId + "/oelink";

        founderPage.locator("#cmHostsEditor").click();
        founderPage.keyboard().press("Control+A");
        founderPage.keyboard().type(PROBE_LINE);
        assertThat(founderPage.locator("#saveStatus")).containsText("Saved",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        // Sanity: the row really is encrypted server-side (same guarantee HostsContentEncryptionTest
        // checks) - otherwise the rest of this test wouldn't actually be exercising decryption.
        var raw = (Map<?, ?>) founderPage.evaluate(
            "async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }",
            hostsId);
        assertThat((String) raw.get("wrappedContentKey")).isNotBlank();
        assertThat((String) raw.get("hostsContent")).doesNotContain("share-view-probe");
    }

    @Test
    @Order(3)
    void anonymousVisitor_isRedirectedToLogin() {
        var anonPage = browser.newPage();
        anonPage.navigate(shareUrl);
        anonPage.waitForURL(Pattern.compile(".*/login.*"));
        anonPage.close();
    }

    @Test
    @Order(4)
    void ownerViewingTheirOwnShareLink_seesDecryptedContent() {
        founderPage.navigate(shareUrl);
        assertThat(founderPage.locator("#rawContent")).containsText(PROBE_LINE,
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
    }

    @Test
    @Order(5)
    void differentWorkspaceMember_getsForbidden() {
        outsiderPage = browser.newPage();
        outsiderPage.navigate(server.baseUrl() + "/register");
        outsiderPage.locator("input[name=wsName]").fill("HSV Other Co");
        outsiderPage.locator("input[name=userId]").fill(OUTSIDER_ID);
        outsiderPage.locator("input[name=password]").fill(OUTSIDER_PW);
        outsiderPage.locator("input[name=confirmPassword]").fill(OUTSIDER_PW);
        outsiderPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(outsiderPage.locator("#recoveryCodeModal.show")).isVisible();
        outsiderPage.locator("#btnRecoveryCodeContinue").click();
        outsiderPage.waitForURL(Pattern.compile(".*/login$"));

        outsiderPage.locator("input[name=userId]").fill(OUTSIDER_ID);
        outsiderPage.locator("input[name=password]").fill(OUTSIDER_PW);
        outsiderPage.locator("#btnLoginSubmit").click();
        outsiderPage.waitForURL(server.baseUrl() + "/");

        var resp = outsiderPage.navigate(shareUrl);
        assertThat(resp.status()).isEqualTo(403);
    }

    @Test
    @Order(6)
    void sameWorkspaceMember_canViewViaSearchResults_throughTheNewViewEndpoint() {
        founderPage.navigate(server.baseUrl() + "/wsa/users");
        var inviteCode = (String) founderPage.evaluate("""
            async () => {
                const r = await fetch('/api/wsa/invites', { method: 'POST' });
                const data = await r.json();
                return data.inviteCode;
            }
            """);
        assertThat(inviteCode).isNotBlank();

        memberPage = browser.newPage();
        memberPage.onDialog(com.microsoft.playwright.Dialog::accept);
        memberPage.navigate(server.baseUrl() + "/register?invite=" + inviteCode);
        memberPage.locator("input[name=userId]").fill(MEMBER_ID);
        memberPage.locator("input[name=password]").fill(MEMBER_PW);
        memberPage.locator("input[name=confirmPassword]").fill(MEMBER_PW);
        memberPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(memberPage.locator("#recoveryCodeModal.show")).isVisible();
        memberPage.locator("#btnRecoveryCodeContinue").click();

        founderPage.navigate(server.baseUrl() + "/wsa/users");
        var pendingRow = founderPage.locator("#pendingTbody tr[data-user-no]");
        assertThat(pendingRow).hasCount(1);
        pendingRow.locator(".btn-approve-pending").click();
        assertThat(founderPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);

        memberPage.navigate(server.baseUrl() + "/login");
        memberPage.locator("input[name=userId]").fill(MEMBER_ID);
        memberPage.locator("input[name=password]").fill(MEMBER_PW);
        memberPage.locator("#btnLoginSubmit").click();
        memberPage.waitForURL(server.baseUrl() + "/");

        memberPage.addInitScript("try { localStorage.setItem('hostsGuideHidden', 'true'); } catch (e) {}");
        memberPage.navigate(server.baseUrl() + "/oehub/hosts");
        memberPage.locator("#btnSearchToggle").click();
        memberPage.locator("#searchInput").fill("new hosts");
        memberPage.waitForTimeout(600); // debounced search
        var result = memberPage.locator(".oe-search-item .search-item-info").first();
        assertThat(result).isVisible();
        result.click();
        memberPage.waitForTimeout(500); // let activateSearchResult's fetch+decrypt settle

        var editorContent = (String) memberPage.evaluate("() => ace.edit('cmHostsEditor').getValue()");
        assertThat(editorContent).contains(PROBE_LINE);
    }

    @Test
    @Order(7)
    void copyingAnotherMembersPublicProfile_decryptsAndReEncryptsWithAFreshKey() {
        memberPage.navigate(server.baseUrl() + "/oehub/hosts");
        memberPage.locator("#btnSearchToggle").click();
        memberPage.locator("#searchInput").fill("new hosts");
        memberPage.waitForTimeout(600); // debounced search
        var copyBtn = memberPage.locator(".oe-search-item .search-copy-btn").first();
        assertThat(copyBtn).isVisible();
        copyBtn.click();
        memberPage.waitForTimeout(500); // unwrap source + re-encrypt + POST /copy round trip

        // The member had no profiles of their own before this - the copy should be their only one.
        var mine = (java.util.List<?>) memberPage.evaluate(
            "async () => await (await fetch('/api/hosts')).json()");
        assertThat(mine).hasSize(1);
        var copy = (Map<?, ?>) mine.get(0);
        assertThat((String) copy.get("wrappedContentKey")).isNotBlank();
        assertThat((String) copy.get("hostsContent")).doesNotContain("share-view-probe");
        firstCopyHostsId = (String) copy.get("hostsId");

        var decrypted = (String) memberPage.evaluate(
            "(row) => OE_CONTENT_CRYPTO.decrypt(row.wrappedContentKey, row.hostsContent, row.visibility)",
            copy);
        assertThat(decrypted).contains(PROBE_LINE);
    }

    @Test
    @Order(8)
    void rawTextEndpoint_refusesToServeCiphertext_forEncryptedContent() {
        var result = (Map<?, ?>) founderPage.evaluate("""
            async (url) => {
                const r = await fetch(url);
                return { status: r.status, body: await r.text() };
            }
            """, server.baseUrl() + "/share/" + hostsId + "/text");
        assertThat(((Number) result.get("status")).intValue()).isEqualTo(409);
        assertThat((String) result.get("body")).doesNotContain("share-view-probe");
    }

    // A third code path that independently hit the same gap as the search-copy button (Order 7)
    // - reported by the user after the earlier fix, since only the search panel's copy button had
    // been wired, not this separate "paste a share link" modal (both end up at the same
    // HostsProfService.copyProfile guard, but from different client-side call sites).
    @Test
    @Order(9)
    void importingViaAPastedShareLink_alsoDecryptsAndReEncryptsWithAFreshKey() {
        memberPage.navigate(server.baseUrl() + "/oehub/hosts");
        memberPage.locator("#btnImportLink").click();
        // _import-link-modal.pebble's own shown.bs.modal listener resets #shareLinkInput to ''
        // (then, as its very last step, focus()es that same input) once the modal's fade-in
        // transition finishes - filling before that reset fires races it and gets silently wiped.
        // A fixed sleep here is exactly the kind of timing assumption that's unreliable across
        // environments (it wasn't enough once already); waiting for focus to actually land on the
        // input is deterministic, since focus() only runs after the reset it follows.
        assertThat(memberPage.locator("#shareLinkInput")).isFocused(
            new com.microsoft.playwright.assertions.LocatorAssertions.IsFocusedOptions().setTimeout(5000));
        memberPage.locator("#shareLinkInput").fill(shareUrl);
        memberPage.locator("#btnImportLinkConfirm").click();
        memberPage.waitForTimeout(500); // unwrap source + re-encrypt + POST /copy round trip

        // Order(7) already gave this member one copy - the import should add a second.
        var mine = (java.util.List<?>) memberPage.evaluate(
            "async () => await (await fetch('/api/hosts')).json()");
        assertThat(mine).hasSize(2);
        var imported = mine.stream()
            .map(o -> (Map<?, ?>) o)
            .filter(m -> !firstCopyHostsId.equals(m.get("hostsId")))
            .findFirst().orElseThrow();
        assertThat((String) imported.get("hostsContent")).doesNotContain("share-view-probe");

        var decrypted = (String) memberPage.evaluate(
            "(row) => OE_CONTENT_CRYPTO.decrypt(row.wrappedContentKey, row.hostsContent, row.visibility)",
            imported);
        assertThat(decrypted).contains(PROBE_LINE);
    }
}
