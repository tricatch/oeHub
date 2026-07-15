package tricatch.oe.proxy.util;

import java.math.BigInteger;

public class OidUtil {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    // Odd multiplier -> bijective (collision-free) transform over the full 64-bit range,
    // so consecutive user_no values scramble into unrelated-looking output.
    private static final long MULTIPLIER = 0x9E3779B97F4A7C15L;

    // Modular multiplicative inverse of MULTIPLIER mod 2^64, used by decode() to undo the scramble.
    private static final long INVERSE = BigInteger.valueOf(MULTIPLIER)
            .mod(BigInteger.ONE.shiftLeft(64))
            .modInverse(BigInteger.ONE.shiftLeft(64))
            .longValue();

    // 26^14 > 2^64, so 14 letters is enough to cover every possible scrambled value uniquely.
    private static final int LENGTH = 14;

    // Encodes a user_no into a fixed-length, uppercase-letter-only opaque OID string.
    public static String encode(long userNo) {
        long scrambled = userNo * MULTIPLIER;
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            int digit = (int) Long.remainderUnsigned(scrambled, ALPHABET.length);
            out[i] = ALPHABET[digit];
            scrambled = Long.divideUnsigned(scrambled, ALPHABET.length);
        }
        return new String(out);
    }

    // Reverses encode(): recovers the original user_no, or null if oid isn't a well-formed OID string.
    public static Long decode(String oid) {
        if (oid == null || oid.length() != LENGTH) return null;
        long scrambled = 0L;
        for (int i = 0; i < LENGTH; i++) {
            int digit = oid.charAt(i) - 'A';
            if (digit < 0 || digit >= ALPHABET.length) return null;
            scrambled = scrambled * ALPHABET.length + digit;
        }
        return scrambled * INVERSE;
    }
}
