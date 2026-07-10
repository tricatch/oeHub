package tricatch.oe.hub;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.FileRenderer;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.FileLoader;
import tricatch.oe.hub.i18n.LocaleContext;
import tricatch.oe.hub.i18n.Messages;
import org.apache.ibatis.session.SqlSessionFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.config.AppHome;
import tricatch.oe.hub.config.DatabaseConfig;
import tricatch.oe.hub.config.JwtService;
import tricatch.oe.hub.controller.AdminHostsUaController;
import tricatch.oe.hub.controller.AdminHostsUrlController;
import tricatch.oe.hub.controller.OidExtensionController;
import tricatch.oe.hub.controller.AdminUserController;
import tricatch.oe.hub.controller.AuthController;
import tricatch.oe.hub.controller.SetupController;
import tricatch.oe.hub.controller.SettingsController;
import tricatch.oe.hub.controller.UserController;
import tricatch.oe.hosts.controller.HostsController;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.controller.ProxyController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.HashMap;
import java.util.Properties;

public class OeHubApplication {

    private static final Logger logger = LoggerFactory.getLogger(OeHubApplication.class);

    private static final String VERSION = resolveVersion();
    private static final int DEFAULT_PORT = 36912;
    private static int appPort = DEFAULT_PORT;
    private static int h2ConsolePort = DEFAULT_PORT + 1;

