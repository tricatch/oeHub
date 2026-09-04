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
    private static final int LENGTH = (ID_BYTES + TAG_BYTES) * 2; // hex-encoded
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static volatile SecretKeySpec macKey;

    public static void init(byte[] secret) {
        macKey = new SecretKeySpec(secret, HMAC_ALG);
    }

    public static String encode(long userNo) {
        byte[] idBytes = longToBytes(userNo);
        byte[] tag = hmac(idBytes);
        byte[] out = new byte[ID_BYTES + TAG_BYTES];
        System.arraycopy(idBytes, 0, out, 0, ID_BYTES);
        System.arraycopy(tag, 0, out, ID_BYTES, TAG_BYTES);
        return toHex(out);
    }

    // Returns the user_no only if oid is well-formed AND its HMAC tag verifies against the
    // server secret — an unrecognized or tampered oid (e.g. a guessed user_no with no valid
    // tag) returns null rather than being trusted.
    public static Long decode(String oid) {
        if (oid == null || oid.length() != LENGTH) return null;
        byte[] raw;
        try {
            raw = fromHex(oid);
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

    private static String toHex(byte[] bytes) {
        var out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    private static byte[] fromHex(String s) {
        var out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex in OID");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
