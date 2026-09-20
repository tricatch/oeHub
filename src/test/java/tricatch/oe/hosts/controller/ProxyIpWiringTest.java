package tricatch.oe.hosts.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tricatch.oe.fwdproxy.ForwardProxyServer;
import tricatch.oe.hub.controller.SettingsController;
import tricatch.oe.mapper.MapperTestBase;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// HostsController.proxyIpFor decides which Host header names the server may look up in DNS: only
// names on the admin's allowed-domains list - the same list that gates the forward proxy's relay
// (ForwardProxyServer.isWhitelisted) - whoever the caller is.
class ProxyIpWiringTest extends MapperTestBase {

    private static final String LOCAL = "10.0.0.5";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HostsController hosts =
        new HostsController(FACTORY, new SettingsController(FACTORY, objectMapper), objectMapper);
    private final List<String> looked = new ArrayList<>();

    @BeforeAll
    static void initServer() {
        ForwardProxyServer.init(FACTORY);
    }

    @BeforeEach
    @AfterEach
    void clearAllowedDomains() {
        ForwardProxyServer.setWhitelist("", 0L);
    }

    private String resolve(String hostHeader) {
        return hosts.proxyIpFor(hostHeader, LOCAL, name -> {
            looked.add(name);
            return "203.0.113.9";
        });
    }

    @Test
    void withNothingAllowed_noNameIsLookedUp() {
        assertThat(resolve("attacker-chosen.example.net")).isEqualTo(LOCAL);
        assertThat(looked).isEmpty(); // fell back to the server's own address without asking DNS
    }

    @Test
    void anAllowedDomain_isLookedUp() {
        ForwardProxyServer.setWhitelist("hub.example.com", 0L);

        assertThat(resolve("hub.example.com:36912")).isEqualTo("203.0.113.9");
        assertThat(looked).containsExactly("hub.example.com");
    }

    @Test
    void wildcardEntries_coverSubdomains_likeTheForwardProxyRelay() {
        ForwardProxyServer.setWhitelist("*.corp.example.com", 0L);

        assertThat(resolve("app.corp.example.com")).isEqualTo("203.0.113.9");
        assertThat(looked).containsExactly("app.corp.example.com");
    }

    @Test
    void aNameOffTheList_isStillNotLookedUpOnceSomethingIsAllowed() {
        ForwardProxyServer.setWhitelist("hub.example.com", 0L);

        assertThat(resolve("other.example.org")).isEqualTo(LOCAL);
        assertThat(looked).isEmpty();
    }

    @Test
    void inWorkspaceMode_nothingIsEverLookedUp_evenForAnAllowedNameOrAnIpAddress() {
        // oeProxy doesn't run in workspace mode, so PROXY_SVR means nothing there.
        ForwardProxyServer.setWhitelist("hub.example.com", 0L);
        var previous = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "workspace");
        try {
            assertThat(resolve("hub.example.com")).isEqualTo(LOCAL);
            assertThat(resolve("192.168.1.23:36912")).isEqualTo(LOCAL);
        } finally {
            if (previous == null) System.clearProperty("oe.mode");
            else System.setProperty("oe.mode", previous);
        }
        assertThat(looked).isEmpty();
    }

    @Test
    void anIpAddress_needsNoListEntry() {
        assertThat(resolve("192.168.1.23:36912")).isEqualTo("203.0.113.9");
        assertThat(looked).containsExactly("192.168.1.23");
    }
}
