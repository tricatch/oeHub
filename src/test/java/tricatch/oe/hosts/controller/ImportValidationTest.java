package tricatch.oe.hosts.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.controller.SettingsController;
import tricatch.oe.hub.controller.UserController;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;
import tricatch.oe.proxy.controller.ProxyController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Import/restore files are user-supplied. A malformed one must be answered with 400 - not a 500
// from a NullPointerException/ClassCastException - and, above all, before a replace-import has
// deleted anything, so a bad file can't cost the user their existing data.
class ImportValidationTest extends MapperTestBase {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HostsProfService hostsService = new HostsProfService(FACTORY);

    private Javalin appAs(HubUser actor) {
        var hosts = new HostsController(FACTORY, new SettingsController(FACTORY, objectMapper), objectMapper);
        var proxy = new ProxyController(FACTORY, objectMapper);
        var user = new UserController(FACTORY, objectMapper);
        return Javalin.create(config -> {
            config.routes.before(ctx -> ctx.attribute("currentUser", actor));
            config.routes.post("/api/hosts/import", hosts::apiImport);
            config.routes.post("/api/proxy/vhosts/import", proxy::apiImport);
            config.routes.post("/api/user/restore", user::apiRestore);
        });
    }

    private static Map<String, Object> entry(Object... keyValues) {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    private void assertRejectedAndNothingDeleted(HubUser user, String path, Object body) {
        var existing = hostsService.create(user.getUserNo());

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post(path, body).code()).isEqualTo(400));

        assertThat(hostsService.get(existing.getHostsId())).as("the user's data must survive a rejected file").isNotNull();
    }

    @Test
    void hostsImport_entryWithoutAProfileName_isRejected_andAReplaceDoesNotDeleteAnything() {
        var user = insertUser("import-no-name");
        assertRejectedAndNothingDeleted(user, "/api/hosts/import?merge=false",
            Map.of("hosts", List.of(entry("hostsContent", "127.0.0.1 a.oe"))));
    }

    @Test
    void hostsImport_wrongTypes_areRejected() {
        var user = insertUser("import-wrong-types");
        assertRejectedAndNothingDeleted(user, "/api/hosts/import?merge=false",
            Map.of("hosts", List.of(entry("hostsProfile", 42))));
        assertRejectedAndNothingDeleted(insertUser("import-wrong-sort"), "/api/hosts/import?merge=false",
            Map.of("hosts", List.of(entry("hostsProfile", "p", "sortOrder", "first"))));
        assertRejectedAndNothingDeleted(insertUser("import-not-objects"), "/api/hosts/import?merge=false",
            Map.of("hosts", List.of("just a string")));
        assertRejectedAndNothingDeleted(insertUser("import-not-a-list"), "/api/hosts/import?merge=false",
            Map.of("hosts", "nope"));
        assertRejectedAndNothingDeleted(insertUser("import-bad-settings"), "/api/hosts/import?merge=false",
            Map.of("hosts", List.of(entry("hostsProfile", "p")), "settings", Map.of("open_url", 7)));
    }

    @Test
    void hostsImport_entryWithoutContent_isImportedWithEmptyContent() {
        var user = insertUser("import-no-content");

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/hosts/import?merge=true",
                Map.of("hosts", List.of(entry("hostsProfile", "no-content")))).code()).isEqualTo(200));

        var imported = hostsService.list(user.getUserNo());
        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).getHostsContent()).isEmpty();
    }

    @Test
    void vhostImport_entryWithoutAProfileName_isRejected() {
        var user = insertUser("import-vhost-no-name");
        assertRejectedAndNothingDeleted(user, "/api/proxy/vhosts/import?merge=false",
            Map.of("vhosts", List.of(entry("vhostContent", "virtual: []"))));
    }

    @Test
    void restore_aMalformedVhost_isRejectedBeforeTheHostsAreReplaced() {
        var user = insertUser("restore-bad-vhost");
        // Valid hosts, bad vhost: used to replace the hosts first and only then fail on the vhost.
        assertRejectedAndNothingDeleted(user, "/api/user/restore?merge=false",
            Map.of(
                "hosts", Map.of("profiles", List.of(entry("hostsProfile", "restored", "hostsContent", "127.0.0.1 r.oe"))),
                "proxy", Map.of("vhosts", List.of(entry("vhostContent", "no name")))));
    }

    @Test
    void restore_aWellFormedFile_stillWorks() {
        var user = insertUser("restore-ok");

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/user/restore?merge=true", Map.of(
                "hosts", Map.of(
                    "profiles", List.of(entry("hostsProfile", "restored", "hostsContent", "127.0.0.1 r.oe")),
                    "settings", Map.of("incognito", "true")),
                "proxy", Map.of("vhosts", List.of(entry("vhostProfile", "v", "vhostContent", "virtual: []"))))).code())
                .isEqualTo(200));

        assertThat(hostsService.list(user.getUserNo())).extracting(p -> p.getHostsProfile()).containsExactly("restored");
    }
}
