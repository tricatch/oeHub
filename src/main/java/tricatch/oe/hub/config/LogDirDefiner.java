package tricatch.oe.hub.config;

import ch.qos.logback.core.PropertyDefinerBase;

// Resolves the log directory for logback.xml's <define name="LOG_DIR">. Logback initializes
// on the first LoggerFactory call (static logger fields load before main runs), so the path
// is computed here from the system properties rather than set by the app at startup.
// -Doe.log.dir, when set, IS the log directory (used as-is); otherwise <oe.home>/log.
public class LogDirDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        var override = System.getProperty("oe.log.dir");
        if (override != null && !override.isBlank()) {
            return override;
        }
        return AppHome.oeHubDir().resolve("log").toString();
    }
}
