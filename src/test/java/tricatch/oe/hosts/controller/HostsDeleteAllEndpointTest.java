package tricatch.oe.hosts.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.controller.SettingsController;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;

import static org.assertj.core.api.Assertions.assertThat;

// "Delete all" is a user's request to remove their profiles. It used to share the account-removal
// path, which keeps 'workspace' scoped profiles alive by handing them to the workspace's system
// account - so most profiles (new ones default to 'workspace' scope) silently survived, still
// searchable and shared.
class HostsDeleteAllEndpointTest extends MapperTestBase {

    private Javalin appAs(HubUser actor) {
        var objectMapper = new ObjectMapper();
        var hosts = new HostsController(FACTORY, new SettingsController(FACTORY, objectMapper), objectMapper);
        return Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute("currentUser", actor));
            config.routes.delete("/api/hosts", hosts::apiDeleteAll);
        });
    }

    @Test
    void deleteAll_removesPublicProfiles_insteadOfHandingThemToTheSystemAccount() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("delete-all-endpoint");
        var service = new HostsProfService(FACTORY);
        var publicProfile = service.create(owner.getUserNo()); // defaults to share_scope='workspace'

        JavalinTest.test(appAs(owner), (server, client) ->
            assertThat(client.delete("/api/hosts").code()).isEqualTo(204));

        assertThat(service.get(publicProfile.getHostsId())).isNull();
        assertThat(service.list(owner.getUserNo())).isEmpty();
        assertThat(service.list(wsSystem.getUserNo())).isEmpty();
    }
}
