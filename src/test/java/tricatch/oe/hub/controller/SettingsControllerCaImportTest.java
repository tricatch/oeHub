package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Importing a CA replaces the one the proxies read at startup, so a bad upload must be refused
// before anything of the current CA is touched.
class SettingsControllerCaImportTest {

    @TempDir
    Path home;

    private String previousHome;
    private final SettingsController controller = new SettingsController(null, new ObjectMapper());

    @BeforeEach
    void pointAtScratchHome() {
        previousHome = System.getProperty("oe.home");
        System.setProperty("oe.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("oe.home");
        else System.setProperty("oe.home", previousHome);
    }

    private record Pair(byte[] cert, byte[] key) {}

    /** Generates a CA into the live directory and returns its files, leaving that CA installed. */
    private Pair generateInstalled(String name) throws Exception {
        controller.generateCaToDir(name);
        return new Pair(Files.readAllBytes(SettingsController.caCertPath()), Files.readAllBytes(SettingsController.caKeyPath()));
    }

    private Pair installed() throws Exception {
        return new Pair(Files.readAllBytes(SettingsController.caCertPath()), Files.readAllBytes(SettingsController.caKeyPath()));
    }

    private static void assertSame(Pair actual, Pair expected) {
        assertThat(Arrays.equals(actual.cert(), expected.cert())).as("certificate unchanged").isTrue();
        assertThat(Arrays.equals(actual.key(), expected.key())).as("private key unchanged").isTrue();
    }

    @Test
    void aMatchingPair_replacesTheInstalledCa() throws Exception {
        var first = generateInstalled("First CA");
        var second = generateInstalled("Second CA"); // overwrites first; keep its bytes for the import below
        controller.importCaToDir(first.cert(), first.key());

        assertSame(installed(), first);
        assertThat(second.cert()).isNotEqualTo(first.cert());
    }

    @Test
    void garbageUpload_isRefused_andTheInstalledCaSurvives() throws Exception {
        var current = generateInstalled("Current CA");

        assertThatThrownBy(() -> controller.importCaToDir("not a certificate".getBytes(), "not a key".getBytes()))
            .isInstanceOf(Exception.class);

        assertSame(installed(), current);
    }

    @Test
    void aCertificateWithSomeoneElsesKey_isRefused_andTheInstalledCaSurvives() throws Exception {
        var other = generateInstalled("Other CA");
        var current = generateInstalled("Current CA");

        assertThatThrownBy(() -> controller.importCaToDir(other.cert(), current.key()))
            .isInstanceOf(Exception.class);

        assertSame(installed(), current);
    }

    @Test
    void aRefusedUpload_leavesNoScratchFilesBehind() throws Exception {
        generateInstalled("Current CA");

        assertThatThrownBy(() -> controller.importCaToDir(new byte[0], new byte[0])).isInstanceOf(Exception.class);

        try (var entries = Files.list(SettingsController.caDir())) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("ca.cer", "ca.pfx");
        }
    }
}
