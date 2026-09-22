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

import static org.assertj.core.api.Assertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Proves that the hosts export/import (GET/POST /api/hosts/export|import) and full-account
 * backup/restore (GET/POST /api/user/backup|restore) round trips carry an encrypted (workspace mode)
 * row's wrapped_content_key through unchanged, alongside its ciphertext hosts_content - a real
 * bug found by auditing for other missed call sites after the user caught the "import from share
 * link" gap (e2eEncryption design doc §9): both controllers dropped wrapped_content_key on the
 * way back in, so a restored/imported row's ciphertext would land with no key at all and get
 * treated/displayed as if it were plaintext. Re-wrapping is never needed here - importing back
 * into the SAME account/workspace means the existing wrap (personal key for 'private', workspace
 * key for 'workspace'/'collabo', identical either way per §6) is still valid unchanged.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExportImportEncryptionTest {

    private static final int PORT = 39924;
    private static final String FOUNDER_ID = "eieFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String PROBE_LINE = "127.0.0.1 export-import-probe.oe";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private String hostsId;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.mode=workspace"));
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
    void bootstrapsWorkspaceAndCreatesAnEncryptedProfile() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("eieInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();

        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill("EIE Co");
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
    void exportThenImport_preservesTheWrapAndDecryptsCorrectly() {
        var exported = (Map<?, ?>) page.evaluate(
            "async () => await (await fetch('/api/hosts/export')).json()");
        var exportedHosts = (List<?>) exported.get("hosts");
        var exportedRow = exportedHosts.stream()
            .map(o -> (Map<?, ?>) o)
            .filter(m -> hostsId.equals(m.get("hostsId")))
            .findFirst().orElseThrow();
        assertThat((String) exportedRow.get("wrappedContentKey")).isNotBlank();
        assertThat((String) exportedRow.get("hostsContent")).doesNotContain("export-import-probe");
        // The public link (linkContent/wrappedLinkKey) is per-workspace derived data - it must
        // never leave in an export, since wrappedLinkKey can't be unwrapped outside the workspace
        // it was wrapped in (e2eEncryption design doc §6).
        assertThat(exportedRow.get("linkContent")).isNull();
        assertThat(exportedRow.get("wrappedLinkKey")).isNull();

        var importResult = (Map<?, ?>) page.evaluate("""
            async (exportedJson) => {
                const r = await fetch('/api/hosts/import?merge=true', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(exportedJson)
                });
                return { status: r.status };
            }
            """, exported);
        assertThat(((Number) importResult.get("status")).intValue()).isEqualTo(200);

        var mine = (List<?>) page.evaluate("async () => await (await fetch('/api/hosts')).json()");
        var imported = mine.stream()
            .map(o -> (Map<?, ?>) o)
            .filter(m -> !hostsId.equals(m.get("hostsId")))
            .findFirst().orElseThrow();
        assertThat((String) imported.get("wrappedContentKey")).isNotBlank();

        var decrypted = (String) page.evaluate(
            "(row) => OE_CONTENT_CRYPTO.decrypt(row.wrappedContentKey, row.hostsContent, row.shareScope)",
            imported);
        assertThat(decrypted).contains(PROBE_LINE);
    }

    @Test
    @Order(3)
    void backupThenRestore_alsoPreservesTheWrapAndDecryptsCorrectly() {
        var backup = (Map<?, ?>) page.evaluate(
            "async () => await (await fetch('/api/user/backup')).json()");

        var restoreResult = (Map<?, ?>) page.evaluate("""
            async (backupJson) => {
                const r = await fetch('/api/user/restore?merge=true', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(backupJson)
                });
                return { status: r.status };
            }
            """, backup);
        assertThat(((Number) restoreResult.get("status")).intValue()).isEqualTo(200);

        // Order(2) already brought this account to 2 hosts profiles - a merge restore of the same
        // 2 rows should bring it to 4, all still correctly decryptable.
        var mine = (List<?>) page.evaluate("async () => await (await fetch('/api/hosts')).json()");
        assertThat(mine).hasSize(4);
        for (var o : mine) {
            var row = (Map<?, ?>) o;
            var decrypted = (String) page.evaluate(
                "(row) => OE_CONTENT_CRYPTO.decrypt(row.wrappedContentKey, row.hostsContent, row.shareScope)",
                row);
            assertThat(decrypted).contains(PROBE_LINE);
        }
    }
}
