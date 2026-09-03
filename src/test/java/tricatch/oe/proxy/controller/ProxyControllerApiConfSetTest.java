package tricatch.oe.proxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;
import tricatch.oe.proxy.service.ProxyConfService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyControllerApiConfSetTest extends MapperTestBase {

    private static final String VALID_VHOST_YAML = """
            virtual:
              - domain: test.example.com
                location:
                  - host: http://${LOCAL_SVR}:36912
                    path:
                      - /**
            """;

    private Javalin appFor(ProxyController controller, HubUser user) {
        return Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute("currentUser", user));
            config.routes.put("/api/proxy/conf/{name}", controller::apiConfSet);
        });
    }

    @Test
    void invalidVhostYaml_isRejectedAndNeverPersisted() throws Exception {
        HubUser user = insertUser("vhostconf-invalid");
        ProxyController controller = new ProxyController(FACTORY, new ObjectMapper());
        ProxyConfService confService = new ProxyConfService(FACTORY);

        JavalinTest.test(appFor(controller, user), (server, client) -> {
            var response = client.put("/api/proxy/conf/vhost", Map.of("value", "not: [valid: yaml: at: all"));
            assertThat(response.code()).isEqualTo(400);
        });

        // The invalid YAML must never have reached the DB.
        assertThat(confService.get("vhost", user.getUserNo())).isNull();
    }

    @Test
    void validVhostYaml_isPersistedAndReturns204() throws Exception {
        HubUser user = insertUser("vhostconf-valid");
        ProxyController controller = new ProxyController(FACTORY, new ObjectMapper());
        ProxyConfService confService = new ProxyConfService(FACTORY);

        JavalinTest.test(appFor(controller, user), (server, client) -> {
            var response = client.put("/api/proxy/conf/vhost", Map.of("value", VALID_VHOST_YAML));
            assertThat(response.code()).isEqualTo(204);
        });

        assertThat(confService.get("vhost", user.getUserNo())).isEqualTo(VALID_VHOST_YAML);
    }
}
