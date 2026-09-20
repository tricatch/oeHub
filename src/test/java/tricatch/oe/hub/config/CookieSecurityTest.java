package tricatch.oe.hub.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CookieSecurityTest {

    @AfterEach
    void clear() {
        System.clearProperty("oe.secure.cookie");
    }

    @Test
    void byDefault_followsTheRequest() {
        assertThat(CookieSecurity.isSecure("https")).isTrue();
        assertThat(CookieSecurity.isSecure("HTTPS")).isTrue();
        assertThat(CookieSecurity.isSecure("http")).isFalse();
        assertThat(CookieSecurity.isSecure(null)).isFalse();
    }

    @Test
    void whenForced_everyRequestCounts_asHttps() {
        System.setProperty("oe.secure.cookie", "true");

        assertThat(CookieSecurity.isSecure("http")).isTrue();
        assertThat(CookieSecurity.isSecure(null)).isTrue();
    }

    @Test
    void anythingButTrue_doesNotForce() {
        System.setProperty("oe.secure.cookie", "yes");

        assertThat(CookieSecurity.isSecure("http")).isFalse();
    }
}
