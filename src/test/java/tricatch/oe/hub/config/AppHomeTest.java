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
}
