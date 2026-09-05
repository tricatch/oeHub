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
        // Flip the first base32 character (part of the id, not the tag) - the tag no longer
        // authenticates the new id.
        var tampered = flipBase32Char(oid, 0);
        assertThat(OidUtil.decode(tampered)).isNull();
    }

    @Test
    void decode_tamperedTagPortion_isRejected() {
        var oid = OidUtil.encode(123L);
        // Id occupies bits 0-63 of the 128-bit payload; char 13 covers bits 65-69, comfortably
        // inside the tag (bits 64-127), so this can't be mistaken for an id-portion tamper.
        var tampered = flipBase32Char(oid, 13);
        assertThat(OidUtil.decode(tampered)).isNull();
    }

    @Test
    void decode_forgedOidWithGuessedIdAndZeroTag_isRejected() {
        // Simulates an attacker who knows/guesses a target user_no but not the server secret,
        // so cannot produce a valid HMAC tag - this is exactly the attack OidUtil.decode() must
        // defeat per its class-level contract.
        var raw = new byte[16];
        raw[7] = 1; // id bytes = user_no 1, big-endian; tag bytes left zero
        var forged = OidUtil.toBase32(raw);
        assertThat(OidUtil.decode(forged)).isNull();
    }

    @Test
    void decode_wrongLength_isRejected() {
        var oid = OidUtil.encode(123L);
        assertThat(OidUtil.decode(oid.substring(0, oid.length() - 2))).isNull();
        assertThat(OidUtil.decode(oid + "AA")).isNull();
    }

    @Test
    void decode_nonBase32Characters_isRejected() {
        var oid = OidUtil.encode(123L);
        // '0' and '1' aren't in the RFC 4648 base32 alphabet (reserved to avoid confusion with O/I).
        var withNonBase32 = "01" + oid.substring(2);
        assertThat(OidUtil.decode(withNonBase32)).isNull();
    }

    @Test
    void decode_nullOrEmpty_isRejected() {
        assertThat(OidUtil.decode(null)).isNull();
        assertThat(OidUtil.decode("")).isNull();
    }

    @Test
    void encode_producesTwentySixCharBase32Oid() {
        // 16 raw bytes (8 id + 8 HMAC tag) at 5 bits/char = ceil(128/5) = 26 chars, vs. 32 for
        // the previous hex encoding - shorter header value, same unforgeability guarantee.
        var oid = OidUtil.encode(123L);
        assertThat(oid).hasSize(26).matches("[A-Z2-7]+");
    }

    private static String flipBase32Char(String oid, int index) {
        char c = oid.charAt(index);
        char flipped = c == 'A' ? 'B' : 'A';
        return oid.substring(0, index) + flipped + oid.substring(index + 1);
    }
}
