package tricatch.oe.hub.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class AppHomeTest {

    @Test
    void restrictToOwner_setsOwnerOnlyPermissions_onPosixFilesystems(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX file permissions not supported on this filesystem");

        var file = dir.resolve("secret.txt");
        Files.writeString(file, "shh");

        AppHome.restrictToOwner(file);

        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void restrictToOwner_neverThrows_regardlessOfFilesystem(@TempDir Path dir) throws Exception {
        var file = dir.resolve("secret.txt");
        Files.writeString(file, "shh");

        // Must not throw on any filesystem, including ones without POSIX permission support
        // (e.g. NTFS) - callers write the secret file first and call this right after, with no
        // try/catch of their own.
        AppHome.restrictToOwner(file);
    }

    @Test
    void restrictToOwner_missingFile_isSilentlyIgnored(@TempDir Path dir) {
        var missing = dir.resolve("does-not-exist.txt");

        AppHome.restrictToOwner(missing); // IOException from setPosixFilePermissions is swallowed
    }

    // isGroupMode() reads a live system property (no caching), so each test sets it and restores
    // the original value afterward rather than leaving JVM-global state behind for other tests.

    @Test
    void isGroupMode_unset_defaultsToStandaloneFalse() {
        var original = System.getProperty("oe.mode");
        System.clearProperty("oe.mode");
        try {
            assertThat(AppHome.isGroupMode()).isFalse();
        } finally {
            if (original != null) System.setProperty("oe.mode", original);
        }
    }

    @Test
    void isGroupMode_explicitStandalone_isFalse() {
        var original = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "standalone");
        try {
            assertThat(AppHome.isGroupMode()).isFalse();
        } finally {
            if (original != null) System.setProperty("oe.mode", original); else System.clearProperty("oe.mode");
        }
    }

    @Test
    void isGroupMode_group_isTrue() {
        var original = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "group");
        try {
            assertThat(AppHome.isGroupMode()).isTrue();
        } finally {
            if (original != null) System.setProperty("oe.mode", original); else System.clearProperty("oe.mode");
        }
    }

    @Test
    void isGroupMode_unrecognizedValue_fallsBackToStandaloneFalse() {
        // Any value other than exactly "group" is standalone - a typo like "Group" or "cloud"
        // must not silently disable oeProxy.
        var original = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "cloud");
        try {
            assertThat(AppHome.isGroupMode()).isFalse();
        } finally {
            if (original != null) System.setProperty("oe.mode", original); else System.clearProperty("oe.mode");
        }
    }
}
