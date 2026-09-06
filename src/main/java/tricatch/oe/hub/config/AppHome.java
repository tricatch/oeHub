package tricatch.oe.hub.config;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class AppHome {

    // -Dhome, when set, IS the oeHub data directory (used as-is, no "oeHub" appended) —
    // e.g. `sudo java -jar oeHub.jar -Dhome=/home/alice/oeHub` to keep using that exact
    // path instead of /root/oeHub. Without it, defaults to <user.home>/oeHub.
    public static Path oeHubDir() {
        var override = System.getProperty("home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), "oeHub");
    }

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /**
     * Restricts a just-written secret file (DB password, CA private key, etc.) to owner-only
     * read/write. On a shared Linux/Mac host another local account could otherwise read these
     * files via default umask permissions. No-op (silently) on filesystems without POSIX
     * permission support, e.g. Windows - NTFS ACLs already default to the current user there.
     */
    public static void restrictToOwner(Path file) {
        try {
            java.nio.file.Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
        }
    }
}
