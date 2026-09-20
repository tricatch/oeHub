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
 * Proves the full workspace-key chain works end to end under oe.mode=workspace: a founder's browser
 * generates the workspace key and wraps it for itself at registration (e2eEncryption design doc
 * §4), an invited member joins as 'pending' with no key wrap yet (§5), and the founder's approval
 * bundles a fresh wrap of its own cached workspace key for the new member's public key (§5) - the
 * final assertion moves a wrapped content key from the founder's session to the new member's and
 * confirms it decrypts the same content, which is only possible if both ended up holding the
 * identical workspace key material.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkspaceModeKeyWrapTest {

    private static final int PORT = 39917;
    private static final String FOUNDER_ID = "kwFounder";
    private static final String FOUNDER_PW = "FounderPass123!";
    private static final String JOINER_ID = "kwJoiner";
    private static final String JOINER_PW = "JoinerPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page founderPage;
    private Page joinerPage;
    private String inviteCode;

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
        if (joinerPage != null) joinerPage.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void bootstrapsInstanceAdmin_noCaNeededInWorkspaceMode() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("kwInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();

        // workspace mode never requires a CA (cloudGroupService design doc §2.6) - admin alone makes
        // setup complete, so #btnGotoLogin should already be enabled.
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
        page.close();
    }

    @Test
    @Order(2)
    void founderRegistersNewWorkspace_andLogsIn() {
        founderPage = browser.newPage();
        // apiApprovePending's row goes through a native confirm() (users.pebble's
        // onApprovePending) - left unhandled it blocks all further events on the page.
        founderPage.onDialog(com.microsoft.playwright.Dialog::accept);
        founderPage.navigate(server.baseUrl() + "/register");
        founderPage.locator("input[name=wsName]").fill("KeyWrap Co");
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

        Object wsAlgo = founderPage.evaluate("""
            async () => {
                await new Promise((resolve, reject) => {
                    const s = document.createElement('script');
                    s.src = '/js/session-keys.js';
                    s.onload = resolve; s.onerror = reject;
                    document.head.appendChild(s);
                });
                const ws = await OE_SESSION_KEYS.loadWorkspaceKey();
                return ws ? ws.algorithm.name : null;
            }
            """);
        assertThat((String) wsAlgo).isEqualTo("AES-KW");
    }

    @Test
    @Order(3)
    void founderGeneratesInviteCode() {
        founderPage.navigate(server.baseUrl() + "/wsa/users");
        Object codeJson = founderPage.evaluate("""
            async () => {
                const r = await fetch('/api/wsa/invites', { method: 'POST' });
                const data = await r.json();
                return data.inviteCode;
            }
            """);
        inviteCode = (String) codeJson;
        assertThat(inviteCode).isNotBlank();
    }

    @Test
    @Order(4)
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

        // Login must still be blocked - no workspace-key wrap exists for the joiner yet.
        joinerPage.locator("input[name=userId]").fill(JOINER_ID);
        joinerPage.locator("input[name=password]").fill(JOINER_PW);
        joinerPage.locator("#btnLoginSubmit").click();
        assertThat(joinerPage.locator("#loginErrorBox")).not().hasClass("d-none");
    }

    @Test
    @Order(5)
    void founderApprovesJoiner_bundlingWorkspaceKeyWrap() {
        founderPage.navigate(server.baseUrl() + "/wsa/users");
        var pendingRow = founderPage.locator("#pendingTbody tr[data-user-no]");
        assertThat(pendingRow).hasCount(1);
        assertThat(pendingRow).containsText(JOINER_ID);
        // publicKey must have made it all the way from the joiner's registration to this list.
        assertThat(pendingRow.getAttribute("data-public-key")).isNotBlank();

        pendingRow.locator(".btn-approve-pending").click();
        assertThat(founderPage.locator("#pendingTbody tr[data-user-no]")).hasCount(0);
    }

    @Test
    @Order(6)
    void joinerLogsInAfterApproval_andHoldsTheSameWorkspaceKeyAsTheFounder() {
        joinerPage.navigate(server.baseUrl() + "/login");
        joinerPage.locator("input[name=userId]").fill(JOINER_ID);
        joinerPage.locator("input[name=password]").fill(JOINER_PW);
        joinerPage.locator("#btnLoginSubmit").click();
        joinerPage.waitForURL(server.baseUrl() + "/");

        // The founder's browser (still holding its own cached workspace key from Order(2)) wraps
        // a fresh content key and encrypts a test string with it.
        var founderOutput = (java.util.Map<?, ?>) founderPage.evaluate("""
            async () => {
                const wsKey = await OE_SESSION_KEYS.loadWorkspaceKey();
                const dek = await OE_CRYPTO.generateContentKey();
                const enc = await OE_CRYPTO.encryptContent('shared across the whole workspace', dek);
                const wrappedDek = await OE_CRYPTO.wrapContentKeyWithWorkspaceKey(dek, wsKey);
                return { iv: enc.iv, ciphertext: enc.ciphertext, wrappedDek: wrappedDek };
            }
            """);

        // The joiner's browser (whose workspace key came entirely from the approval-time wrap in
        // Order(5)) must be able to unwrap that same content key and decrypt the founder's
        // content - possible only if both browsers ended up with identical workspace key bytes.
        var joinerScript = String.format("""
            async () => {
                async function loadScript(src) {
                    await new Promise((resolve, reject) => {
                        const s = document.createElement('script');
                        s.src = src;
                        s.onload = resolve; s.onerror = reject;
                        document.head.appendChild(s);
                    });
                }
                await loadScript('/js/crypto.js');
                await loadScript('/js/session-keys.js');
                const wsKey = await OE_SESSION_KEYS.loadWorkspaceKey();
                const dek = await OE_CRYPTO.unwrapContentKeyWithWorkspaceKey('%s', wsKey);
                return await OE_CRYPTO.decryptContent({ iv: '%s', ciphertext: '%s' }, dek);
            }
            """, founderOutput.get("wrappedDek"), founderOutput.get("iv"), founderOutput.get("ciphertext"));
        Object decrypted = joinerPage.evaluate(joinerScript);
        assertThat((String) decrypted).isEqualTo("shared across the whole workspace");
    }
}
