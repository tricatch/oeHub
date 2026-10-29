package tricatch.oe.hub.config;

import ch.qos.logback.core.PropertyDefinerBase;

// Threshold for logback.xml's console appender: everything with -Doe.dev=true (./gradlew run),
// nothing otherwise, so a deployed instance logs only to the rolling file.
public class ConsoleLevelDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return "true".equals(System.getProperty("oe.dev")) ? "TRACE" : "OFF";
    }
}
