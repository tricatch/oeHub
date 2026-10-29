package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// In workspace mode the browser re-wraps the private key with an extractable copy it unwraps from the
// account's own wrapped key using the CURRENT password (the session-cached copy is not extractable).
// A wrong password can't unwrap it, so the browser then sends a placeholder instead of a wrap - and the
// server must count the wrong attempt like any other, verify the password BEFORE looking at the wrap,
// and never store a placeholder.
class UserControllerWrapCheckTest extends MapperTestBase {

    private static final String CURRENT_AUTH_KEY = "A".repeat(43);
    private static final String NEW_AUTH_KEY = "B".repeat(43);
    private static final String OLD_WRAP = "{\"salt\":\"c2FsdA==\",\"iv\":\"aXY=\",\"wrapped\":\"b2xk\"}";
    private static final String OLD_RECOVERY_WRAP = "{\"iv\":\"aXY=\",\"wrapped\":\"b2xkLXJlY292ZXJ5\"}";

    private static final String NEW_WRAP = "{\"salt\":\"bmV3\",\"iv\":\"aXY=\",\"wrapped\":\"bmV3\"}";
    private static final String NEW_RECOVERY_WRAP = "{\"iv\":\"aXY=\",\"wrapped\":\"bmV3LXJlY292ZXJ5\"}";
    private static final String PLACEHOLDER = "{}"; // OE_AUTH.UNUSABLE_WRAP

    private final UserController controller = new UserController(FACTORY, new ObjectMapper());
    private String previousMode;

