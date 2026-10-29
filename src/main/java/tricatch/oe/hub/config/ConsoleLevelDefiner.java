package tricatch.oe.hub.config;

import ch.qos.logback.core.PropertyDefinerBase;

// Threshold for logback.xml's console appender: everything with -Doe.dev=true (./gradlew run) or
// -Doe.log.console=true (a plain jar run that still wants console output), nothing otherwise, so a
// deployed instance logs only to the rolling file. -Doe.dev can't serve the jar case: it also
// reads templates/static files from src/main/resources, which a jar install doesn't have.
public class ConsoleLevelDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        boolean console = "true".equals(System.getProperty("oe.dev"))
            || "true".equals(System.getProperty("oe.log.console"));
        return console ? "TRACE" : "OFF";
    }
}
