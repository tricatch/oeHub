package tricatch.oe.hub.config;

import java.nio.file.Path;

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
}
