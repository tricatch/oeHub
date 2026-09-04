package tricatch.oe.proxy.util;

import org.junit.jupiter.api.Test;
import tricatch.oe.mapper.MapperTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OidUtil is the unforgeable-ID scheme for X-OeHub-Oid on the reverse proxy's public,
 * unauthenticated data plane (see its class-level doc) - had no test coverage before this file.
 * Extends MapperTestBase purely so its static initializer (which calls ReverseProxyServer.init(),
 * which calls OidUtil.init() with a real secret) has definitely run before these tests execute,
 * regardless of which order the test runner picks - these tests never touch the DB otherwise.
 */
class OidUtilTest extends MapperTestBase {

    @Test
    void encodeThenDecode_returnsOriginalUserNo() {
        for (long userNo : new long[]{0L, 1L, 42L, Long.MAX_VALUE}) {
            var oid = OidUtil.encode(userNo);
            assertThat(OidUtil.decode(oid)).isEqualTo(userNo);
        }
    }

    @Test
    void encode_differentUserNos_produceDifferentOids() {
        assertThat(OidUtil.encode(1L)).isNotEqualTo(OidUtil.encode(2L));
    }

    @Test
    void decode_tamperedIdPortion_isRejected() {
        var oid = OidUtil.encode(123L);
        // Flip the first hex character (part of the id, not the tag) - the tag no longer
        // authenticates the new id.
        var tampered = flipHexChar(oid, 0);
        assertThat(OidUtil.decode(tampered)).isNull();
    }

    @Test
    void decode_tamperedTagPortion_isRejected() {
        var oid = OidUtil.encode(123L);
        // Id is 8 bytes = 16 hex chars; the tag starts right after.
        var tampered = flipHexChar(oid, 16);
        assertThat(OidUtil.decode(tampered)).isNull();
    }

    @Test
    void decode_forgedOidWithGuessedIdAndZeroTag_isRejected() {
        // Simulates an attacker who knows/guesses a target user_no but not the server secret,
        // so cannot produce a valid HMAC tag - this is exactly the attack OidUtil.decode() must
        // defeat per its class-level contract.
        var idHex = "0000000000000001"; // user_no = 1, as 8 bytes hex
        var forged = idHex + "0000000000000000"; // zero tag
        assertThat(OidUtil.decode(forged)).isNull();
    }

    @Test
    void decode_wrongLength_isRejected() {
        var oid = OidUtil.encode(123L);
        assertThat(OidUtil.decode(oid.substring(0, oid.length() - 2))).isNull();
        assertThat(OidUtil.decode(oid + "00")).isNull();
    }

    @Test
    void decode_nonHexCharacters_isRejected() {
        var oid = OidUtil.encode(123L);
        var withNonHex = "zz" + oid.substring(2);
        assertThat(OidUtil.decode(withNonHex)).isNull();
    }

    @Test
    void decode_nullOrEmpty_isRejected() {
        assertThat(OidUtil.decode(null)).isNull();
        assertThat(OidUtil.decode("")).isNull();
    }

    private static String flipHexChar(String hex, int index) {
        char c = hex.charAt(index);
        char flipped = c == '0' ? '1' : '0';
        return hex.substring(0, index) + flipped + hex.substring(index + 1);
    }
}
