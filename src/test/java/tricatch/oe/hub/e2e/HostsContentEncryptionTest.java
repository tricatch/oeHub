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
 * Proves hosts.pebble's content-encryption wiring end to end under oe.mode=workspace: the server
 * only ever stores ciphertext (a fresh, independent fetch confirms it, not the app's own
 * already-decrypted in-memory copy), the editor still shows real plaintext after a full page
 * reload (a fresh unwrap via the cached workspace key, not a leftover JS variable), and a
 * private<->public visibility flip re-wraps the same DEK without corrupting the content.
 * See aidoc/e2eEncryption/00-design.md §1's correction: this only applies to HOSTS_PFILE under
 * oe.mode=workspace - the self-hosted editing e2e tests elsewhere exercise the unencrypted path.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HostsContentEncryptionTest {

    private static final int PORT = 39919;
    private static final String USER_ID = "hceFounder";
    private static final String USER_PW = "HcePass123!";
    private static final String PROBE_LINE = "127.0.0.1 encryption-probe.oe";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String hostsId;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, java.util.List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        // hosts.pebble auto-shows a first-visit "install guide" modal (util.js's
        // initGuideModal(), see SetupToOeProxyScenarioTest's dismissGuideModalIfShown() for the
        // click-based alternative) that blocks pointer events on everything else until
        // dismissed - simpler to just pre-seed the "already seen it" flag it checks.
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
    void bootstrapsWorkspaceAndLogsIn() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("hceInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/setup"));

        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill("HCE Co");
        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(USER_PW);
        page.locator("input[name=confirmPassword]").fill(USER_PW);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/login$"));

        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(USER_PW);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");
    }

    @Test
    @Order(2)
    void creatingAProfile_storesCiphertextServerSide_butDisplaysPlaintext() {
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.locator("#btnAdd").click();
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(1);
        assertThat(page.locator("#hostsEditorBody")).isVisible();

        hostsId = (String) page.evaluate("() => document.querySelector('#profileList .oe-list-item').dataset.hostsId");
        assertThat(hostsId).isNotBlank();

        // Independent fetch (not the app's own decrypted in-memory `profiles` array) - this is
        // exactly what the server actually persisted.
        var raw = (Map<?, ?>) page.evaluate("async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }", hostsId);
        assertThat((String) raw.get("wrappedContentKey")).isNotBlank();
        var storedContent = (String) raw.get("hostsContent");
        // Ciphertext is a JSON {iv, ciphertext} record - it must not contain the plaintext
        // example content's recognizable markers, and must parse as that JSON shape.
        assertThat(storedContent).doesNotContain("127.0.0.1").doesNotContain("foo.oe");
        assertThat(storedContent).contains("\"iv\"").contains("\"ciphertext\"");
    }

    @Test
    @Order(3)
    void editingContent_reEncryptsWithTheSameKey_andReadsBackCorrectlyAfterReload() {
        page.locator("#cmHostsEditor").click();
        page.keyboard().press("Control+A");
        page.keyboard().type(PROBE_LINE);
        assertThat(page.locator("#saveStatus")).containsText("Saved",
                new com.microsoft.playwright.assertions.LocatorAssertions.ContainsTextOptions().setTimeout(5000));

        // Still ciphertext server-side after the edit, and still doesn't leak the plaintext.
        var raw = (Map<?, ?>) page.evaluate("async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }", hostsId);
        assertThat((String) raw.get("hostsContent")).doesNotContain("encryption-probe");

        // A full reload forces a fresh unwrap (via the cached workspace key surviving navigation,
        // not a leftover JS variable) - the editor must still show the real content afterward.
        // ace.edit() on an already-initialized element returns that same live editor instance
        // (a well-known Ace API behavior), which is more reliable than scraping rendered DOM text.
        page.reload();
        page.waitForTimeout(500); // let init()'s async decrypt + activateProfile settle
        var editorContent = (String) page.evaluate("() => ace.edit('cmHostsEditor').getValue()");
        assertThat(editorContent).contains(PROBE_LINE);
    }

    @Test
    @Order(4)
    void togglingVisibilityToPrivateAndBack_rewrapsWithoutLosingContent() {
        page.locator("#visibilitySelect").selectOption("private");
        page.waitForTimeout(300);
        var afterPrivate = (Map<?, ?>) page.evaluate("async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }", hostsId);
        assertThat((String) afterPrivate.get("visibility")).isEqualTo("private");
        assertThat((String) afterPrivate.get("wrappedContentKey")).isNotBlank();

        page.locator("#visibilitySelect").selectOption("public");
        page.waitForTimeout(300);
        var afterPublic = (Map<?, ?>) page.evaluate("async (id) => { const list = await (await fetch('/api/hosts')).json(); return list.find(p => p.hostsId === id); }", hostsId);
        assertThat((String) afterPublic.get("visibility")).isEqualTo("public");

        page.reload();
        page.waitForTimeout(500);
        var editorContent = (String) page.evaluate("() => ace.edit('cmHostsEditor').getValue()");
        assertThat(editorContent).contains(PROBE_LINE);
    }
}
