package tricatch.oe.hub.config;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class AppHome {

    // -Doe.home, when set, IS the oeHub data directory (used as-is, no "oeHub" appended) —
    // e.g. `sudo java -jar oeHub.jar -Doe.home=/home/alice/oeHub` to keep using that exact
    // path instead of /root/oeHub. Without it, defaults to <user.home>/oeHub.
    public static Path oeHubDir() {
        var override = System.getProperty("oe.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), "oeHub");
    }

    // -Doe.mode=workspace runs oeHub as a multi-tenant cloud service (oeHosts only, oeProxy fully
    // disabled - see cloudGroupService design doc §2.6). Any other value, or no value at all,
    // is "standalone" (oeHosts + oeProxy, the app's existing single-workspace behavior) - the
    // default, so existing installs keep working unmodified with no flag.
    public static boolean isWorkspaceMode() {
        return "workspace".equals(System.getProperty("oe.mode", "standalone"));
    }

    // Workspace mode gets a distinct filename so an operator can switch -Doe.mode back and forth
    // during testing without the two modes' databases colliding on the same file, and so the two
    // are visually distinguishable on disk later.
    public static String dbFileName() {
        return isWorkspaceMode() ? "oeHub-h2-ws" : "oeHub-h2";
    }

    // -Doe.db.file, when set, IS the H2 database file path (no .mv.db extension, same convention
    // as H2's own file-name argument), used as-is instead of <home>/data/<dbFileName()>. Lets an
    // operator point at a specific database file - e.g. a restored backup, or a location on a
    // different volume - independent of -Doe.home, which relocates the rest of the app's data
    // (config, CA) too. Without it, defaults to dbDataDir().resolve(dbFileName()).
    public static Path dbFilePath() {
        var override = System.getProperty("oe.db.file");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return dbDataDir().resolve(dbFileName());
    }

    // The directory holding the H2 database file, its generated password file, and scheduled
    // backups - everything that must travel together with the database itself. Normally
    // <home>/data, but when -Doe.db.file points elsewhere, that file's own parent directory is
    // used instead so these companions stay next to the database they belong to rather than being
    // split across -Doe.home and -Doe.db.file.
    public static Path dbDataDir() {
        var override = System.getProperty("oe.db.file");
        if (override != null && !override.isBlank()) {
            var parent = Path.of(override).toAbsolutePath().getParent();
            return parent != null ? parent : Path.of(".");
        }
        return oeHubDir().resolve("data");
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