    private static int resolvePort() {
        String prop = System.getProperty("port");
        if (prop != null && !prop.isBlank()) {
            try { return Integer.parseInt(prop.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return DEFAULT_PORT;
    }

    private static String resolveVersion() {
        String sysProp = System.getProperty("app.version");
        if (sysProp != null && !sysProp.isBlank()) return sysProp;
        String manifest = OeHubApplication.class.getPackage().getImplementationVersion();
        return manifest != null ? manifest : "unknown";
    }

    public static Javalin createApp(SqlSessionFactory sqlSessionFactory) {

        boolean dev = "true".equals(System.getProperty("dev"));

        var messagesMap = java.util.Map.of("en", new Messages("en"), "ko", new Messages("ko"));

        var objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        var jwtService  = new JwtService(sqlSessionFactory);
        var settings    = new SettingsController(sqlSessionFactory, objectMapper);
        var auth        = new AuthController(sqlSessionFactory, jwtService);
        var setup       = new SetupController(sqlSessionFactory, settings, objectMapper);
        var hosts       = new HostsController(sqlSessionFactory, settings, objectMapper);
        var proxy       = new ProxyController(sqlSessionFactory, objectMapper);
        var userCtrl    = new UserController(sqlSessionFactory, objectMapper);
        var adminUser   = new AdminUserController(sqlSessionFactory);
        var adminUa     = new AdminHostsUaController(sqlSessionFactory, objectMapper);
        var adminUrl    = new AdminHostsUrlController(sqlSessionFactory, objectMapper);
        var oidExtension = new OidExtensionController(settings);

        setup.refreshSetupState();

        var app = Javalin.create(config -> {
            config.http.maxRequestSize = 30 * 1024 * 1024L;
            config.fileRenderer(createRenderer(dev, messagesMap));
            if (dev) {
                var staticPath = Path.of("src/main/resources/static").toAbsolutePath().toString();
                config.staticFiles.add(staticPath, Location.EXTERNAL);
            } else {
                config.staticFiles.add("/static", Location.CLASSPATH);
            }

            // Set locale from cookie (fallback: Accept-Language header, then "en")
            config.routes.before(ctx -> {
                String lang = ctx.cookie("oe_lang");
                if (lang == null) {
                    String accept = ctx.header("Accept-Language");
                    if (accept != null && !accept.isBlank()) {
                        String raw = accept.split("[,;]")[0].trim();
                        if (raw.length() >= 2) lang = raw.substring(0, 2).toLowerCase();
                    }
                    lang = (lang != null && (lang.equals("en") || lang.equals("ko"))) ? lang : "en";
                    ctx.cookie("oe_lang", lang);
                }
                LocaleContext.set((lang.equals("en") || lang.equals("ko")) ? lang : "en");
            });

            // Redirect to setup if initial configuration is not complete
            config.routes.before(ctx -> {
                if (!SetupController.isSetupComplete()) {
                    var path = ctx.path();
                    if (!path.startsWith("/setup") && !path.startsWith("/css/") && !path.startsWith("/icon/") && !path.startsWith("/logo/") && !path.startsWith("/js/")) {
                        ctx.redirect("/setup");
                        ctx.skipRemainingHandlers();
                    }
                }
            });

            // Resolve user from cookie for all requests
            config.routes.before(auth::resolveUser);

            // Require login for all tool pages and API
            config.routes.before("/oehub/*", ctx -> {
                if (AuthController.currentUser(ctx) == null) {
                    ctx.redirect("/login?redirect=" + ctx.path());
                    ctx.skipRemainingHandlers();
                }
            });
            config.routes.before("/oehub/settings", ctx -> {
                var user = AuthController.currentUser(ctx);
                if (user == null || !"adm".equals(user.getRole())) {
                    ctx.status(403).result("Forbidden");
                    ctx.skipRemainingHandlers();
                }
            });
            config.routes.before("/oehub/admin/*", ctx -> {
                var user = AuthController.currentUser(ctx);
                if (user == null || !"adm".equals(user.getRole())) {
                    ctx.status(403).result("Forbidden");
                    ctx.skipRemainingHandlers();
                }
            });
            config.routes.before("/api/admin/*", ctx -> {
                var user = AuthController.currentUser(ctx);
                if (user == null || !"adm".equals(user.getRole())) {
                    ctx.status(403).result("Forbidden");
                    ctx.skipRemainingHandlers();
                }
            });
            config.routes.before("/oehub/h2", ctx -> {
                var user = AuthController.currentUser(ctx);
                if (user == null || !"adm".equals(user.getRole())) {
                    ctx.status(403).result("Forbidden");
                    ctx.skipRemainingHandlers();
                }
            });
            config.routes.before("/api/*", ctx -> {
                if (AuthController.currentUser(ctx) == null) {
                    ctx.status(401).result("Unauthorized");
                    ctx.skipRemainingHandlers();
                }
            });

            config.routes.after(ctx -> {
                LocaleContext.clear();
                var path = ctx.path();
                if (!path.startsWith("/css/") && !path.startsWith("/icon/") && !path.startsWith("/logo/") && !path.startsWith("/js/")) {
                    ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                    ctx.header("Pragma", "no-cache");
                    ctx.header("Expires", "0");
                }
            });

            config.routes.get("/setup", setup::showSetup);
            config.routes.post("/setup", setup::processSetup);
            config.routes.post("/setup/ca/generate", setup::generateCa);
            config.routes.post("/setup/ca/import",   setup::importCa);
            config.routes.get("/setup/hosts-url",              setup::apiSetupUrlList);
            config.routes.post("/setup/hosts-url",             setup::apiSetupUrlCreate);
            config.routes.patch("/setup/hosts-url/{urlId}",    setup::apiSetupUrlUpdate);
            config.routes.delete("/setup/hosts-url/{urlId}",   setup::apiSetupUrlDelete);
            config.routes.put("/setup/hosts-url/order",        setup::apiSetupUrlReorder);
            config.routes.get("/setup/hosts-ua",               setup::apiSetupUaList);
            config.routes.post("/setup/hosts-ua",              setup::apiSetupUaCreate);
            config.routes.patch("/setup/hosts-ua/{uaId}",      setup::apiSetupUaUpdate);
            config.routes.delete("/setup/hosts-ua/{uaId}",     setup::apiSetupUaDelete);
            config.routes.put("/setup/hosts-ua/order",         setup::apiSetupUaReorder);
            config.routes.post("/setup/oid-domain-default", setup::saveOidDomainDefault);

            config.routes.get("/", ctx -> {
                var model = new HashMap<String, Object>();
                model.put("user", AuthController.currentUser(ctx));
                model.put("appVersion", VERSION);
                ctx.render("templates/index.pebble", model);
            });

            config.routes.get("/login", auth::showLogin);
            config.routes.post("/login", auth::processLogin);
            config.routes.get("/logout", auth::logout);
            config.routes.get("/lang/{locale}", ctx -> {
                String locale = ctx.pathParam("locale");
                if (locale.equals("en") || locale.equals("ko")) ctx.cookie("oe_lang", locale);
                String back = ctx.header("Referer");
                ctx.redirect(back != null && !back.isBlank() ? back : "/");
            });
            config.routes.get("/register", auth::showRegister);
            config.routes.post("/register", auth::processRegister);

            // Admin settings
            config.routes.get("/oehub/settings",              settings::showSettings);
            config.routes.post("/api/admin/settings/oid-domain-default", settings::apiSaveOidDomainDefault);
            config.routes.post("/api/admin/settings/identifier",         settings::apiSaveIdentifier);
            config.routes.post("/oehub/settings/ca/generate", settings::generateCa);
            config.routes.post("/oehub/settings/ca/import",   settings::importCa);

            // H2 console (adm only)
            config.routes.get("/oehub/h2", ctx ->
                ctx.redirect("http://localhost:" + h2ConsolePort + "/login.do?setting=oeHub"));

            // oeHosts UI
            config.routes.get("/oehub/hosts",  hosts::showHosts);
            config.routes.get("/oehub/my/setting",  hosts::showMySetting);

            // oeHosts REST API
            config.routes.get("/api/hosts",                       hosts::apiList);
            config.routes.post("/api/hosts",                      hosts::apiCreate);
            config.routes.delete("/api/hosts",                    hosts::apiDeleteAll);
            config.routes.patch("/api/hosts/{hostsId}/content",    hosts::apiUpdateContent);
            config.routes.patch("/api/hosts/{hostsId}/name",       hosts::apiUpdateName);
            config.routes.patch("/api/hosts/{hostsId}/selected",   hosts::apiToggleSelected);
            config.routes.patch("/api/hosts/{hostsId}/visibility",  hosts::apiUpdateVisibility);
            config.routes.post("/api/hosts/{hostsId}/copy",        hosts::apiCopy);
            config.routes.post("/api/hosts/{hostsId}/register",    hosts::apiRegister);
            config.routes.delete("/api/hosts/{hostsId}",           hosts::apiDelete);
            config.routes.get("/api/hosts/search",                hosts::apiSearch);
            config.routes.put("/api/hosts/order",                 hosts::apiReorder);
            config.routes.get("/api/hosts/export",                hosts::apiExport);
            config.routes.post("/api/hosts/import",               hosts::apiImport);
            config.routes.get("/api/hosts/ua/presets",             hosts::apiUaPresets);
            config.routes.get("/api/hosts/url/presets",            hosts::apiUrlPresets);
            config.routes.get("/api/hosts/ua/my",                 hosts::apiMyUaList);
            config.routes.post("/api/hosts/ua/my",                hosts::apiMyUaCreate);
            config.routes.patch("/api/hosts/ua/my/{uaId}",        hosts::apiMyUaUpdate);
            config.routes.delete("/api/hosts/ua/my/{uaId}",       hosts::apiMyUaDelete);
            config.routes.put("/api/hosts/ua/my/order",           hosts::apiMyUaReorder);
            config.routes.get("/api/hosts/url/my",                hosts::apiMyUrlList);
            config.routes.post("/api/hosts/url/my",               hosts::apiMyUrlCreate);
            config.routes.patch("/api/hosts/url/my/{urlId}",      hosts::apiMyUrlUpdate);
            config.routes.delete("/api/hosts/url/my/{urlId}",     hosts::apiMyUrlDelete);
            config.routes.put("/api/hosts/url/my/order",          hosts::apiMyUrlReorder);
            config.routes.get("/api/hosts/conf/{name}",            hosts::apiEnvGet);
            config.routes.put("/api/hosts/conf/{name}",            hosts::apiEnvSet);
            config.routes.post("/api/oid/domain/my",              hosts::apiMyOidDomainSave);
            config.routes.get("/api/oid/download",                 oidExtension::apiDownload);

            // Share (public, no auth)
            config.routes.get("/share/{hostsId}/oelink",            hosts::apiShare);
            config.routes.get("/share/{hostsId}/text",             hosts::apiShareText);

            // My info
            config.routes.get("/oehub/my/info", ctx -> {
                var user = AuthController.currentUser(ctx);
                var fmt  = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                var model = new HashMap<String, Object>();
                model.put("user", user);
                model.put("createAt", user.getCreateAt() != null ? user.getCreateAt().format(fmt) : "");
                model.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().format(fmt) : "");
                ctx.render("templates/oehub/my-info.pebble", model);
            });

            // Licenses
            config.routes.get("/oehub/licenses", ctx -> {
                var model = new HashMap<String, Object>();
                model.put("user", AuthController.currentUser(ctx));
                ctx.render("templates/oehub/licenses.pebble", model);
            });

            // oeProxy UI
            config.routes.get("/oehub/proxy", proxy::showProxy);
            config.routes.get("/oehub/proxy/monitor", proxy::showMonitor);

            // oeProxy SSE monitor event stream
            config.routes.get("/api/proxy/monitor/event", proxy::monitorEvent);

            // oeProxy CA certificate download (no auth required — browser needs to install)
            config.routes.get("/api/proxy/ca", proxy::apiDownloadCa);

            // oeProxy REST API
            config.routes.get("/api/proxy/vhosts",                          proxy::apiList);
            config.routes.post("/api/proxy/vhosts",                         proxy::apiCreate);
            config.routes.delete("/api/proxy/vhosts",                       proxy::apiDeleteAll);
            config.routes.patch("/api/proxy/vhosts/{vhostId}/content",      proxy::apiUpdateContent);
            config.routes.patch("/api/proxy/vhosts/{vhostId}/name",         proxy::apiUpdateName);
            config.routes.patch("/api/proxy/vhosts/{vhostId}/selected",     proxy::apiToggleSelected);
            config.routes.patch("/api/proxy/vhosts/{vhostId}/visibility",   proxy::apiUpdateVisibility);
            config.routes.post("/api/proxy/vhosts/{vhostId}/copy",          proxy::apiCopy);
            config.routes.post("/api/proxy/vhosts/{vhostId}/register",      proxy::apiRegister);
            config.routes.delete("/api/proxy/vhosts/{vhostId}",             proxy::apiDelete);
            config.routes.get("/api/proxy/vhosts/search",                   proxy::apiSearch);
            config.routes.put("/api/proxy/vhosts/order",                    proxy::apiReorder);
            config.routes.get("/api/proxy/vhosts/export",                   proxy::apiExport);
            config.routes.post("/api/proxy/vhosts/import",                  proxy::apiImport);

            // User backup / restore
            config.routes.get("/api/user/backup",   userCtrl::apiBackup);
            config.routes.post("/api/user/restore",  userCtrl::apiRestore);

            // Admin: user management
            config.routes.get("/oehub/admin/users",               adminUser::showUsers);
            config.routes.get("/api/admin/users",                 adminUser::apiSearch);
            config.routes.patch("/api/admin/users/{userNo}/role",  adminUser::apiSetRole);
            config.routes.delete("/api/admin/users/{userNo}",      adminUser::apiDeleteUser);

            // Admin: hosts user-agent presets
            config.routes.get("/api/admin/hosts/ua",              adminUa::apiList);
            config.routes.post("/api/admin/hosts/ua",             adminUa::apiCreate);
            config.routes.patch("/api/admin/hosts/ua/{uaId}",     adminUa::apiUpdate);
            config.routes.delete("/api/admin/hosts/ua/{uaId}",    adminUa::apiDelete);
            config.routes.put("/api/admin/hosts/ua/order",        adminUa::apiReorder);
            config.routes.get("/api/admin/hosts/url",             adminUrl::apiList);
            config.routes.post("/api/admin/hosts/url",            adminUrl::apiCreate);
            config.routes.patch("/api/admin/hosts/url/{urlId}",   adminUrl::apiUpdate);
            config.routes.delete("/api/admin/hosts/url/{urlId}",  adminUrl::apiDelete);
            config.routes.put("/api/admin/hosts/url/order",       adminUrl::apiReorder);
            config.routes.get("/api/proxy/conf/{name}",                     proxy::apiConfGet);
            config.routes.put("/api/proxy/conf/{name}",                     proxy::apiConfSet);

            // Proxy share (public, no auth)
            config.routes.get("/share/proxy/{vhostId}/view",                proxy::apiShare);
            config.routes.get("/share/proxy/{vhostId}/text",                proxy::apiShareText);

            config.routes.exception(Exception.class, (e, ctx) -> {
                logger.error("Uncaught exception on {}", ctx.path(), e);
                var msg = e.getMessage() != null ? e.getMessage() : "Internal Server Error";
                if (ctx.path().startsWith("/api/")) {
                    ctx.status(500).result(msg);
                } else {
                    var model = new HashMap<String, Object>();
                    model.put("requestPath", ctx.path());
                    model.put("message", msg);
                    ctx.status(500).render("templates/error/error.pebble", model);
                }
            });
        });

        return app;
    }

    private static FileRenderer createRenderer(boolean dev, java.util.Map<String, Messages> messagesMap) {
        var builder = new PebbleEngine.Builder();
        if (dev) {
            var prefix = Path.of("src/main/resources").toAbsolutePath() + "/";
            builder.loader(new FileLoader(prefix)).cacheActive(false);
        }
        var pebble = builder.build();
        return (filePath, model, ctx) -> {
            String locale = LocaleContext.get();
            Messages messages = messagesMap.getOrDefault(locale, messagesMap.get("en"));
            var enriched = new java.util.HashMap<String, Object>(model);
            enriched.put("msg", messages.asMap());
            enriched.put("msgJson", messages.toJson());
            enriched.put("currentLocale", locale);
            var writer = new java.io.StringWriter();
            try {
                pebble.getTemplate(filePath).evaluate(writer, enriched);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return writer.toString();
        };
    }

    static String h2JdbcUrl() {
        var dbPath = AppHome.oeHubDir().resolve("data").resolve("oeHub-h2")
                         .toAbsolutePath().toString().replace("\\", "/");
        return "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=TRUE";
    }

    public static void main(String[] args) throws Exception {

        Security.addProvider(new BouncyCastleProvider());

        appPort = resolvePort();
        h2ConsolePort = appPort + 1;

        var oeHubDir = AppHome.oeHubDir();
        writeH2Properties(oeHubDir, h2JdbcUrl());
        Server.createWebServer("-webPort", String.valueOf(h2ConsolePort),
                "-properties", oeHubDir.toString()).start();
        logger.info("H2 Console: http://localhost:{}/login.do?setting=oeHub  (user: sa / password: oeHub)", h2ConsolePort);

        var sqlSessionFactory = DatabaseConfig.buildSqlSessionFactory();
        ReverseProxyServer.init(sqlSessionFactory);
        createApp(sqlSessionFactory).start(appPort);

        try {
            ReverseProxyServer.startSslPassServer();
        } catch (tricatch.oe.proxy.exception.NotReadyCaException e) {
            logger.warn("SSL proxy not started: CA certificate not configured yet. ({})", e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to start SSL proxy server: " + e.getMessage(), e);
        }
    }

    private static void writeH2Properties(Path dir, String jdbcUrl) throws IOException {
        Files.createDirectories(dir);
        var propsFile = dir.resolve(".h2.server.properties");
        var props = new Properties();
        if (Files.exists(propsFile)) {
            try (var in = Files.newInputStream(propsFile)) {
                props.load(in);
            }
        }
        var entry = "oeHub|org.h2.Driver|" + jdbcUrl + "|sa";
        var updated = false;
        for (var key : props.stringPropertyNames()) {
            if (props.getProperty(key).startsWith("oeHub|")) {
                props.setProperty(key, entry);
                updated = true;
                break;
            }
        }
        if (!updated) {
            var maxIndex = props.stringPropertyNames().stream()
                .filter(k -> k.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max().orElse(-1);
            props.setProperty(String.valueOf(maxIndex + 1), entry);
        }
        try (var out = Files.newOutputStream(propsFile)) {
            props.store(out, null);
        }
    }
}
