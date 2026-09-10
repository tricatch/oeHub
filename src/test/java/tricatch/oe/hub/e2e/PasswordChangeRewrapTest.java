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
 * Proves that changing your own password re-wraps wrapped_private_key for the new password in
 * the same request (e2eEncryption design doc §3) - if it didn't, the old wrap would become
 * permanently unusable the moment the password changed, since PBKDF2-deriving the KEK from the
 * new password would no longer match what wrapped it.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PasswordChangeRewrapTest {

    private static final int PORT = 39918;
    private static final String USER_ID = "pwRewrapAdmin";
    private static final String OLD_PW = "OldPassword123!";
    private static final String NEW_PW = "NewPassword456!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT);
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
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
    void bootstrapsAdminAndLogsIn() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill(USER_ID);
        page.locator("form[action='/setup'] input[name=password]").fill(OLD_PW);
        page.locator("form[action='/setup'] input[name=confirm]").fill(OLD_PW);
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        page.waitForURL(Pattern.compile(".*/setup"));

        page.locator("input[name=caName]").fill("Pw Rewrap Test CA");
        page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();
        page.waitForURL(Pattern.compile(".*/setup"));

        page.locator("#btnGotoLogin").click();
        page.waitForURL(Pattern.compile(".*/login"));
        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(OLD_PW);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");
    }

    @Test
    @Order(2)
    void changesPassword_viaSettingsModal() {
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.evaluate("() => document.getElementById('navBtnChangePassword').click()");
        assertThat(page.locator("#changePasswordModal.show")).isVisible();

        page.locator("#cpCurrentPassword").fill(OLD_PW);
        page.locator("#cpNewPassword").fill(NEW_PW);
        page.locator("#cpConfirmPassword").fill(NEW_PW);
        page.locator("#btnChangePasswordConfirm").click();

        // Modal closes on success - if the request had failed (e.g. crypto_required, or wrong
        // current password) cpError would be shown instead and the modal would stay open.
        assertThat(page.locator("#changePasswordModal")).not().hasClass(Pattern.compile(".*\\bshow\\b.*"));
    }

    @Test
    @Order(3)
    void oldPasswordNoLongerWorks_newPasswordLogsInAndUnwrapsTheSameKey() {
        page.evaluate("() => document.getElementById('navLogoutLink').click()");
        page.waitForURL(Pattern.compile(".*/login"));

        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(OLD_PW);
        page.locator("#btnLoginSubmit").click();
        assertThat(page.locator("#loginErrorBox")).not().hasClass("d-none");

        page.locator("input[name=userId]").fill(USER_ID);
        page.locator("input[name=password]").fill(NEW_PW);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");

        page.navigate(server.baseUrl() + "/oehub/hosts");
        Object privType = page.evaluate("async () => { const k = await OE_SESSION_KEYS.loadPrivateKey(); return k ? k.type : null; }");
        assertThat((String) privType).isEqualTo("private");
    }
}
