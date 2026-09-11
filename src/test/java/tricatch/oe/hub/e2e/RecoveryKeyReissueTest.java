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

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the "reissue recovery code" account-menu action (e2eEncryption design doc §3/§9's
 * "재발급" item): it re-wraps the SAME already-cached private key under a freshly-generated
 * recovery code, persists that wrap server-side (via POST /api/user/recovery-key), and the
 * resulting code is genuinely usable - unwrapping it recovers a private key that correctly
 * unwraps this account's own wrapped workspace key, proving it's the real matching keypair and
 * not just a UI illusion.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecoveryKeyReissueTest {

    private static final int PORT = 39922;
    private static final String FOUNDER_ID = "rkrFounder";
    private static final String FOUNDER_PW = "FounderPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, java.util.List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        // hosts.pebble auto-shows a first-visit install-guide modal that blocks clicks elsewhere
        // on the page (see HostsContentEncryptionTest) - not what this test is about, so suppress it.
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
        page.locator("form[action='/setup'] input[name=userId]").fill("rkrInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();

        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill("RKR Co");
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
    }

    @Test
    @Order(2)
    void reissuingProducesAWorkingRecoveryCode_persistedServerSide() {
        var oldWrappedRecovery = (String) page.evaluate(
            "async () => (await (await fetch('/api/user/crypto-keys')).json()).wrappedPrivateKeyRecovery");
        assertThat(oldWrappedRecovery).isNotBlank();

        // index.pebble (at "/") extends the lightweight layout.pebble, not app-layout.pebble, so
        // it has no user-menu dropdown - any app-layout.pebble page does.
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.locator("[data-bs-toggle=dropdown]").first().click();
        page.locator("#navBtnReissueRecovery").click();
        assertThat(page.locator("#reissueRecoveryModal.show")).isVisible(
            new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));
        var displayedCode = page.locator("#reissueRecoveryCodeValue").inputValue();
        assertThat(displayedCode).isNotBlank();
        page.locator("#btnReissueRecoveryContinue").click();

        var newWrappedRecovery = (String) page.evaluate(
            "async () => (await (await fetch('/api/user/crypto-keys')).json()).wrappedPrivateKeyRecovery");
        assertThat(newWrappedRecovery).isNotBlank().isNotEqualTo(oldWrappedRecovery);

        // The displayed code must actually unwrap to the SAME private key already cached - proven
        // by using it to unwrap this account's own wrapped workspace key and getting back a real,
        // usable AES-KW key (design doc §4's workspace-key algorithm).
        var unwrappedAlgo = (String) page.evaluate("""
            async (codeText) => {
                const bytes = OE_CRYPTO.parseRecoveryDisplayCode(codeText);
                const kek = await OE_CRYPTO.importRecoveryKeyAsAesGcm(bytes);
                const keysResp = await (await fetch('/api/user/crypto-keys')).json();
                const record = JSON.parse(keysResp.wrappedPrivateKeyRecovery);
                const recoveredPrivateKey = await OE_CRYPTO.unwrapPrivateKey(record, kek);
                const unwrappedWsKey = await OE_CRYPTO.unwrapWorkspaceKeyForUser(keysResp.wrappedWsKey, recoveredPrivateKey);
                return unwrappedWsKey.algorithm.name;
            }
            """, displayedCode);
        assertThat(unwrappedAlgo).isEqualTo("AES-KW");
    }
}
