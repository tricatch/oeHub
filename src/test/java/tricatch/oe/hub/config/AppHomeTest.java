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

    // isWorkspaceMode() reads a live system property (no caching), so each test sets it and
    // restores the original value afterward rather than leaving JVM-global state behind for
    // other tests.

    @Test
    void isWorkspaceMode_unset_defaultsToSelfHostedFalse() {
        var original = System.getProperty("oe.mode");
        System.clearProperty("oe.mode");
        try {
            assertThat(AppHome.isWorkspaceMode()).isFalse();
        } finally {
            if (original != null) System.setProperty("oe.mode", original);
        }
    }

    @Test
    void isWorkspaceMode_explicitSelfHosted_isFalse() {
        var original = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "self-hosted");
        try {
            assertThat(AppHome.isWorkspaceMode()).isFalse();
        } finally {
            if (original != null) System.setProperty("oe.mode", original); else System.clearProperty("oe.mode");
        }
    }

    @Test
    void isWorkspaceMode_workspace_isTrue() {
        var original = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "workspace");
        try {
            assertThat(AppHome.isWorkspaceMode()).isTrue();
        } finally {
            if (original != null) System.setProperty("oe.mode", original); else System.clearProperty("oe.mode");
        }
    }

    @Test
    void isWorkspaceMode_unrecognizedValue_fallsBackToSelfHostedFalse() {
        // Any value other than exactly "workspace" is self-hosted - a typo like "Workspace" or
        // "cloud" must not silently disable oeProxy.
        var original = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "cloud");
        try {
            assertThat(AppHome.isWorkspaceMode()).isFalse();
        } finally {
            if (original != null) System.setProperty("oe.mode", original); else System.clearProperty("oe.mode");
        }
    }
}
