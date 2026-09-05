package tricatch.oe.proxy.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

// Encodes/decodes the opaque OID that identifies a HUB_USR.user_no on the reverse proxy's
// public, unauthenticated data plane (the X-OeHub-Oid header — see ReverseProxyServer.getVirtualHosts).
// This MUST be unforgeable: init() is called once at startup with a server-only secret
// (ReverseProxyServer loads/generates it from HUB_CONF, mirroring JwtService's key handling),
// and every decoded value is authenticated with a truncated HMAC tag before it's trusted.
// A caller who doesn't control the secret cannot produce an oid that decode() will accept for
// any user_no other than one it has already legitimately seen.
public class OidUtil {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final int TAG_BYTES = 8;
    private static final int ID_BYTES = 8;
    private static final int RAW_BYTES = ID_BYTES + TAG_BYTES;
    // Base32 (RFC 4648, unpadded): 5 bits/char instead of hex's 4, so the header value shrinks
    // from 32 to 26 chars for the same 16-byte payload while staying case-insensitive and safe
    // to put straight into an HTTP header with no escaping.
    private static final int LENGTH = (RAW_BYTES * 8 + 4) / 5; // = 26
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] BASE32_LOOKUP = buildBase32Lookup();

    private static volatile SecretKeySpec macKey;

    public static void init(byte[] secret) {
        macKey = new SecretKeySpec(secret, HMAC_ALG);
    }

    public static String encode(long userNo) {
        byte[] idBytes = longToBytes(userNo);
        byte[] tag = hmac(idBytes);
        byte[] out = new byte[RAW_BYTES];
        System.arraycopy(idBytes, 0, out, 0, ID_BYTES);
        System.arraycopy(tag, 0, out, ID_BYTES, TAG_BYTES);
        return toBase32(out);
    }

    // Returns the user_no only if oid is well-formed AND its HMAC tag verifies against the
    // server secret — an unrecognized or tampered oid (e.g. a guessed user_no with no valid
    // tag) returns null rather than being trusted.
    public static Long decode(String oid) {
        if (oid == null || oid.length() != LENGTH) return null;
        byte[] raw;
        try {
            raw = fromBase32(oid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] idBytes = Arrays.copyOfRange(raw, 0, ID_BYTES);
        byte[] tag = Arrays.copyOfRange(raw, ID_BYTES, raw.length);
        byte[] expected = hmac(idBytes);
        if (!MessageDigest.isEqual(tag, expected)) return null;
        return bytesToLong(idBytes);
    }

    private static byte[] hmac(byte[] data) {
        if (macKey == null) throw new IllegalStateException("OidUtil.init() was not called");
        try {
            var mac = Mac.getInstance(HMAC_ALG);
            mac.init(macKey);
            return Arrays.copyOf(mac.doFinal(data), TAG_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] longToBytes(long v) {
        var b = new byte[ID_BYTES];
        for (int i = ID_BYTES - 1; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return b;
    }

    private static long bytesToLong(byte[] b) {
        long v = 0;
        for (int i = 0; i < ID_BYTES; i++) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    private static int[] buildBase32Lookup() {
        var table = new int[128];
        Arrays.fill(table, -1);
        for (int i = 0; i < BASE32_ALPHABET.length; i++) table[BASE32_ALPHABET[i]] = i;
        return table;
    }

    /** Package-visible (not just private) so tests can build forged raw payloads directly. */
    static String toBase32(byte[] data) {
        var sb = new StringBuilder(LENGTH);
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET[(buffer >> bitsLeft) & 0x1F]);
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }

    // Caller has already checked s.length() == LENGTH, so this always yields exactly RAW_BYTES
    // bytes, with the last (LENGTH*5 - RAW_BYTES*8) bits of the final character discarded as
    // padding, mirroring what toBase32() left zero-filled there.
    private static byte[] fromBase32(String s) {
        var out = new byte[RAW_BYTES];
        int buffer = 0, bitsLeft = 0, index = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            int val = c < BASE32_LOOKUP.length ? BASE32_LOOKUP[c] : -1;
            if (val < 0) throw new IllegalArgumentException("Invalid base32 character in OID");
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[index++] = (byte) ((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out;
    }
}
