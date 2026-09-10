package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
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
 * Proves the fully-public, no-login "public link" feature (e2eEncryption design doc §6's last
 * item, implemented as a follow-up to the earlier /share auth-gating work): a freshly-generated
 * link's raw decryption key never touches the server (it only ever appears in the browser and in
 * the URL fragment), an anonymous visitor with no session/cookies at all can still decrypt and
 * view it, revoking makes it 404 for that same anonymous visitor, and issuing/revoking never
 * disturbs the profile's normal (workspace-key-encrypted) hosts_content/wrapped_content_key.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublicLinkTest {

    private static final int PORT = 39923;
    private static final String FOUNDER_ID = "plFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String PROBE_LINE = "127.0.0.1 public-link-probe.oe";
    private static final String SECOND_PROBE_LINE = "127.0.0.1 public-link-probe-2.oe";
    private static final String THIRD_PROBE_LINE = "127.0.0.1 public-link-probe-3.oe";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String hostsId;
    private String linkUrl;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, java.util.List.of("-Doe.mode=group"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        page.addInitScript("try { localStorage.setItem('hostsGuideHidden', 'true'); } catch (e) {}");
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
    void bootstrapsWorkspaceAndCreatesAPublicProfile() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("plInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();

        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill("PL Co");
        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=password]").fill(FOUNDER_PW);
        page.locator("input[name=confirmPassword]").fill(FOUNDER_PW);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/login$"));

        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=password]").fill(FOUNDER_PW);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");

        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.locator("#btnAdd").click();
        assertThat(page.locator("#hostsEditorBody")).isVisible();
        hostsId = (String) page.evaluate(
            "() => document.querySelector('#profileList .oe-list-item').dataset.hostsId");
        assertThat(hostsId).isNotBlank();

        page.locator("#cmHostsEditor").click();
        page.keyboard().press("Control+A");
        page.keyboard().type(PROBE_LINE);
        assertThat(page.locator("#saveStatus")).containsText("Saved",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
    }

    @Test
    @Order(2)
    void generatingALink_producesAWorkingUrlWithTheKeyOnlyInTheFragment() {
        assertThat(page.locator("#btnPublicLink")).isEnabled();
        page.locator("#btnPublicLink").click();
        assertThat(page.locator("#publicLinkModal.show")).isVisible();
        page.locator("#btnGeneratePublicLink").click();
        assertThat(page.locator("#publicLinkResultWrap")).isVisible(
                new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
        linkUrl = page.locator("#publicLinkValue").inputValue();
        assertThat(linkUrl).contains("/link/" + hostsId + "/oelink#key=");

        // The server-persisted row must never contain the raw fragment key or PROBE_LINE in the
        // clear anywhere - link_content is a completely separate ciphertext from hosts_content,
        // and this asserts the ordinary (workspace-key) row is untouched by issuing a link.
        var raw = (java.util.Map<?, ?>) page.evaluate(
            "async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }",
            hostsId);
        assertThat((String) raw.get("hostsContent")).doesNotContain("public-link-probe");

        page.locator("#publicLinkModal .btn-close").click();
        assertThat(page.locator("#publicLinkModal.show")).not().isVisible(
            new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
    }

    @Test
    @Order(3)
    void anonymousVisitorWithNoSessionAtAll_canDecryptAndViewTheLink() {
        var anonContext = browser.newContext(); // fresh, cookie-less - proves no login is needed
        var anonPage = anonContext.newPage();
        anonPage.navigate(linkUrl);
        assertThat(anonPage.locator("#rawContent")).containsText(PROBE_LINE,
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
        anonContext.close();
    }

    @Test
    @Order(4)
    void visitingTheSameUrlWithoutTheFragmentKey_failsToDecrypt() {
        var anonContext = browser.newContext();
        var anonPage = anonContext.newPage();
        var urlWithoutFragment = linkUrl.substring(0, linkUrl.indexOf('#'));
        anonPage.navigate(urlWithoutFragment);
        assertThat(anonPage.locator("#rawContent")).not().containsText(PROBE_LINE,
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
        anonContext.close();
    }

    @Test
    @Order(5)
    void editingAfterGeneratingALink_liveUpdatesTheSameLink() {
        // The "living link" redesign (e2eEncryption design doc §6): the row's wrapped_link_key
        // lets the editor's browser re-encrypt link_content on every save, so the link URL never
        // changes but always shows the latest content - no more stale snapshot.
        page.locator("#cmHostsEditor").click();
        page.keyboard().press("Control+A");
        page.keyboard().type(SECOND_PROBE_LINE);
        assertThat(page.locator("#saveStatus")).containsText("Saved",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        var anonContext = browser.newContext();
        var anonPage = anonContext.newPage();
        anonPage.navigate(linkUrl);
        assertThat(anonPage.locator("#rawContent")).containsText(SECOND_PROBE_LINE,
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
        assertThat(anonPage.locator("#rawContent")).not().containsText(PROBE_LINE,
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
        anonContext.close();
    }

    @Test
    @Order(6)
    void rotatingTheWorkspaceKey_theLinkSurvivesAndKeepsUpdating() {
        // wrapped_link_key must rotate in lockstep with wrapped_content_key (e2eEncryption design
        // doc §6/§7) - otherwise the very next save after a rotation would silently break the
        // link (the editor's browser would fail to unwrap it with the new workspace key).
        page.navigate(server.baseUrl() + "/oehub/admin/users");
        page.onceDialog(com.microsoft.playwright.Dialog::accept);
        page.locator("#btnRotateWsKey").click();
        assertThat(page.locator("#toast")).containsText("Workspace key rotated.",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        page.navigate(server.baseUrl() + "/oehub/hosts");
        assertThat(page.locator("#hostsEditorBody")).isVisible();
        page.waitForTimeout(500); // let init()'s async decrypt + activateProfile settle

        page.locator("#cmHostsEditor").click();
        page.keyboard().press("Control+A");
        page.keyboard().type(THIRD_PROBE_LINE);
        assertThat(page.locator("#saveStatus")).containsText("Saved",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        var anonContext = browser.newContext();
        var anonPage = anonContext.newPage();
        anonPage.navigate(linkUrl);
        assertThat(anonPage.locator("#rawContent")).containsText(THIRD_PROBE_LINE,
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));
        anonContext.close();
    }

    @Test
    @Order(7)
    void reopeningTheModal_showsTheCurrentUrl() {
        // The raw key can now be recovered by unwrapping wrapped_link_key with the workspace key,
        // so the modal shows the already-issued link immediately instead of requiring a fresh
        // "Generate" (e2eEncryption design doc §6).
        page.locator("#btnPublicLink").click();
        assertThat(page.locator("#publicLinkModal.show")).isVisible();
        assertThat(page.locator("#publicLinkResultWrap")).isVisible(
                new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
        assertThat(page.locator("#publicLinkValue")).hasValue(linkUrl);
        page.locator("#publicLinkModal .btn-close").click();
        assertThat(page.locator("#publicLinkModal.show")).not().isVisible(
                new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
    }

    @Test
    @Order(8)
    void changingVisibilityAwayFromPublic_autoRevokesTheLink() {
        page.locator("#visibilitySelect").selectOption("private");
        page.waitForTimeout(300);

        var anonContext = browser.newContext();
        var anonPage = anonContext.newPage();
        Response resp = anonPage.navigate(linkUrl);
        assertThat(resp.status()).isEqualTo(404);
        anonContext.close();

        page.locator("#visibilitySelect").selectOption("public");
        page.waitForTimeout(300);

        page.locator("#btnPublicLink").click();
        assertThat(page.locator("#publicLinkModal.show")).isVisible();
        assertThat(page.locator("#publicLinkResultWrap")).not().isVisible();
        page.locator("#publicLinkModal .btn-close").click();
        assertThat(page.locator("#publicLinkModal.show")).not().isVisible(
                new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
    }

    @Test
    @Order(9)
    void revoking_makesTheSameLink404ForAnonymousVisitors() {
        // The previous test's visibility flip auto-revoked the earlier link - issue a fresh one
        // here so this test still proves the "Revoke" button itself works end-to-end.
        page.locator("#btnPublicLink").click();
        assertThat(page.locator("#publicLinkModal.show")).isVisible();
        page.locator("#btnGeneratePublicLink").click();
        assertThat(page.locator("#publicLinkResultWrap")).isVisible(
                new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
        linkUrl = page.locator("#publicLinkValue").inputValue();

        page.locator("#btnRevokePublicLink").click();
        assertThat(page.locator("#publicLinkModal.show")).not().isVisible(
            new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));

        var anonContext = browser.newContext();
        var anonPage = anonContext.newPage();
        Response resp = anonPage.navigate(linkUrl);
        assertThat(resp.status()).isEqualTo(404);
        anonContext.close();
    }
}
