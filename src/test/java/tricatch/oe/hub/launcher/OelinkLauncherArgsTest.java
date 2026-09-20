package tricatch.oe.hub.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The oelink launchers (macOS bash, Windows PowerShell) turn the base64 payload of an oelink:// link
 * into Chrome's command line. The link can be crafted by anyone, so each launcher splits it into
 * words without a shell, refuses any word that is not a plain allowed flag or start URL, and starts
 * nothing if one is refused. These tests pull the real splitting/validation functions out of the
 * launcher sources and run them under the real interpreters (skipped where one is not installed).
 */
class OelinkLauncherArgsTest {

    private static final String DATA_URL =
        "data:text/html,<script>setTimeout(()=>location.replace('https://x.com/a?b=1'),1000)</script>";

    private static final Map<String, Boolean> CASES = new java.util.LinkedHashMap<>();
    static {
        // allowed
        CASES.put("--oelink=123 --disable-sync --incognito --user-data-dir=\"C:/Users/a b/oe-chrome\""
            + " --user-agent=\"Mozilla/5.0 (X11; Linux) AppleWebKit\"", true);
        CASES.put("--host-resolver-rules=\"MAP a.com 1.2.3.4,MAP b.com 5.6.7.8\" https://example.com", true);
        CASES.put("--proxy-server=\"10.0.0.1:8899\" about:blank", true);
        CASES.put(DATA_URL, true);
        // refused: programs, sandbox, debug channel, traffic redirection
        CASES.put("--host-resolver-rules=\"MAP a 1.1.1.1\" --renderer-cmd-prefix=/bin/sh", false);
        CASES.put("--gpu-launcher=\"touch /tmp/pwn\"", false);
        CASES.put("--Renderer-Cmd-Prefix=x", false);
        CASES.put("--remote-debugging-port=9222", false);
        CASES.put("--no-sandbox", false);
        CASES.put("--ignore-certificate-errors-spki-list=abc", false);
        CASES.put("--proxy-pac-url=http://evil/x.pac", false);
        CASES.put("--load-extension=/tmp/ext", false);
        // refused: not a flag or an allowed URL at all
        CASES.put("$(touch /tmp/pwn)", false);
        CASES.put("; touch /tmp/pwn", false);
        CASES.put("file:///etc/passwd", false);
        CASES.put("javascript:alert(1)", false);
        CASES.put("--user-data-dir=\"abc", false); // unbalanced quote
    }

    private static String between(String source, String from, String to) {
        int a = source.indexOf(from);
        int b = source.indexOf(to, a);
        assertThat(a).as(from).isGreaterThanOrEqualTo(0);
        assertThat(b).as(to).isGreaterThan(a);
        return source.substring(a, b);
    }

    private static boolean available(String... command) {
        try {
            var p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs a script fed on stdin with the case line in $OELINK_LINE; returns the exit code. */
    private static int run(String script, String line, String... command) throws Exception {
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("OELINK_LINE", line);
        var p = pb.start();
        p.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    @Test
    void macLauncher_splitsWithoutAShell_andRefusesWordsThatAreNotAllowed() throws Exception {
        assumeTrue(available("bash", "-c", "[[ a == a ]] && exit 0"), "bash not available");
        String source = Files.readString(Path.of("oelink/mac/install_mac_oelink.sh"), StandardCharsets.UTF_8);
        String functions = between(source, "# ── Launch-argument parsing", "# Tells the user");
        String script = functions + "\n"
            + "split_args \"$OELINK_LINE\" || exit 1\n"
            + "for w in \"${ARGV[@]}\"; do arg_allowed \"$w\" || exit 1; done\n"
            + "exit 0\n";

        for (var c : CASES.entrySet()) {
            assertThat(run(script, c.getKey(), "bash", "-s") == 0).as(c.getKey()).isEqualTo(c.getValue());
        }
    }

    @Test
    void macLauncher_neverEvaluatesTheArguments() throws IOException {
        String source = Files.readString(Path.of("oelink/mac/install_mac_oelink.sh"), StandardCharsets.UTF_8);
        assertThat(source).doesNotContain("eval ");
    }

    @Test
    void windowsLauncher_splitsWithoutAShell_andRefusesWordsThatAreNotAllowed() throws Exception {
        assumeTrue(available("pwsh", "-NoProfile", "-Command", "exit 0"), "pwsh not available");
        String source = Files.readString(Path.of("oelink/win/_internal/_oelink_exe.ps1"), StandardCharsets.UTF_8);
        String functions = between(source, "# The argument string comes", "function Launch-WithArgs");
        String script = functions + "\n"
            + "$words = Split-OelinkArgs $env:OELINK_LINE\n"
            + "if ($null -eq $words) { exit 1 }\n"
            + "foreach ($w in $words) { if (-not (Test-OelinkArg $w)) { exit 1 } }\n"
            + "exit 0\n";
        Path file = Files.createTempFile("oelink-args", ".ps1");
        try {
            Files.writeString(file, script, StandardCharsets.UTF_8);
            for (var c : CASES.entrySet()) {
                int code = run("", c.getKey(), "pwsh", "-NoProfile", "-File", file.toString());
                assertThat(code == 0).as(c.getKey()).isEqualTo(c.getValue());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
