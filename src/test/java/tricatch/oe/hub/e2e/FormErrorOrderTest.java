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
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Input-form errors are reported in on-screen field order, one message at a time (CLAUDE.md
 * "Forms — Validation Message Order"): an empty first field must never be pre-empted by a later
 * field's message. Workspace mode, because that is where the browser does the validating itself.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FormErrorOrderTest {

    private static final int PORT = 39946;

    private static E2eServer server;
    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void stopAll() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    /** The toast must show the message for {@code key} - compared through window.MSG so the test does
     *  not depend on the browser's locale. */
    private static void assertToast(Page page, String key) {
        var expected = (String) page.evaluate("k => window.MSG[k]", key);
        assertThat(page.locator("#toast.show .oe-toast-msg")).hasText(expected);
    }

    @Test
    @Order(1)
    void setupFormReportsUserIdBeforePassword() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/setup");
        var submit = page.locator("form[action='/setup'] button[type=submit]");

        submit.click();                                        // everything empty
        assertToast(page, "auth.error.userid.required");

        page.locator("form[action='/setup'] input[name=userId]").fill("ab");
        submit.click();
        assertToast(page, "auth.error.userid.too.short");

        page.locator("form[action='/setup'] input[name=userId]").fill("admin1");
        submit.click();                                        // user id fine, password still empty
        assertToast(page, "auth.error.password.required");

        // Finish the setup so /register becomes reachable for the next test.
        page.locator("form[action='/setup'] input[name=password]").fill("Setup-Order-Pw-1");
        page.locator("form[action='/setup'] input[name=confirm]").fill("Setup-Order-Pw-1");
        submit.click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        assertThat(page.locator("#btnGotoLogin")).isEnabled();
        page.close();
    }

    @Test
    @Order(2)
    void registerFormReportsWorkspaceNameThenUserIdThenPassword() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/register");
        var submit = page.locator("#btnRegisterSubmit");

        submit.click();
        assertToast(page, "auth.error.wsname.required");

        page.locator("input[name=wsName]").fill("Order Co");
        submit.click();
        assertToast(page, "auth.error.userid.required");

        page.locator("input[name=userId]").fill("orderuser");
        submit.click();
        assertToast(page, "auth.error.password.required");
        page.close();
    }

    /** The change-password dialog: the button waits for all three fields, then current password is
     *  checked before the new one - in the browser and, bypassing it, on the server. */
    @Test
    @Order(4)
    void changePasswordReportsCurrentPasswordFirst() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/login");
        page.locator("input[name=userId]").fill("admin1");
        page.locator("input[name=password]").fill("Setup-Order-Pw-1");
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.evaluate("() => document.getElementById('navBtnChangePassword').click()");
        assertThat(page.locator("#changePasswordModal.show")).isVisible();
        var confirm = page.locator("#btnChangePasswordConfirm");

        assertThat(confirm).isDisabled();
        page.locator("#cpNewPassword").fill("short");
        page.locator("#cpConfirmPassword").fill("short");
        assertThat(confirm).isDisabled();                      // current password still empty

        page.locator("#cpCurrentPassword").fill("Setup-Order-Pw-1");
        assertThat(confirm).isEnabled();
        confirm.click();                                       // all filled: now the value rules apply
        var tooShort = (String) page.evaluate("k => window.MSG[k]", "change.password.error.too.short");
        assertThat(page.locator("#cpError")).hasText(tooShort);

        var error = (String) page.evaluate(
            "async () => (await (await fetch('/api/user/change-password', {method: 'POST',"
                + " headers: {'Content-Type': 'application/json'},"
                + " body: JSON.stringify({currentPassword: '', newPassword: 'x', confirmPassword: 'y',"
                + " newWrappedPrivateKey: 'x'})})).json()).error");
        org.assertj.core.api.Assertions.assertThat(error).isEqualTo("current_password_required");
        page.close();
    }

    /** Login sends nothing until both fields are filled, naming the first empty one; the server
     *  answers the same way, in the same order, when the browser's check is bypassed. */
    @Test
    @Order(5)
    void loginReportsMissingUserIdThenPasswordWithoutSubmitting() {
        var page = browser.newPage();
        var loginPosts = new java.util.concurrent.atomic.AtomicInteger();
        page.onRequest(r -> { if ("POST".equals(r.method()) && r.url().endsWith("/login")) loginPosts.incrementAndGet(); });
        page.navigate(server.baseUrl() + "/login");
        var submit = page.locator("#btnLoginSubmit");
        var box = page.locator("#loginErrorBox");

        submit.click();
        assertThat(box).hasText((String) page.evaluate("k => window.MSG[k]", "auth.error.userid.required"));

        page.locator("input[name=userId]").fill("admin1");
        submit.click();
        assertThat(box).hasText((String) page.evaluate("k => window.MSG[k]", "auth.error.password.required"));
        org.assertj.core.api.Assertions.assertThat(loginPosts.get()).isZero();

        var html = (String) page.evaluate(
            "async () => { const f = document.getElementById('loginForm');"
                + " const b = new URLSearchParams({_csrf: f._csrf.value, redirect: '', userId: '', password: ''});"
                + " return await (await fetch('/login', {method: 'POST', body: b})).text(); }");
        var expected = (String) page.evaluate("k => window.MSG[k]", "auth.error.userid.required");
        org.assertj.core.api.Assertions.assertThat(html).contains(expected);
        page.close();
    }

    /** Same order when the browser's own checks are bypassed and the server judges the form. */
    @Test
    @Order(3)
    void registerServerReportsWorkspaceNameBeforeUserId() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/register");
        page.evaluate("() => { const f = document.getElementById('registerForm');"
            + " const clone = f.cloneNode(true); f.replaceWith(clone); clone.submit(); }");
        assertThat(page.locator(".alert-danger")).isVisible();
        var expected = (String) page.evaluate("k => window.MSG[k]", "auth.error.wsname.required");
        assertThat(page.locator(".alert-danger")).hasText(expected);
        page.close();
    }
}
