package tricatch.oe.hosts.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tricatch.oe.hub.controller.SettingsController;
import tricatch.oe.mapper.MapperTestBase;
import tricatch.oe.proxy.ReverseProxyServer;

import static org.assertj.core.api.Assertions.assertThat;

// HostsController.proxyIpFor: PROXY_SVR is a plain admin-set address (ReverseProxyServer), not
// derived from the caller-controlled Host header - see the class-level comment on ProxyIpWiringTest's
// old DNS-lookup-based design in git history if that context is ever needed again.
class ProxyIpWiringTest extends MapperTestBase {

    private static final String LOCAL = "10.0.0.5";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HostsController hosts =
        new HostsController(FACTORY, new SettingsController(FACTORY, objectMapper), objectMapper);

    @AfterEach
    void resetProxySvrAddress() {
        ReverseProxyServer.setProxySvrAddress("127.0.0.1", 0L);
    }

    @Test
    void defaultsToLoopback() {
        assertThat(hosts.proxyIpFor(LOCAL)).isEqualTo("127.0.0.1");
    }

    @Test
    void usesTheAdminConfiguredAddress() {
        ReverseProxyServer.setProxySvrAddress("203.0.113.9", 0L);

        assertThat(hosts.proxyIpFor(LOCAL)).isEqualTo("203.0.113.9");
    }

    @Test
    void inWorkspaceMode_theConfiguredAddressIsNeverUsed() {
        // oeProxy doesn't run in workspace mode, so PROXY_SVR means nothing there.
        ReverseProxyServer.setProxySvrAddress("203.0.113.9", 0L);
        var previous = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "workspace");
        try {
            assertThat(hosts.proxyIpFor(LOCAL)).isEqualTo(LOCAL);
        } finally {
            if (previous == null) System.clearProperty("oe.mode");
            else System.setProperty("oe.mode", previous);
        }
    }
}
