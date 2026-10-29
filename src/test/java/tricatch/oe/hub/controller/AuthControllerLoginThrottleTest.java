package tricatch.oe.hub.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerLoginThrottleTest {

    private AuthController auth;

    @BeforeEach
    void setUp() {
        // The throttle never touches the session factory or JWT service.
        auth = new AuthController(null, null);
    }

    private void fail(String userId, int times) {
        for (int i = 0; i < times; i++) auth.recordLoginFailure(userId);
    }

    @Test
    void fiveFailuresOnOneAccount_lockThatAccount() {
        fail("victim", 4);
        assertThat(auth.isLoginLocked("victim")).isFalse();
        fail("victim", 1);
        assertThat(auth.isLoginLocked("victim")).isTrue();
    }

    @Test
    void accountLock_doesNotAffectOtherAccounts() {
        fail("victim", 5);
        assertThat(auth.isLoginLocked("victim")).isTrue();
        assertThat(auth.isLoginLocked("someone-else")).isFalse();
    }

    @Test
    void successfulLoginAsAnotherAccount_doesNotResetCounter() {
        // The bypass this guards against: guess at the victim, log in as your own account to
        // wipe the counter, repeat - the lockout must still fire on the 5th failure.
        for (int round = 0; round < 3; round++) {
            fail("victim", 1);
            auth.recordLoginSuccess("attacker");
        }
        fail("victim", 2);
        assertThat(auth.isLoginLocked("victim")).isTrue();
    }

    @Test
    void successfulLogin_clearsThatAccountsOwnFailures() {
        fail("alice", 3);
        auth.recordLoginSuccess("alice");
        fail("alice", 4);
        assertThat(auth.isLoginLocked("alice")).isFalse();
        fail("alice", 1);
        assertThat(auth.isLoginLocked("alice")).isTrue();
    }

    @Test
    void unknownUserIds_areThrottledLikeRealOnes() {
        // Lock behavior must not reveal whether an account exists.
        fail("no-such-account", 5);
        assertThat(auth.isLoginLocked("no-such-account")).isTrue();
    }

    @Test
    void overlongUserIds_shareAKeyOnlyByTheirFirst64Chars() {
        var prefix = "x".repeat(64);
        fail(prefix + "a", 3);
        fail(prefix + "b", 2);
        assertThat(auth.isLoginLocked(prefix)).isTrue();
    }

    @Test
    void nullUserId_isThrottledWithoutError() {
        fail(null, 5);
        assertThat(auth.isLoginLocked(null)).isTrue();
    }
}
