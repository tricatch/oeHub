package tricatch.oe.hub.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// Personal API tokens (HUB_API_TOKEN). Unlike PasswordUtil's bcrypt - tuned to slow down guessing
// a low-entropy human password - the token itself already carries 256 bits of random entropy, so a
// fast SHA-256 hash is enough, and unlike bcrypt it lets HubApiTokenMapper look a token up by exact
// hash match instead of re-hashing every stored row to find a candidate.
public class ApiTokenUtil {

    private static final String PREFIX = "oeh_";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record GeneratedToken(String token, String tokenHash) {
    }

    public static GeneratedToken generate() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedToken(token, hash(token));
    }

    public static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // SHA-256 is always available on the JVM
        }
    }
}
