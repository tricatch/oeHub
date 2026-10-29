package tricatch.oe.hub.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdRulesTest {

    @Test
    void theGeneratedSystemAccountId_isItselfReserved() {
        assertThat(UserIdRules.wsSystemUserId(7)).isEqualTo("__wss_7");
        assertThat(UserIdRules.isReserved(UserIdRules.wsSystemUserId(7))).isTrue();
    }

    @Test
    void onlyAnIdStartingWithTheReservedPrefix_isReserved() {
        assertThat(UserIdRules.isReserved("__anything")).isTrue();
        assertThat(UserIdRules.isReserved("_single")).isFalse();
        assertThat(UserIdRules.isReserved("a__b")).isFalse();
        assertThat(UserIdRules.isReserved("alice")).isFalse();
        assertThat(UserIdRules.isReserved(null)).isFalse();
        assertThat(UserIdRules.isReserved("")).isFalse();
    }
}
