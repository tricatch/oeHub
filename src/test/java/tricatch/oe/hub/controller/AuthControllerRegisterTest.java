package tricatch.oe.hub.controller;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;

import java.sql.SQLException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerRegisterTest extends MapperTestBase {

    // ── registration throttle ───────────────────────────────────────────────

    @Test
    void fiveRegistrationsFromOneAddress_lockThatAddress_butNotOthers() {
        var auth = new AuthController(null, null);

        for (int i = 0; i < 4; i++) auth.recordRegisterAttempt("203.0.113.1");
        assertThat(auth.isRegisterLocked("203.0.113.1")).isFalse();

        auth.recordRegisterAttempt("203.0.113.1");
        assertThat(auth.isRegisterLocked("203.0.113.1")).isTrue();
        assertThat(auth.isRegisterLocked("203.0.113.2")).isFalse();
    }

    @Test
    void theThrottleDoesNotTrackAnUnboundedNumberOfAddresses() {
        var auth = new AuthController(null, null);

        // A client cycling through addresses must not be able to grow memory without limit.
        IntStream.range(0, 30_000).forEach(i -> auth.recordRegisterAttempt("2001:db8::" + Integer.toHexString(i)));

        assertThat(auth.trackedRegisterIps()).isLessThanOrEqualTo(10_000);
    }

    // ── racing sign-ups ─────────────────────────────────────────────────────

    @Test
    void uniqueViolationError_tellsTheWorkspaceNameFromTheUserId() {
        var wsName = new SQLException(
            "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_2 ON PUBLIC.HUB_WS(WS_NAME NULLS FIRST) VALUES 1\"",
            "23505");
        var userId = new SQLException(
            "Unique index or primary key violation: \"PUBLIC.CONSTRAINT_INDEX_3 ON PUBLIC.HUB_USR(USER_ID NULLS FIRST) VALUES 1\"",
            "23505");

        assertThat(AuthController.uniqueViolationError(wsName)).isEqualTo("auth.error.wsname.exists");
        assertThat(AuthController.uniqueViolationError(userId)).isEqualTo("auth.error.userid.exists");
        // Found through a cause chain, as MyBatis wraps it.
        assertThat(AuthController.uniqueViolationError(new RuntimeException("wrapped", userId)))
            .isEqualTo("auth.error.userid.exists");
    }

    @Test
    void uniqueViolationError_ignoresEverythingElse() {
        assertThat(AuthController.uniqueViolationError(new SQLException("boom", "42000"))).isNull();
        assertThat(AuthController.uniqueViolationError(new RuntimeException("not sql"))).isNull();
        assertThat(AuthController.uniqueViolationError(null)).isNull();
    }

    /** A sign-up that lost the race: the existence check saw no such user (as it would have just
     *  before the winner committed), so the insert itself is what hits the UNIQUE constraint. */
    @Test
    void aSignUpThatLosesTheRace_getsTheNormalError_notAServerError() {
        insertUser("raceUser");
        var auth = new AuthController(FACTORY, null) {
            @Override HubUser findUser(String userId) { return null; }
        };
        var app = Javalin.create(config -> {
            // Echo the error key the register page would be rendered with.
            config.fileRenderer((path, model, ctx) -> String.valueOf(model.get("error")));
            config.routes.post("/register", auth::processRegister);
        });

        JavalinTest.test(app, (server, client) -> {
            var form = "userId=raceUser&password=correct-horse&confirmPassword=correct-horse"
                + "&publicKey=k&wrappedPrivateKey=k&wrappedPrivateKeyRecovery=k&recoveryVerifier=k";
            var response = client.post("/register", form,
                req -> req.header("Content-Type", "application/x-www-form-urlencoded"));

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("auth.error.userid.exists");
        });
    }

    // ── reserved user ids ───────────────────────────────────────────────────

    /** Ids starting with "__" are the ones oeHub generates (a workspace's system account); a
     *  sign-up must not be able to take one before that workspace exists. */
    @Test
    void aSignUpUnderAReservedId_isRefused() {
        var auth = new AuthController(FACTORY, null);
        var app = Javalin.create(config -> {
            config.fileRenderer((path, model, ctx) -> String.valueOf(model.get("error")));
            config.routes.post("/register", auth::processRegister);
        });

        JavalinTest.test(app, (server, client) -> {
            var form = "userId=__wss_500&password=correct-horse&confirmPassword=correct-horse"
                + "&publicKey=k&wrappedPrivateKey=k&wrappedPrivateKeyRecovery=k&recoveryVerifier=k";
            var response = client.post("/register", form,
                req -> req.header("Content-Type", "application/x-www-form-urlencoded"));

            assertThat(response.body().string()).isEqualTo("auth.error.userid.reserved");
        });
    }
}
