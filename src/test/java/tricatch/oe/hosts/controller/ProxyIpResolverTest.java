package tricatch.oe.hosts.controller;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyIpResolverTest {

    private static final String LOCAL = "10.0.0.5";
    private static final Predicate<String> NOTHING_ALLOWED = name -> false;

    /** A lookup that records every name it is asked about and answers 203.0.113.9 for all of them. */
    private final List<String> looked = new ArrayList<>();
    private final Function<String, String> lookup = name -> {
        looked.add(name);
        return "203.0.113.9";
    };

    private static Predicate<String> allowing(String... names) {
        return Set.of(names)::contains;
    }

    @Test
    void onlyNamesOnTheListAreLookedUp() {
        var allowed = allowing("hub.example.com");

        assertThat(ProxyIpResolver.resolve("hub.example.com:36912", LOCAL, allowed, lookup)).isEqualTo("203.0.113.9");
        assertThat(looked).containsExactly("hub.example.com");

        looked.clear();
        // Any other name is not looked up at all - the server never asks DNS about it.
        assertThat(ProxyIpResolver.resolve("attacker-chosen.example.net", LOCAL, allowed, lookup)).isEqualTo(LOCAL);
        assertThat(looked).isEmpty();
    }

    @Test
    void emptyList_meansNoNameIsLookedUp() {
        assertThat(ProxyIpResolver.resolve("hub.example.com", LOCAL, NOTHING_ALLOWED, lookup)).isEqualTo(LOCAL);
        assertThat(looked).isEmpty();
    }

    @Test
    void ipLiterals_alwaysPass_becauseTheyNeedNoDnsLookup() {
        assertThat(ProxyIpResolver.resolve("192.168.1.23:36912", LOCAL, NOTHING_ALLOWED, lookup)).isEqualTo("203.0.113.9");
        assertThat(ProxyIpResolver.resolve("[::1]:36912", LOCAL, NOTHING_ALLOWED, lookup)).isEqualTo("203.0.113.9");
        assertThat(looked).containsExactly("192.168.1.23", "::1");
    }

    @Test
    void aFailedLookup_fallsBackToTheServersOwnAddress() {
        assertThat(ProxyIpResolver.resolve("hub.example.com", LOCAL, allowing("hub.example.com"), name -> null))
            .isEqualTo(LOCAL);
    }

    @Test
    void missingOrBlankHostHeader_usesTheServersOwnAddress() {
        assertThat(ProxyIpResolver.resolve(null, LOCAL, allowing("hub.example.com"), lookup)).isEqualTo(LOCAL);
        assertThat(ProxyIpResolver.resolve("  ", LOCAL, NOTHING_ALLOWED, lookup)).isEqualTo(LOCAL);
        assertThat(looked).isEmpty();
    }
}
