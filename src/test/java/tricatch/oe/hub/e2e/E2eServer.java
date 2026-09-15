package tricatch.oe.hub.e2e;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Boots the real oeHub server as a separate OS process, the same way a user would run it
 * ({@code gradlew run}), so Playwright drives an actually-running instance instead of an
 * in-JVM fake. Each instance gets its own {@code -Doe.home} data directory (under {@code build/})
 * so it never touches the real {@code ~/oeHub} data.
 */
public class E2eServer implements AutoCloseable {

    private final int port;
    private final Path homeDir;
    private final java.util.List<String> extraJvmArgs;
    private Process process;

    public E2eServer(int port) {
        this(port, java.util.List.of());
    }

    /** extraJvmArgs are appended after the standard ones below (e.g. "-Doe.mode=workspace"). */
    public E2eServer(int port, java.util.List<String> extraJvmArgs) {
        this.port = port;
        this.homeDir = Path.of("build", "e2e-home-" + port).toAbsolutePath();
        this.extraJvmArgs = extraJvmArgs;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    /** The isolated -Doe.home data directory this instance runs with (root-ca/, data/, etc.). */
    public Path homeDir() {
        return homeDir;
    }

    public void start() throws IOException, InterruptedException {
        deleteHomeDir();
        Files.createDirectories(homeDir);

        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Mirrors the ":run" Gradle task's classpath: raw src/main/resources first, so Pebble's
        // dev-mode FileLoader and the rest of the runtime classpath match what a normal run uses.
        var resourcesDir = Path.of("src", "main", "resources").toAbsolutePath().toString();
        var classpath = resourcesDir + File.pathSeparator + System.getProperty("java.class.path");

        var command = new ArrayList<String>();
        command.add(javaBin);
        command.add("-Doe.dev=true");
        command.add("-Doe.port=" + port);
        command.add("-Doe.home=" + homeDir);
        command.add("-Djava.net.preferIPv4Stack=true");
        command.addAll(extraJvmArgs);
        command.add("-cp");
        command.add(classpath);
        command.add("tricatch.oe.hub.OeHubApplication");

        process = new ProcessBuilder(command)
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectOutput(homeDir.resolve("server.log").toFile())
            .redirectErrorStream(true)
            .start();

        waitUntilReady(Duration.ofSeconds(30));
    }

    private void waitUntilReady(Duration timeout) throws InterruptedException, IOException {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                    "oeHub server process exited early (exit=" + process.exitValue() + "). See "
                        + homeDir.resolve("server.log"));
            }
            try {
                var conn = (HttpURLConnection) URI.create(baseUrl() + "/setup").toURL().openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.getResponseCode(); // any HTTP response means the server is accepting connections
                return;
            } catch (IOException notYetUp) {
                Thread.sleep(300);
            }
        }
        throw new IllegalStateException("oeHub server did not become ready within " + timeout
            + ". See " + homeDir.resolve("server.log"));
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        deleteHomeDir();
    }

    private void deleteHomeDir() {
        if (!Files.exists(homeDir)) return;
        try (var walk = Files.walk(homeDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
