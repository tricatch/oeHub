package tricatch.oe.proxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import tricatch.oe.hub.controller.AdminUserController;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.NotFoundProxyVirtualHostsException;
import tricatch.oe.proxy.service.ProxyVhostService;
import tricatch.oe.proxy.util.OidUtil;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The reverse proxy serves from an in-memory routing table built from a user's selected vhosts.
// Removing vhosts (or the user) must take their routes out of that table right away - otherwise a
// deleted route keeps serving until the next apply or a restart.
class ProxyControllerStaleRoutingTest extends MapperTestBase {

    private static final String ROUTE = "stale-route.example.com";
    private static final String REPLACEMENT_ROUTE = "replacement-route.example.com";

    private static String yamlFor(String domain) {
        return """
                virtual:
                  - domain: %s
                    location:
                      - host: http://127.0.0.1:36912
                        path:
                          - /**
                """.formatted(domain);
    }

    private final ProxyVhostService vhostService = new ProxyVhostService(FACTORY);
    private final ProxyController controller = new ProxyController(FACTORY, new ObjectMapper());

    /** A user with one selected vhost whose routes are already live in the proxy. */
    private HubUser userWithLiveRoute(String userId) throws Exception {
        var user = insertUser(userId);
        var vhost = vhostService.create(user.getUserNo());
        vhostService.updateContent(vhost.getVhostId(), user.getUserNo(), yamlFor(ROUTE));
        vhostService.toggleSelected(vhost.getVhostId(), user.getUserNo());
        assertThat(ProxyController.applyMergedConfig(FACTORY, user.getUserNo(), "127.0.0.1")).isTrue();
        assertThat(liveRoutes(user)).containsExactly(ROUTE);
        return user;
    }

    private static java.util.Set<String> liveRoutes(HubUser user) throws Exception {
        return ReverseProxyServer.getVirtualHosts("ignored", OidUtil.encode(user.getUserNo())).keySet();
    }

    private static void assertNoLiveRoutes(HubUser user) {
        assertThatThrownBy(() -> liveRoutes(user)).isInstanceOf(NotFoundProxyVirtualHostsException.class);
    }

    private Javalin appAs(HubUser actor) {
        return Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute("currentUser", actor));
            config.routes.delete("/api/proxy/vhosts/{vhostId}", controller::apiDelete);
            config.routes.delete("/api/proxy/vhosts", controller::apiDeleteAll);
            config.routes.post("/api/proxy/vhosts/import", controller::apiImport);
            var adminUser = new AdminUserController(FACTORY);
            config.routes.delete("/api/wsa/users/{userNo}", adminUser::apiDeleteUser);
        });
    }

    @Test
    void deletingASelectedVhost_removesItsLiveRoutes() throws Exception {
        var user = userWithLiveRoute("stale-delete-one");
        var vhostId = vhostService.list(user.getUserNo()).get(0).getVhostId();

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.delete("/api/proxy/vhosts/" + vhostId).code()).isEqualTo(204));

        assertNoLiveRoutes(user);
    }

    @Test
    void deletingAllVhosts_removesTheirLiveRoutes_andTheVhostsThemselves() throws Exception {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var user = userWithLiveRoute("stale-delete-all");

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.delete("/api/proxy/vhosts").code()).isEqualTo(204));

        assertNoLiveRoutes(user);
        // The vhost was 'public' (the default); "delete all" must delete it, not hand it to the
        // workspace's system account as account removal does.
        assertThat(vhostService.list(user.getUserNo())).isEmpty();
        assertThat(vhostService.list(wsSystem.getUserNo())).isEmpty();
    }

    @Test
    void replaceImport_swapsTheLiveRoutesForTheImportedSelection() throws Exception {
        var user = userWithLiveRoute("stale-import");
        var body = Map.of("vhosts", List.of(Map.of(
            "vhostProfile", "imported", "vhostContent", yamlFor(REPLACEMENT_ROUTE), "selected", true)));

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/proxy/vhosts/import?merge=false", body).code()).isEqualTo(200));

        assertThat(liveRoutes(user)).containsExactly(REPLACEMENT_ROUTE);
    }

    @Test
    void replaceImportWithNoSelectedVhost_removesTheOldLiveRoutes() throws Exception {
        var user = userWithLiveRoute("stale-import-unselected");
        var body = Map.of("vhosts", List.of(Map.of(
            "vhostProfile", "imported", "vhostContent", yamlFor(REPLACEMENT_ROUTE), "selected", false)));

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/proxy/vhosts/import?merge=false", body).code()).isEqualTo(200));

        assertNoLiveRoutes(user);
    }

    @Test
    void deletingAUser_dropsTheirLiveRoutesAndIpClaim() throws Exception {
        var member = userWithLiveRoute("stale-member");
        var admin = insertUser("stale-admin");
        ReverseProxyServer.claimIp("10.20.30.40", member.getUserNo());
        assertThat(ReverseProxyServer.getClaimedOid("10.20.30.40")).isEqualTo(OidUtil.encode(member.getUserNo()));

        JavalinTest.test(appAs(admin), (server, client) ->
            assertThat(client.delete("/api/wsa/users/" + member.getUserNo()).code()).isEqualTo(200));

        // The account is gone, but its oid still decodes - so without this the cached table (or
        // the claimed IP) would keep routing for the removed member.
        assertNoLiveRoutes(member);
        assertThat(ReverseProxyServer.getClaimedOid("10.20.30.40")).isNull();
    }
}
