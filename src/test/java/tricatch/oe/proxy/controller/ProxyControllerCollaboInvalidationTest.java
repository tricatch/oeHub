package tricatch.oe.proxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.service.ProxyVhostService;
import tricatch.oe.proxy.util.OidUtil;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Editing a shared (collabo) vhost updates one canonical DB row, but the live routing cache is
// per-owner. This verifies that other selected collaborators' cached routing gets invalidated
// (and lazily reloaded to the new content) when someone else edits the shared vhost.
class ProxyControllerCollaboInvalidationTest extends MapperTestBase {

    private static final String BEFORE_EDIT = """
            virtual:
              - domain: before-edit.example.com
                location:
                  - host: http://127.0.0.1:36912
                    path:
                      - /**
            """;

    private static final String AFTER_EDIT = """
            virtual:
              - domain: after-edit.example.com
                location:
                  - host: http://127.0.0.1:36912
                    path:
                      - /**
            """;

    @Test
    void editingASharedVhost_invalidatesOtherSelectedCollaborators_liveRoutingCache() throws Exception {
        ReverseProxyServer.init(FACTORY);
        ProxyVhostService vhostService = new ProxyVhostService(FACTORY);

        HubUser owner = insertUser("collabo-owner");
        HubUser collaborator = insertUser("collabo-member");

        // Owner creates a vhost and turns it into a shared/collabo one.
        var ownerVhost = vhostService.create(owner.getUserNo());
        var ownerRef = vhostService.updateShareScope(ownerVhost.getVhostId(), owner.getUserNo(), "collabo");

        // Collaborator joins it and marks it selected.
        var collabRef = vhostService.registerCollabo(collaborator.getUserNo(), ownerRef.getParentId());
        vhostService.toggleSelected(collabRef.getVhostId(), collaborator.getUserNo());

        // Seed the collaborator's live routing cache with pre-edit content directly, as if they
        // were already routing through it before the owner's edit below.
        ReverseProxyServer.setVirtualHosts(collaborator.getUserNo(), BEFORE_EDIT);
        String collaboratorOid = OidUtil.encode(collaborator.getUserNo());
        assertThat(ReverseProxyServer.getVirtualHosts("ignored", collaboratorOid).keySet())
                .containsExactly("before-edit.example.com");

        // Owner edits the shared content through the real endpoint.
        ProxyController controller = new ProxyController(FACTORY, new ObjectMapper());
        Javalin app = Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute("currentUser", owner));
            config.routes.patch("/api/proxy/vhosts/{vhostId}/content", controller::apiUpdateContent);
        });
        JavalinTest.test(app, (server, client) -> {
            var response = client.patch("/api/proxy/vhosts/" + ownerRef.getVhostId() + "/content", Map.of("content", AFTER_EDIT));
            assertThat(response.code()).isEqualTo(200);
        });

        // The collaborator's cache must have been invalidated and lazily reloaded with the new content.
        assertThat(ReverseProxyServer.getVirtualHosts("ignored", collaboratorOid).keySet())
                .containsExactly("after-edit.example.com");
    }
}
