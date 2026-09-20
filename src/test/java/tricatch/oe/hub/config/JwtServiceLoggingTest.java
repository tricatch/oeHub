package tricatch.oe.hub.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tricatch.oe.mapper.MapperTestBase;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

// An expired auth cookie is routine (sessions last 24 hours), so it must not produce a warning or a
// stack trace; a token that fails verification for any other reason is still worth a warning, but
// its message says it all.
class JwtServiceLoggingTest extends MapperTestBase {

    private final Logger jwtLogger = (Logger) LoggerFactory.getLogger(JwtService.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private JwtService jwt;

    @BeforeEach
    void attach() {
        jwt = new JwtService(FACTORY);
        captured.start();
        jwtLogger.addAppender(captured);
    }

    @AfterEach
    void detach() {
        jwtLogger.detachAppender(captured);
    }

    private boolean loggedAtLeast(Level level) {
        return captured.list.stream().anyMatch(e -> e.getLevel().isGreaterOrEqual(level));
    }

    @Test
    void anExpiredToken_isRejectedQuietly() {
        var twoDaysAgo = new Date(System.currentTimeMillis() - 2L * 24 * 3600 * 1000);
        var expired = jwt.issueAt(42L, 0, twoDaysAgo, 24L * 3600);

        assertThat(jwt.verify(expired)).isNull();
        assertThat(loggedAtLeast(Level.WARN)).as("expiry is routine, not a warning").isFalse();
    }

    @Test
    void aTamperedToken_isRejectedWithAWarningButNoStackTrace() {
        var valid = jwt.issue(42L, 0, false);
        var tampered = valid.substring(0, valid.length() - 4) + (valid.endsWith("AAAA") ? "BBBB" : "AAAA");

        assertThat(jwt.verify(tampered)).isNull();
        assertThat(captured.list).anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(captured.list).allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    void garbage_isRejectedWithAWarningButNoStackTrace() {
        assertThat(jwt.verify("not-a-jwt")).isNull();
        assertThat(captured.list).anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(captured.list).allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    void aValidToken_stillVerifiesAndLogsNothing() {
        var token = jwt.issue(42L, 3, true);

        var verified = jwt.verify(token);

        assertThat(verified).isNotNull();
        assertThat(verified.userNo()).isEqualTo(42L);
        assertThat(verified.tokenVersion()).isEqualTo(3);
        assertThat(loggedAtLeast(Level.WARN)).isFalse();
    }
}
