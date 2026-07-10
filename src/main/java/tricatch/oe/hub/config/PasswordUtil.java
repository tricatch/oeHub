package tricatch.oe.hub.config;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

import java.security.SecureRandom;

public class PasswordUtil {

    private static final int COST = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return OpenBSDBCrypt.generate(password.toCharArray(), salt, COST);
    }

    public static boolean matches(String raw, String hashed) {
        return OpenBSDBCrypt.checkPassword(hashed, raw.toCharArray());
    }
}
