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
 * End-to-end proof of the "forgot password" recovery flow (e2eEncryption design doc §3 "복구 플로우
 * 프로토콜 — 복구 검증자(recovery verifier)"): a locked-out user who only has their recovery code can
 * verify it (without the server ever learning the code itself), unwrap their own private key
 * client-side, set a new password, and get a freshly-issued recovery code in the same operation -
 * while a wrong code is rejected with one generic error and the old code stops working afterward.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PasswordRecoveryTest {

    private static final int PORT = 39925;
    private static final String FOUNDER_ID = "pwrFounder";
    private static final String OLD_PW = "OldFounderPass123!";
    private static final String NEW_PW = "NewFounderPass456!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    private String founderRecoveryCode;
    private String newRecoveryCode;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, java.util.List.of("-Doe.mode=workspace"));
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
    void bootstrapsWorkspaceRegistersFounderAndLogsIn() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill("pwrInstanceAdmin");
        page.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();

        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill("PWR Co");
        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=password]").fill(OLD_PW);
        page.locator("input[name=confirmPassword]").fill(OLD_PW);
        page.locator("form[action='/register'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();

        // Read the code before dismissing the modal - it's shown exactly once (design doc §3).
        founderRecoveryCode = page.locator("#recoveryCodeValue").inputValue();
        assertThat(founderRecoveryCode).isNotBlank();
        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/login$"));

        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=password]").fill(OLD_PW);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");
    }

    @Test
    @Order(2)
    void wrongRecoveryCode_showsGenericErrorAndKeepsStepTwoHidden() {
        page.navigate(server.baseUrl() + "/logout");
        page.waitForURL(Pattern.compile(".*/login"));

        page.navigate(server.baseUrl() + "/recover");
        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=recoveryCode]").fill(mutateOneChar(founderRecoveryCode));
        page.locator("#btnRecoverVerify").click();

        assertThat(page.locator("#recoverErrorBox")).not().hasClass(Pattern.compile(".*\\bd-none\\b.*"));
        assertThat(page.locator("#recoverErrorBox")).hasText("Invalid user ID or recovery code.");
        assertThat(page.locator("#recoverStep2")).hasClass(Pattern.compile(".*\\bd-none\\b.*"));
    }

    @Test
    @Order(3)
    void correctRecoveryCode_resetsPasswordAndIssuesNewRecoveryCode() {
        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=recoveryCode]").fill(founderRecoveryCode);
        page.locator("#btnRecoverVerify").click();

        assertThat(page.locator("#recoverStep2")).not().hasClass(Pattern.compile(".*\\bd-none\\b.*"));

        page.locator("#recoverStep2Form input[name=newPassword]").fill(NEW_PW);
        page.locator("#recoverStep2Form input[name=confirmPassword]").fill(NEW_PW);
        page.locator("#btnRecoverReset").click();

        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        newRecoveryCode = page.locator("#recoveryCodeValue").inputValue();
        assertThat(newRecoveryCode).isNotBlank().isNotEqualTo(founderRecoveryCode);

        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/login\\?reset=done"));
        assertThat(page.locator(".alert-info")).isVisible();
    }

    @Test
    @Order(4)
    void oldPasswordRejected_newPasswordLogsInAndUnwrapsSessionKeys() {
        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=password]").fill(OLD_PW);
        page.locator("#btnLoginSubmit").click();
        assertThat(page.locator("#loginErrorBox")).not().hasClass(Pattern.compile(".*\\bd-none\\b.*"));

        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=password]").fill(NEW_PW);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");

        page.navigate(server.baseUrl() + "/oehub/hosts");
        Object cached = page.evaluate("""
            async () => {
                const priv = await OE_SESSION_KEYS.loadPrivateKey();
                const ws = await OE_SESSION_KEYS.loadWorkspaceKey();
                return { privType: priv ? priv.type : null, wsAlgo: ws ? ws.algorithm.name : null };
            }
            """);
        assertThat(((Map<?, ?>) cached).get("privType")).isEqualTo("private");
        assertThat(((Map<?, ?>) cached).get("wsAlgo")).isEqualTo("AES-KW");
    }

    @Test
    @Order(5)
    void newRecoveryCodeIsGenuinelyUsable_persistedServerSide() {
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
            """, newRecoveryCode);
        assertThat(unwrappedAlgo).isEqualTo("AES-KW");
    }

    @Test
    @Order(6)
    void oldRecoveryCodeNoLongerVerifies() {
        Object status = page.evaluate("""
            async (codeText) => {
                const bytes = OE_CRYPTO.parseRecoveryDisplayCode(codeText);
                const verifier = await OE_CRYPTO.deriveRecoveryVerifier(bytes);
                const res = await fetch('/api/recover/verify', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ userId: '%s', recoveryVerifier: verifier })
                });
                return res.status;
            }
            """.formatted(FOUNDER_ID), founderRecoveryCode);
        assertThat(((Number) status).intValue()).isEqualTo(400);
    }

    /** Flips exactly one non-space character while keeping the code base64url-shaped and
     *  otherwise well-formed, so the browser still parses it (exercising the server-side
     *  verifier check, not a client-side parse failure). */
    private static String mutateOneChar(String code) {
        var chars = code.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] != ' ') {
                chars[i] = chars[i] == 'A' ? 'B' : 'A';
                break;
            }
        }
        return new String(chars);
    }
}