    @BeforeEach
    void workspaceMode() {
        previousMode = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "workspace");
    }

    @AfterEach
    void restoreMode() {
        if (previousMode == null) System.clearProperty("oe.mode");
        else System.setProperty("oe.mode", previousMode);
    }

    /** An account whose "password" is the authKey the browser would send, with real-looking wraps. */
    private HubUser accountWithWraps(String userId) {
        var user = insertUser(userId);
        user.setPassword(PasswordUtil.hash(CURRENT_AUTH_KEY));
        user.setWrappedPrivateKey(OLD_WRAP);
        user.setWrappedPrivateKeyRecovery(OLD_RECOVERY_WRAP);
        user.setRecoveryVerifier(PasswordUtil.hash("old-verifier"));
        user.setUpdatedBy(user.getUserNo());
        user.setUpdatedAt(LocalDateTime.now());
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.updatePasswordAndRewrapPrivateKey(user);
            mapper.updateWrappedPrivateKeyRecovery(user);
        }
        return user;
    }

    private HubUser stored(HubUser user) {
        try (var session = FACTORY.openSession()) {
            return session.getMapper(HubUserMapper.class).findByUserNo(user.getUserNo());
        }
    }

    private Javalin appAs(HubUser actor) {
        return Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute("currentUser", actor));
            config.routes.post("/api/user/change-password", controller::apiChangePassword);
            config.routes.post("/api/user/recovery-key", controller::apiReissueRecoveryKey);
        });
    }

    private static Map<String, String> changeBody(String currentPassword, String wrap) {
        return Map.of("currentPassword", currentPassword, "newPassword", NEW_AUTH_KEY,
            "confirmPassword", NEW_AUTH_KEY, "newWrappedPrivateKey", wrap);
    }

    private static Map<String, String> reissueBody(String currentPassword, String recoveryWrap) {
        return Map.of("currentPassword", currentPassword, "wrappedPrivateKeyRecovery", recoveryWrap,
            "recoveryVerifier", "new-verifier");
    }

    // ── change-password ─────────────────────────────────────────────────────

    @Test
    void changePassword_rightPasswordAndARealWrap_isStored() {
        var user = accountWithWraps("wrap-change-ok");

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/user/change-password", changeBody(CURRENT_AUTH_KEY, NEW_WRAP)).code()).isEqualTo(204));

        assertThat(stored(user).getWrappedPrivateKey()).isEqualTo(NEW_WRAP);
        assertThat(PasswordUtil.matches(NEW_AUTH_KEY, stored(user).getPassword())).isTrue();
    }

    @Test
    void changePassword_wrongPasswordWithAPlaceholder_isCountedAsAWrongAttempt_andNothingChanges() throws Exception {
        var user = accountWithWraps("wrap-change-wrong");

        JavalinTest.test(appAs(user), (server, client) -> {
            var response = client.post("/api/user/change-password", changeBody("C".repeat(43), PLACEHOLDER));

            assertThat(response.code()).isEqualTo(400);
            var body = new ObjectMapper().readTree(response.body().string());
            assertThat(body.get("error").asText()).isEqualTo("current_password_invalid");
            assertThat(body.get("remainingAttempts").asInt()).isEqualTo(4); // the attempt was counted
        });

        assertThat(stored(user).getWrappedPrivateKey()).isEqualTo(OLD_WRAP);
        assertThat(PasswordUtil.matches(CURRENT_AUTH_KEY, stored(user).getPassword())).isTrue();
    }

    @Test
    void changePassword_rightPasswordButAPlaceholder_isRefused_andTheStoredWrapSurvives() {
        var user = accountWithWraps("wrap-change-placeholder");

        JavalinTest.test(appAs(user), (server, client) -> {
            var response = client.post("/api/user/change-password", changeBody(CURRENT_AUTH_KEY, PLACEHOLDER));
            assertThat(response.code()).isEqualTo(400);
        });

        // Neither the wrap nor the password changed: a placeholder must never replace the real key.
        assertThat(stored(user).getWrappedPrivateKey()).isEqualTo(OLD_WRAP);
        assertThat(PasswordUtil.matches(CURRENT_AUTH_KEY, stored(user).getPassword())).isTrue();
    }

    @Test
    void changePassword_aWrapMissingItsSalt_isRefused() {
        var user = accountWithWraps("wrap-change-nosalt");

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/user/change-password",
                changeBody(CURRENT_AUTH_KEY, "{\"iv\":\"aXY=\",\"wrapped\":\"b2xk\"}")).code()).isEqualTo(400));

        assertThat(stored(user).getWrappedPrivateKey()).isEqualTo(OLD_WRAP);
    }

    // ── recovery-code reissue ───────────────────────────────────────────────

    @Test
    void reissue_rightPasswordAndARealWrap_isStored() {
        var user = accountWithWraps("wrap-reissue-ok");

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/user/recovery-key", reissueBody(CURRENT_AUTH_KEY, NEW_RECOVERY_WRAP)).code()).isEqualTo(204));

        assertThat(stored(user).getWrappedPrivateKeyRecovery()).isEqualTo(NEW_RECOVERY_WRAP);
    }

    @Test
    void reissue_wrongPasswordWithAPlaceholder_isCounted_andNothingChanges() throws Exception {
        var user = accountWithWraps("wrap-reissue-wrong");

        JavalinTest.test(appAs(user), (server, client) -> {
            var response = client.post("/api/user/recovery-key", reissueBody("C".repeat(43), PLACEHOLDER));

            assertThat(response.code()).isEqualTo(400);
            var body = new ObjectMapper().readTree(response.body().string());
            assertThat(body.get("error").asText()).isEqualTo("current_password_invalid");
            assertThat(body.get("remainingAttempts").asInt()).isEqualTo(4);
        });

        assertThat(stored(user).getWrappedPrivateKeyRecovery()).isEqualTo(OLD_RECOVERY_WRAP);
    }

    @Test
    void reissue_rightPasswordButAPlaceholder_isRefused_andTheStoredWrapSurvives() {
        var user = accountWithWraps("wrap-reissue-placeholder");

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/user/recovery-key", reissueBody(CURRENT_AUTH_KEY, PLACEHOLDER)).code()).isEqualTo(400));

        assertThat(stored(user).getWrappedPrivateKeyRecovery()).isEqualTo(OLD_RECOVERY_WRAP);
    }
}
