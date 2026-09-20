package tricatch.oe.hub.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AuthKeyTest {

    @Test
    void wellFormed_isExactly43Base64UrlChars() {
        assertThat(AuthKey.isWellFormed("A".repeat(43))).isTrue();
        assertThat(AuthKey.isWellFormed("abcDEF0123456789_-abcDEF0123456789_-abcDEF0")).isTrue();
        assertThat(AuthKey.isWellFormed("A".repeat(42))).isFalse();
        assertThat(AuthKey.isWellFormed("A".repeat(44))).isFalse();
        assertThat(AuthKey.isWellFormed("A".repeat(42) + "=")).isFalse(); // padded base64
        assertThat(AuthKey.isWellFormed("A".repeat(42) + "+")).isFalse(); // standard, not url, alphabet
        assertThat(AuthKey.isWellFormed("A".repeat(42) + " ")).isFalse();
        assertThat(AuthKey.isWellFormed("")).isFalse();
        assertThat(AuthKey.isWellFormed(null)).isFalse();
        // A typical typed password is not an authKey.
        assertThat(AuthKey.isWellFormed("correct horse battery staple")).isFalse();
        assertThat(AuthKey.isWellFormed("Password123!")).isFalse();
    }

    @Test
    void saltOf_readsTheSaltOutOfAWrappedPrivateKeyRecord() {
        assertThat(AuthKey.saltOf("{\"salt\":\"c2FsdA==\",\"iv\":\"x\",\"wrapped\":\"y\"}")).isEqualTo("c2FsdA==");
        assertThat(AuthKey.saltOf("{\"iv\":\"x\",\"wrapped\":\"y\"}")).isNull();
        assertThat(AuthKey.saltOf("{\"salt\":123}")).isNull();
        assertThat(AuthKey.saltOf("self-hosted")).isNull();
        assertThat(AuthKey.saltOf("")).isNull();
        assertThat(AuthKey.saltOf(null)).isNull();
    }

    @Test
    void decoySalt_isStablePerIdAndSecret_andShapedLikeARealSalt() {
        var secret = new byte[32];
        secret[0] = 1;
        var other = new byte[32];
        other[0] = 2;

        var salt = AuthKey.decoySalt(secret, "alice");
        assertThat(AuthKey.decoySalt(secret, "alice")).isEqualTo(salt);
        assertThat(AuthKey.decoySalt(secret, "bob")).isNotEqualTo(salt);
        assertThat(AuthKey.decoySalt(other, "alice")).isNotEqualTo(salt);
        // 16 bytes, standard base64 - the same encoding crypto.js writes for a real salt.
        assertThat(Base64.getDecoder().decode(salt)).hasSize(16);
        assertThat(AuthKey.decoySalt(secret, null)).isEqualTo(AuthKey.decoySalt(secret, ""));
    }

    @Test
    void wellFormedWrap_needsRealTextFields_andASaltOnlyForAPasswordWrap() {
        assertThat(AuthKey.isWellFormedWrap("{\"salt\":\"s\",\"iv\":\"i\",\"wrapped\":\"w\"}", true)).isTrue();
        assertThat(AuthKey.isWellFormedWrap("{\"iv\":\"i\",\"wrapped\":\"w\"}", false)).isTrue();
        assertThat(AuthKey.isWellFormedWrap("{\"iv\":\"i\",\"wrapped\":\"w\"}", true)).isFalse(); // no salt
        assertThat(AuthKey.isWellFormedWrap("{}", false)).isFalse(); // the browser's placeholder
        assertThat(AuthKey.isWellFormedWrap("{}", true)).isFalse();
        assertThat(AuthKey.isWellFormedWrap("{\"iv\":\"i\",\"wrapped\":\"\"}", false)).isFalse();
        assertThat(AuthKey.isWellFormedWrap("{\"iv\":1,\"wrapped\":\"w\"}", false)).isFalse();
        assertThat(AuthKey.isWellFormedWrap("[]", false)).isFalse();
        assertThat(AuthKey.isWellFormedWrap("self-hosted", false)).isFalse();
        assertThat(AuthKey.isWellFormedWrap("", false)).isFalse();
        assertThat(AuthKey.isWellFormedWrap(null, false)).isFalse();
    }
}
