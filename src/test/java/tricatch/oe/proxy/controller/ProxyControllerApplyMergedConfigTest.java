package tricatch.oe.proxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;
import tricatch.oe.proxy.service.ProxyConfService;
import tricatch.oe.proxy.service.ProxyVhostService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// applyMergedConfig() is the same validate-before-persist rule as apiConfSet's vhost handling,
// but exercised through the endpoint users actually hit when editing a vhost's content in place:
// PATCH /api/proxy/vhosts/{vhostId}/content, which re-merges and live-applies whenever the edited
// vhost is currently selected.
class ProxyControllerApplyMergedConfigTest extends MapperTestBase {

    // "location" must bind to a List<VirtualLocation>; a plain scalar here passes the loose,
    // map-shape-only check in ReverseProxyServer.mergeVhostYaml() but fails the strict
    // Constructor(VirtualHost.class) binding inside setVirtualHosts().
    private static final String STRUCTURALLY_INVALID_VHOST = """
            virtual:
              - domain: broken.example.com
                location: not-a-list
            """;

    private static final String VALID_VHOST = """
            virtual:
              - domain: ok.example.com
                location:
                  - host: http://${LOCAL_SVR}:36912
                    path:
                      - /**
            """;

    private Javalin appFor(ProxyController controller, HubUser user) {
        return Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute("currentUser", user));
            config.routes.patch("/api/proxy/vhosts/{vhostId}/content", controller::apiUpdateContent);
            config.routes.patch("/api/proxy/vhosts/{vhostId}/selected", controller::apiToggleSelected);
        });
    }

    @Test
    void invalidMergedContent_isRejectedAndNeverPersisted_butTheDraftContentStillSaves() throws Exception {
        HubUser user = insertUser("merge-invalid");
        ProxyVhostService vhostService = new ProxyVhostService(FACTORY);
        ProxyConfService confService = new ProxyConfService(FACTORY);
        ProxyController controller = new ProxyController(FACTORY, new ObjectMapper());

        var vhost = vhostService.create(user.getUserNo());
        vhostService.toggleSelected(vhost.getVhostId(), user.getUserNo());

        JavalinTest.test(appFor(controller, user), (server, client) -> {
            var response = client.patch(
                    "/api/proxy/vhosts/" + vhost.getVhostId() + "/content",
                    Map.of("content", STRUCTURALLY_INVALID_VHOST));
            assertThat(response.code()).isEqualTo(400);

            // The 400 still carries the saved vhost as its body, so the client can tell
            // "content saved, live-apply rejected" apart from a plain save failure.
            var returned = new ObjectMapper().readValue(response.body().string(), Map.class);
            assertThat(returned.get("vhostContent")).isEqualTo(STRUCTURALLY_INVALID_VHOST);
        });

        // The merged live-routing config must never have been persisted from a rejected merge.
        assertThat(confService.get("vhost", user.getUserNo())).isNull();

        // The vhost's own draft content is a separate, always-allowed save (independent of
        // whether the merged result validates) and must still have gone through.
        assertThat(vhostService.get(vhost.getVhostId()).getVhostContent()).isEqualTo(STRUCTURALLY_INVALID_VHOST);
    }

    @Test
    void validMergedContent_isPersisted() throws Exception {
        HubUser user = insertUser("merge-valid");
        ProxyVhostService vhostService = new ProxyVhostService(FACTORY);
        ProxyConfService confService = new ProxyConfService(FACTORY);
        ProxyController controller = new ProxyController(FACTORY, new ObjectMapper());

        var vhost = vhostService.create(user.getUserNo());
        vhostService.toggleSelected(vhost.getVhostId(), user.getUserNo());

        JavalinTest.test(appFor(controller, user), (server, client) -> {
            var response = client.patch(
                    "/api/proxy/vhosts/" + vhost.getVhostId() + "/content",
                    Map.of("content", VALID_VHOST));
            assertThat(response.code()).isEqualTo(200);
        });

        assertThat(confService.get("vhost", user.getUserNo())).contains("ok.example.com");
    }

    @Test
    void togglingSelectionOnInvalidVhost_savesTheToggle_butRejectsTheMerge() throws Exception {
        HubUser user = insertUser("merge-toggle-invalid");
        ProxyVhostService vhostService = new ProxyVhostService(FACTORY);
        ProxyConfService confService = new ProxyConfService(FACTORY);
        ProxyController controller = new ProxyController(FACTORY, new ObjectMapper());

        var vhost = vhostService.create(user.getUserNo());
        vhostService.updateContent(vhost.getVhostId(), user.getUserNo(), STRUCTURALLY_INVALID_VHOST);

        JavalinTest.test(appFor(controller, user), (server, client) -> {
            var response = client.patch("/api/proxy/vhosts/" + vhost.getVhostId() + "/selected");
            assertThat(response.code()).isEqualTo(400);

            var returned = new ObjectMapper().readValue(response.body().string(), Map.class);
            assertThat(returned.get("selected")).isEqualTo(true);
        });

        // The selection toggle itself must have persisted even though the merge was rejected.
        assertThat(vhostService.get(vhost.getVhostId()).isSelected()).isTrue();
        assertThat(confService.get("vhost", user.getUserNo())).isNull();
    }
}
