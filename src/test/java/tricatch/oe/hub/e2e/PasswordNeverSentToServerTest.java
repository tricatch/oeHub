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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves workspace mode's central claim (e2eEncryption design doc §3): a typed password never
 * reaches the server. The browser derives an authKey and a KEK from it (crypto.js deriveKeys); only
 * the authKey is sent, in the field that used to carry the password. Every state-changing request
 * the browser makes while founding an instance, registering, logging in, changing the password and
 * reissuing the recovery code is captured here and searched for the typed passwords.
 *
 * Passwords are letters and digits only, so a form-encoded or JSON body would contain them verbatim
 * if they were sent - there is no encoding that could hide a leak from the substring check.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PasswordNeverSentToServerTest {

    private static final int PORT = 39940;
    private static final String INST_ADMIN_ID = "pnsInstAdmin";
    private static final String INST_ADMIN_PW = "InstAdminSecret4711";
    private static final String FOUNDER_ID = "pnsFounder";
    private static final String FOUNDER_PW = "FounderSecret4711";
    private static final String CHANGED_PW = "ChangedSecret8123";
    private static final Pattern AUTH_KEY_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private final List<String> postedBodies = new CopyOnWriteArrayList<>();

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT, List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        page.onRequest(request -> {
            if ("POST".equals(request.method()) || "PATCH".equals(request.method()) || "PUT".equals(request.method())) {
                postedBodies.add(request.method() + " " + request.url() + " " + request.postData());
            }
        });
    }

    @AfterAll
    void stopAll() {
        if (page != null) page.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private void assertNoneOfTheTypedPasswordsWereSent(String... passwords) {
        assertThat(postedBodies).as("some state-changing requests must have been captured").isNotEmpty();
        for (var body : postedBodies) {
            for (var password : passwords) {
                assertThat(body).as("request body must not contain the typed password").doesNotContain(password);
            }
        }
    }

    /** The value a form or JSON body carries for a field, or null. */
    private String fieldValue(String bodyLine, String field) {
        var m = Pattern.compile("[\"&\\s]?" + field + "[\"]?[=:]\\s?\"?([A-Za-z0-9_%.\\-]+)").matcher(bodyLine);
        return m.find() ? m.group(1) : null;
    }

    @Test
    @Order(1)
    void setupRegisterAndLogin_neverSendThePassword_onlyAnAuthKey() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill(INST_ADMIN_ID);
        page.locator("form[action='/setup'] input[name=password]").fill(INST_ADMIN_PW);
        page.locator("form[action='/setup'] input[name=confirm]").fill(INST_ADMIN_PW);
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator("#recoveryCodeModal.show")).isVisible();
        page.locator("#btnRecoveryCodeContinue").click();
        assertThat(page.locator("#btnGotoLogin")).isEnabled();

        page.navigate(server.baseUrl() + "/register");
        page.locator("input[name=wsName]").fill("Pns Co");
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

        assertNoneOfTheTypedPasswordsWereSent(INST_ADMIN_PW, FOUNDER_PW);

        // What was sent in the password fields is an authKey-shaped value, on every one of them.
        var passwordFieldValues = postedBodies.stream()
            .filter(b -> b.contains("/setup") || b.contains("/register") || b.contains("/login"))
            .map(b -> fieldValue(b, "password"))
            .filter(v -> v != null)
            .toList();
        assertThat(passwordFieldValues).hasSize(3); // setup, register, login
        assertThat(passwordFieldValues).allMatch(v -> AUTH_KEY_SHAPE.matcher(v).matches());
    }

    @Test
    @Order(2)
    void changingThePassword_sendsOnlyAuthKeys_andTheNewPasswordWorks() {
        postedBodies.clear();
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.evaluate("() => document.getElementById('navBtnChangePassword').click()");
        assertThat(page.locator("#changePasswordModal.show")).isVisible();
        page.locator("#cpCurrentPassword").fill(FOUNDER_PW);
        page.locator("#cpNewPassword").fill(CHANGED_PW);
        page.locator("#cpConfirmPassword").fill(CHANGED_PW);
        page.locator("#btnChangePasswordConfirm").click();
        assertThat(page.locator("#changePasswordModal")).not().hasClass(Pattern.compile(".*\\bshow\\b.*"));

        assertNoneOfTheTypedPasswordsWereSent(FOUNDER_PW, CHANGED_PW);
        var change = postedBodies.stream().filter(b -> b.contains("/api/user/change-password")).findFirst().orElseThrow();
        for (var field : List.of("currentPassword", "newPassword", "confirmPassword")) {
            assertThat(fieldValue(change, field)).as(field).matches(AUTH_KEY_SHAPE);
        }

        // The session was invalidated by the change (token_version bump); logging in with the new
        // password proves the new authKey/KEK pair is consistent with what was stored.
        postedBodies.clear();
        page.navigate(server.baseUrl() + "/login");
        page.locator("input[name=userId]").fill(FOUNDER_ID);
        page.locator("input[name=password]").fill(CHANGED_PW);
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");
        assertNoneOfTheTypedPasswordsWereSent(CHANGED_PW);
    }

    @Test
    @Order(3)
    void reissuingTheRecoveryCode_confirmsWithAnAuthKey() {
        postedBodies.clear();
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.evaluate("() => document.getElementById('navBtnReissueRecovery').click()");
        assertThat(page.locator("#reissueConfirmModal.show")).isVisible();
        page.locator("#rrCurrentPassword").fill(CHANGED_PW);
        page.locator("#btnReissueConfirm").click();
        assertThat(page.locator("#reissueRecoveryModal.show")).isVisible(
            new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(5000));

        assertNoneOfTheTypedPasswordsWereSent(CHANGED_PW);
        var reissue = postedBodies.stream().filter(b -> b.contains("/api/user/recovery-key")).findFirst().orElseThrow();
        assertThat(fieldValue(reissue, "currentPassword")).matches(AUTH_KEY_SHAPE);
    }

    @Test
    @Order(4)
    void kdfSaltEndpoint_servesTheRealSalt_andADeterministicDecoyForUnknownIds() {
        // The account's own salt, as stored inside its wrapped private key.
        var keys = (String) page.evaluate("async () => (await (await fetch('/api/user/crypto-keys')).json()).wrappedPrivateKey");
        var storedSalt = parse(keys).get("salt").asText();

        var anon = playwright.request().newContext();
        try {
            var real = kdfSalt(anon, FOUNDER_ID);
            assertThat(real).isEqualTo(storedSalt);

            // Unknown ids: always the same answer for the same id, a different one per id, and the
            // same shape as a real salt - so the endpoint doesn't tell which ids exist.
            var ghostOnce = kdfSalt(anon, "no-such-account-1");
            assertThat(kdfSalt(anon, "no-such-account-1")).isEqualTo(ghostOnce);
            assertThat(kdfSalt(anon, "no-such-account-2")).isNotEqualTo(ghostOnce);
            assertThat(ghostOnce).hasSameSizeAs(real);
            assertThat(java.util.Base64.getDecoder().decode(ghostOnce)).hasSize(16);
            assertThat(kdfSalt(anon, "")).isNotBlank();
        } finally {
            anon.dispose();
        }
    }

    @Test
    @Order(5)
    void serverRejectsAPlainPasswordWhereAnAuthKeyIsRequired() {
        // A stale cached page or a hand-rolled client that still sends the typed password must not
        // get it stored: the server would then hold something that unwraps the private key.
        var result = (Map<?, ?>) page.evaluate("""
            async () => {
                const r = await fetch('/api/user/change-password', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        currentPassword: 'irrelevant', newPassword: 'PlainTypedPassword1',
                        confirmPassword: 'PlainTypedPassword1', newWrappedPrivateKey: '{}'
                    })
                });
                return { status: r.status, body: await r.json() };
            }
            """);
        assertThat(((Number) result.get("status")).intValue()).isEqualTo(400);
        assertThat(((Map<?, ?>) result.get("body")).get("error")).isEqualTo("crypto_required");
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String kdfSalt(com.microsoft.playwright.APIRequestContext anon, String userId) {
        var response = anon.get(server.baseUrl() + "/api/auth/kdf?userId=" + java.net.URLEncoder.encode(userId, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(response.status()).isEqualTo(200);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.text()).get("salt").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
