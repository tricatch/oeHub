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
import tricatch.oe.proxy.service.ProxyVhostService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Import/restore files are user-supplied. A hosts or vhost row whose share scope the file doesn't
// spell out as "workspace" must come in private - the old default made a hand-written or
// older-format file publish everything (anonymously readable via /share in self-hosted mode).
class ImportShareScopeDefaultTest extends MapperTestBase {

    private static final String VHOST_YAML = """
            virtual:
              - domain: import-share-scope.example.com
                location:
                  - host: http://127.0.0.1:36912
                    path:
                      - /**
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HostsProfService hostsService = new HostsProfService(FACTORY);
    private final ProxyVhostService vhostService = new ProxyVhostService(FACTORY);

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

    /** A hosts entry as it appears in an export/backup; shareScope is left out when null. */
    private static Map<String, Object> hostsEntry(String name, String shareScope) {
        var m = new HashMap<String, Object>();
        m.put("hostsProfile", name);
        m.put("hostsContent", "127.0.0.1 " + name + ".oe");
        if (shareScope != null) m.put("shareScope", shareScope);
        return m;
    }

    /** A vhost entry as it appears in an export/backup; shareScope is left out when null. */
    private static Map<String, Object> vhostEntry(String name, String shareScope) {
        var m = new HashMap<String, Object>();
        m.put("vhostProfile", name);
        m.put("vhostContent", VHOST_YAML);
        if (shareScope != null) m.put("shareScope", shareScope);
        return m;
    }

    private Map<String, String> hostsShareScopeByName(HubUser user) {
        return hostsService.list(user.getUserNo()).stream()
            .collect(Collectors.toMap(p -> p.getHostsProfile(), p -> p.getShareScope()));
    }

    private Map<String, String> vhostShareScopeByName(HubUser user) {
        return vhostService.list(user.getUserNo()).stream()
            .collect(Collectors.toMap(v -> v.getVhostProfile(), v -> v.getShareScope()));
    }

    @Test
    void hostsImport_onlyAnExplicitWorkspaceStaysWorkspace() throws Exception {
        var user = insertUser("import-hosts");
        var body = Map.of("hosts", List.of(
            hostsEntry("no-field", null),
            hostsEntry("explicit-private", "private"),
            hostsEntry("explicit-workspace", "workspace"),
            hostsEntry("unknown-value", "everyone"),
            // Needs a live parent reference an import can't recreate - imported as a standalone
            // workspace-scoped row, as before (ShareScopeUtil.forImport).
            hostsEntry("was-collabo", "collabo")));

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/hosts/import?merge=true", body).code()).isEqualTo(200));

        assertThat(hostsShareScopeByName(user)).containsOnly(
            Map.entry("no-field", "private"),
            Map.entry("explicit-private", "private"),
            Map.entry("explicit-workspace", "workspace"),
            Map.entry("unknown-value", "private"),
            Map.entry("was-collabo", "workspace"));
    }

    @Test
    void vhostImport_onlyAnExplicitWorkspaceStaysWorkspace() throws Exception {
        var user = insertUser("import-vhosts");
        var body = Map.of("vhosts", List.of(
            vhostEntry("no-field", null),
            vhostEntry("explicit-private", "private"),
            vhostEntry("explicit-workspace", "workspace")));

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/proxy/vhosts/import?merge=true", body).code()).isEqualTo(200));

        assertThat(vhostShareScopeByName(user)).containsOnly(
            Map.entry("no-field", "private"),
            Map.entry("explicit-private", "private"),
            Map.entry("explicit-workspace", "workspace"));
    }

    @Test
    void backupRestore_keepsShareScopeForHostsAndVhosts_andDefaultsToPrivate() throws Exception {
        var user = insertUser("restore-both");
        var body = Map.of(
            "hosts", Map.of("profiles", List.of(
                hostsEntry("h-no-field", null),
                hostsEntry("h-private", "private"),
                hostsEntry("h-workspace", "workspace"))),
            "proxy", Map.of("vhosts", List.of(
                vhostEntry("v-no-field", null),
                vhostEntry("v-private", "private"),
                vhostEntry("v-workspace", "workspace"))));

        JavalinTest.test(appAs(user), (server, client) ->
            assertThat(client.post("/api/user/restore?merge=true", body).code()).isEqualTo(200));

        assertThat(hostsShareScopeByName(user)).containsOnly(
            Map.entry("h-no-field", "private"),
            Map.entry("h-private", "private"),
            Map.entry("h-workspace", "workspace"));
        // Restore never read share scope for vhosts at all, so every one of these used to come
        // back 'public' (now 'workspace') regardless of what the backup said.
        assertThat(vhostShareScopeByName(user)).containsOnly(
            Map.entry("v-no-field", "private"),
            Map.entry("v-private", "private"),
            Map.entry("v-workspace", "workspace"));
    }
}
