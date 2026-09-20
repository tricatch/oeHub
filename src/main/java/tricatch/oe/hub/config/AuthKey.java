package tricatch.oe.hub.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Server side of workspace mode's "the password never reaches the server" rule (see crypto.js and
 * auth-keys.js): the browser sends an authKey - 32 bytes derived from the password - in the field
 * that used to carry the password, and the server bcrypt-hashes and compares it like any secret.
 *
 * <p>The PBKDF2 salt behind that derivation has to be known before logging in, so it is served
 * from a public endpoint; unknown accounts get a deterministic decoy so the endpoint doesn't reveal
 * which ids exist.
 */
public final class AuthKey {

    // 32 bytes as unpadded base64url (crypto.js bufToBase64Url).
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int SALT_BYTES = 16;
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthKey() {}

    /**
     * Whether a value posted as a "password" has the exact shape of an authKey. In workspace mode
     * anything else means a client that still sends the typed password (a stale cached page, a
     * hand-rolled request), which must not be accepted for storage: the server would then hold
     * something that unwraps that account's private key.
     */
    public static boolean isWellFormed(String value) {
        return value != null && WELL_FORMED.matcher(value).matches();
    }

    /**
     * Whether {@code json} is a real wrapped-key record: an object with text {@code iv} and
     * {@code wrapped} fields (and {@code salt} for a password wrap, which the login KDF needs).
     * The change-password and recovery-reissue requests carry a placeholder instead when the
     * caller's current password did not unwrap the key (see auth-keys.js); such a request is turned
     * away after the password is checked, so a placeholder can never overwrite a stored wrap.
     */
    public static boolean isWellFormedWrap(String json, boolean withSalt) {
        if (json == null) return false;
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) return false;
            for (var field : withSalt ? new String[] {"salt", "iv", "wrapped"} : new String[] {"iv", "wrapped"}) {
                var value = node.get(field);
                if (value == null || !value.isTextual() || value.asText().isBlank()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The salt inside a stored wrapped-private-key record ({salt, iv, wrapped} JSON), or null. */
    public static String saltOf(String wrappedPrivateKeyJson) {
        if (wrappedPrivateKeyJson == null) return null;
        try {
            JsonNode salt = JSON.readTree(wrappedPrivateKeyJson).get("salt");
            return salt != null && salt.isTextual() ? salt.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A stable pseudo-random salt for an id that has no account: same id, same answer, always. */
    public static String decoySalt(byte[] secret, String userId) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] tag = mac.doFinal(("kdf-salt-decoy:" + (userId == null ? "" : userId)).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(Arrays.copyOf(tag, SALT_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
