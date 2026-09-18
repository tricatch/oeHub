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
import tricatch.oe.hub.config.BackupService;
import tricatch.oe.hub.config.DatabaseConfig;
import tricatch.oe.hub.config.JwtService;
import tricatch.oe.hub.controller.AdminHostsUaController;
import tricatch.oe.hub.controller.AdminHostsUrlController;
import tricatch.oe.hub.controller.OidExtensionController;
import tricatch.oe.hub.controller.AdminUserController;
import tricatch.oe.hub.controller.AuditLogController;
import tricatch.oe.hub.controller.AuthController;
import tricatch.oe.hub.controller.SetupController;
import tricatch.oe.hub.controller.SettingsController;
import tricatch.oe.hub.controller.UserController;
import tricatch.oe.hub.controller.WorkspaceController;
import tricatch.oe.hosts.controller.HostsController;
import tricatch.oe.fwdproxy.BlockedPageServer;
import tricatch.oe.fwdproxy.ForwardProxyServer;
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

    private static final String CSRF_COOKIE_NAME = "oe_csrf";

    private static String generateCsrfToken() {
        var bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // A fresh value every single request (unlike the CSRF token, which is stable per browser) -
    // reusing one across requests would let an attacker who ever saw it once (e.g. in a cached
    // response) replay it to get their own injected <script> past the CSP below.
    private static String generateCspNonce() {
        var bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    // script-src is the directive that actually matters here: 'self' covers our own /js/*.js
    // files, 'nonce-<value>' covers every inline <script nonce="{{ cspNonce }}"> the templates
    // render, and the two CDN hosts cover Bootstrap/Ace/js-yaml (all pinned with SRI integrity
    // hashes in the templates themselves, on top of this). Without 'strict-dynamic', host sources
    // and the nonce both stay in effect together, so Ace's own dynamic loading of its worker/mode
    // files from the same cdnjs origin still works.
    //
    // style-src stays at 'unsafe-inline': the templates use inline style="..." attributes
    // pervasively (icon sizing etc.), and CSS injection can't read IndexedDB or exfiltrate the
    // workspace key the way injected JS can - script-src is where the real value is, so that's
    // where the strictness goes.
    private static String buildCsp(String nonce) {
        return "default-src 'self'; "
            + "script-src 'self' 'nonce-" + nonce + "' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com; "
            + "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; "
            + "img-src 'self' data:; "
            + "font-src 'self' data:; "
            + "connect-src 'self'; "
            + "worker-src 'self' https://cdnjs.cloudflare.com blob:; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'; "
            + "frame-ancestors 'self'";
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int resolvePort() {
        String prop = System.getProperty("oe.port");
        if (prop != null && !prop.isBlank()) {
            try { return Integer.parseInt(prop.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return DEFAULT_PORT;
    }

    private static String resolveVersion() {
        String sysProp = System.getProperty("oe.app.version");
        if (sysProp != null && !sysProp.isBlank()) return sysProp;
        String manifest = OeHubApplication.class.getPackage().getImplementationVersion();
        return manifest != null ? manifest : "unknown";
    }

    public static Javalin createApp(SqlSessionFactory sqlSessionFactory) {

        boolean dev = "true".equals(System.getProperty("oe.dev"));

        var messagesMap = java.util.Map.of("en", new Messages("en"), "ko", new Messages("ko"));

        var objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // oe.mode=workspace disables oeProxy entirely (cloudGroupService design doc §2.6) - the
        // controller itself is never constructed, not just unrouted, so no CA/proxy-config file
        // I/O can happen from a code path that's supposed to not exist in this mode.
        boolean workspaceMode = AppHome.isWorkspaceMode();

        var jwtService  = new JwtService(sqlSessionFactory);
        var settings    = new SettingsController(sqlSessionFactory, objectMapper);
        var auth        = new AuthController(sqlSessionFactory, jwtService);
        var setup       = new SetupController(sqlSessionFactory, settings, objectMapper, auth);
        var hosts       = new HostsController(sqlSessionFactory, settings, objectMapper);
        var proxy       = workspaceMode ? null : new ProxyController(sqlSessionFactory, objectMapper);
        var userCtrl    = new UserController(sqlSessionFactory, objectMapper);
        var adminUser   = new AdminUserController(sqlSessionFactory);
        var auditLogCtrl = new AuditLogController(sqlSessionFactory);
        var adminUa     = new AdminHostsUaController(sqlSessionFactory, objectMapper);
        var adminUrl    = new AdminHostsUrlController(sqlSessionFactory, objectMapper);
        var oidExtension = new OidExtensionController(settings);
        // Instance-admin workspace console (cloudGroupService design doc §2.5/§3 item 2) -
        // workspace mode only, so constructed unconditionally here (cheap, no I/O) but only
        // routed below under "if (workspaceMode)", same pattern as ProxyController's inverse.
        var workspaceCtrl = new WorkspaceController(sqlSessionFactory);

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

            // CSRF protection (double-submit cookie): a per-browser random token is set as a
            // non-HttpOnly cookie so client JS can read and echo it back; every state-changing
            // request must present the same value via header or form field. A cross-site page
            // can trigger the request (form/fetch) but cannot read this origin's cookie to
            // produce a matching token, so the request is rejected. Runs first so the token
            // exists before any other filter or handler needs it.
            config.routes.before(ctx -> {
                String token = ctx.cookie(CSRF_COOKIE_NAME);
                if (token == null || token.isBlank()) {
                    token = generateCsrfToken();
                    var sb = new StringBuilder(CSRF_COOKIE_NAME).append('=').append(token).append("; Path=/; SameSite=Lax");
                    if ("https".equalsIgnoreCase(ctx.scheme())) sb.append("; Secure");
                    ctx.res().addHeader("Set-Cookie", sb.toString());
                }
                ctx.attribute("csrfToken", token);
            });
            config.routes.before(ctx -> {
                var method = ctx.req().getMethod();
                if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)) {
                    String cookieToken = ctx.cookie(CSRF_COOKIE_NAME);
                    String suppliedToken = ctx.header("X-CSRF-Token");
                    if (suppliedToken == null) suppliedToken = ctx.formParam("_csrf");
                    if (cookieToken == null || suppliedToken == null || !constantTimeEquals(cookieToken, suppliedToken)) {
                        ctx.status(403).result("CSRF token missing or invalid");
                        ctx.skipRemainingHandlers();
                    }
                }
            });

            // Content-Security-Policy, nonce-based on script-src (see buildCsp/generateCspNonce) -
            // runs early, before any handler needs the nonce for rendering, same as the CSRF pair
            // above.
            config.routes.before(ctx -> {
                String nonce = generateCspNonce();
                ctx.attribute("cspNonce", nonce);
                ctx.res().addHeader("Content-Security-Policy", buildCsp(nonce));
            });

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
            io.javalin.http.Handler settingsAdminOnly = ctx -> {
                var user = AuthController.currentUser(ctx);
                if (user == null || !"adm".equals(user.getRole())) {
                    ctx.status(403).result("Forbidden");
                    ctx.skipRemainingHandlers();
                }
            };
            config.routes.before("/oehub/settings", settingsAdminOnly);
            config.routes.before("/oehub/settings/*", settingsAdminOnly);
            // Instance-admin workspace console (design doc §2.5) is 'adm'-only, unlike the
            // ws_adm-reachable "/oehub/admin/*" prefix below - a workspace's own ws_adm must never
            // reach this instance-wide screen (isolation principle), so it needs the stricter
            // settingsAdminOnly check, not the isWorkspaceAdmin one.
            if (workspaceMode) {
                config.routes.before("/oehub/admin/workspaces", settingsAdminOnly);
                config.routes.before("/api/admin/workspaces", settingsAdminOnly);
                config.routes.before("/api/admin/workspaces/*", settingsAdminOnly);
            }
            // User management (member list, pending approval) is workspace-scoped: a workspace's
            // own ws_adm needs it, not just the instance 'adm' - see AuthController.isWorkspaceAdmin.
            // The workspace console ("/oehub/admin/workspaces", "/api/admin/workspaces*") is the
            // opposite - instance 'adm' only, already gated (more strictly) by settingsAdminOnly
            // above - so it's excluded here rather than rejected: in workspace mode isWorkspaceAdmin()
            // deliberately excludes 'adm' (design doc §2.5 isolation), which would otherwise block
            // the very instance admin this screen is for.
            config.routes.before("/oehub/admin/*", ctx -> {
                if (workspaceMode && "/oehub/admin/workspaces".equals(ctx.path())) return;
                if (!AuthController.isWorkspaceAdmin(AuthController.currentUser(ctx))) {
                    ctx.status(403).result("Forbidden");
                    ctx.skipRemainingHandlers();
                }
            });
            config.routes.before("/api/admin/*", ctx -> {
                if (workspaceMode && ctx.path().startsWith("/api/admin/workspaces")) return;
                if (!AuthController.isWorkspaceAdmin(AuthController.currentUser(ctx))) {
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
                // oeProxy CA certificate download is deliberately public — a device installing
                // the root CA to trust the SSL reverse proxy may not have (or need) an oeHub
                // session yet. See the route registration below for the full rationale.
                if ("/api/proxy/ca".equals(ctx.path())) {
                    return;
                }
                // Password recovery must be reachable by a locked-out, logged-out caller by
                // definition - it's the account-level auth check itself (recovery verifier /
                // bcrypt), same trust boundary as /login (e2eEncryption design doc §3 "복구 플로우
                // 프로토콜").
                if ("/api/recover/verify".equals(ctx.path()) || "/api/recover/reset".equals(ctx.path())) {
                    return;
                }
                if (AuthController.currentUser(ctx) == null) {
                    ctx.status(401).result("Unauthorized");
                    ctx.skipRemainingHandlers();
                }
            });
            // The /setup/* CRUD routes (hosts-url, hosts-ua, oid-domain-default, ca/generate,
            // ca/import) exist so the first-run wizard can manage global presets and the CA
            // before any admin account/session exists. They must lock down as soon as an admin
            // account exists — not only once the whole wizard (admin + CA) is complete — otherwise
            // there is a window after admin creation, before the CA step, where any unauthenticated
            // caller could POST /setup/ca/import and install their own root CA as the trust root
            // for the entire SSL reverse proxy. SetupController.processSetup logs the newly-created
            // admin in immediately so the legitimate wizard flow keeps working once this closes.
            config.routes.before("/setup/*", ctx -> {
                if (SetupController.isAdminConfigured()) {
                    var user = AuthController.currentUser(ctx);
                    if (user == null || !"adm".equals(user.getRole())) {
                        ctx.status(403).result("Forbidden");
                        ctx.skipRemainingHandlers();
                    }
                }
            });

            config.routes.after(ctx -> {
                LocaleContext.clear();
                // Prevents the app from being embedded in a foreign <iframe>/<frame>, which
                // would otherwise allow clickjacking (e.g. an invisible overlay tricking a
                // logged-in admin into clicking "grant admin" or "delete user").
                ctx.header("X-Frame-Options", "DENY");
                var path = ctx.path();
                if (!path.startsWith("/css/") && !path.startsWith("/icon/") && !path.startsWith("/logo/") && !path.startsWith("/js/")) {
                    ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                    ctx.header("Pragma", "no-cache");
                    ctx.header("Expires", "0");
                }
            });

            config.routes.get("/setup", setup::showSetup);
            config.routes.post("/setup", setup::processSetup);
            if (!workspaceMode) {
                // CA setup and oID (X-OeHub-Oid) domain defaults only make sense for oeProxy,
                // which doesn't exist in workspace mode - cloudGroupService design doc §2.6.
                config.routes.post("/setup/ca/generate", setup::generateCa);
                config.routes.post("/setup/ca/import",   setup::importCa);
                config.routes.post("/setup/oid-domain-default", setup::saveOidDomainDefault);
            }
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
            config.routes.get("/recover", auth::showRecover);
            config.routes.post("/api/recover/verify", auth::apiRecoverVerify);
            config.routes.post("/api/recover/reset",  auth::apiRecoverReset);

            // Admin settings
            config.routes.get("/oehub/settings",              settings::showSettings);
            config.routes.post("/api/admin/settings/oid-domain-default", settings::apiSaveOidDomainDefault);
            config.routes.post("/api/admin/settings/backup-interval", settings::apiSaveBackupInterval);
            if (!workspaceMode) {
                // IP identifier / forward-proxy whitelist / CA are all oeProxy-only concerns -
                // meaningless (and their backing servers non-existent) under workspace mode.
                config.routes.post("/api/admin/settings/identifier",         settings::apiSaveIdentifier);
                config.routes.post("/api/admin/settings/trust-internal-cert", settings::apiSaveTrustInternalCert);
                config.routes.post("/api/admin/settings/fwdproxy-whitelist", settings::apiSaveFwdProxyWhitelist);
                config.routes.post("/oehub/settings/ca/generate", settings::generateCa);
                config.routes.post("/oehub/settings/ca/import",   settings::importCa);
            }

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
            config.routes.get("/api/hosts/{hostsId}/view",         hosts::apiGetForView);
            config.routes.post("/api/hosts/{hostsId}/link",        hosts::apiSetLink);
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
            // Fully-public link (public, no auth, no workspace membership - e2eEncryption design
            // doc §6's last item) - a distinct trust boundary from /share above.
            config.routes.get("/link/{hostsId}/oelink",             hosts::apiPublicLink);
            config.routes.get("/link/{hostsId}/oelink/text",        hosts::apiPublicLinkText);

            // My info
            config.routes.get("/oehub/my/info", ctx -> {
                var user = AuthController.currentUser(ctx);
                var fmt  = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                var model = new HashMap<String, Object>();
                model.put("user", user);
                model.put("createAt", user.getCreateAt() != null ? user.getCreateAt().format(fmt) : "");
                model.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().format(fmt) : "");
                if (workspaceMode) {
                    try (var session = sqlSessionFactory.openSession()) {
                        var ws = session.getMapper(tricatch.oe.hub.mapper.WorkspaceMapper.class).findByWsNo(user.getWsNo());
                        model.put("wsName", ws != null ? ws.getWsName() : "");
                    }
                }
                ctx.render("templates/oehub/my-info.pebble", model);
            });

            // Licenses
            config.routes.get("/oehub/licenses", ctx -> {
                var model = new HashMap<String, Object>();
                model.put("user", AuthController.currentUser(ctx));
                ctx.render("templates/oehub/licenses.pebble", model);
            });

            if (!workspaceMode) {
                // oeProxy UI
                config.routes.get("/oehub/proxy", proxy::showProxy);
                config.routes.get("/oehub/proxy/monitor", proxy::showMonitor);

                // oeProxy SSE monitor event stream
                config.routes.get("/api/proxy/monitor/event", proxy::monitorEvent);

                // oeProxy CA certificate download (no auth required — browser needs to install)
                config.routes.get("/api/proxy/ca", proxy::apiDownloadCa);

                // oeProxy "Use This IP" - claim the current browsing IP for IP-based owner fallback
                config.routes.post("/api/proxy/take-ip", proxy::apiTakeIp);

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
            }

            // User backup / restore
            config.routes.get("/api/user/backup",   userCtrl::apiBackup);
            config.routes.post("/api/user/restore",  userCtrl::apiRestore);
            config.routes.post("/api/user/change-password", userCtrl::apiChangePassword);
            config.routes.get("/api/user/crypto-keys", userCtrl::apiMyCryptoKeys);
            config.routes.post("/api/user/recovery-key", userCtrl::apiReissueRecoveryKey);
            // Personal API tokens: disabled for now (2026-09-16) - unclear real-world use case
            // yet. UserController.apiListApiTokens/apiCreateApiToken/apiDeleteApiToken and
            // AuthController.resolveUserFromApiToken are left in place, just unrouted/uncalled,
            // so this is cheap to turn back on later.
            // config.routes.get("/api/user/api-tokens",                userCtrl::apiListApiTokens);
            // config.routes.post("/api/user/api-tokens",               userCtrl::apiCreateApiToken);
            // config.routes.delete("/api/user/api-tokens/{tokenId}",   userCtrl::apiDeleteApiToken);

            // Admin: user management
            config.routes.get("/oehub/admin/users",               adminUser::showUsers);
            config.routes.get("/api/admin/users",                 adminUser::apiSearch);
            config.routes.get("/api/admin/users/pending",         adminUser::apiListPending);
            config.routes.post("/api/admin/users/{userNo}/approve", adminUser::apiApprovePending);
            config.routes.post("/api/admin/users/{userNo}/reject",  adminUser::apiRejectPending);
            config.routes.get("/api/admin/invites",               adminUser::apiListInvites);
            config.routes.post("/api/admin/invites",              adminUser::apiCreateInvite);
            config.routes.patch("/api/admin/users/{userNo}/role",  adminUser::apiSetRole);
            // Admin-initiated password reset only ever changes the server-side password hash - it
            // cannot re-wrap wrapped_private_key (that requires the OLD password, which the admin
            // never has), so under workspace mode it would silently strand the target member unable
            // to decrypt their own workspace content (e2eEncryption design doc §3/§9). Self-hosted's
            // identity crypto is a dummy placeholder (design doc §1), so the same action is harmless
            // there and stays available. The self-service /recover flow (which DOES re-wrap, via the
            // recovery code) remains the only supported recovery path in workspace mode.
            if (!workspaceMode) {
                config.routes.post("/api/admin/users/{userNo}/reset-password", adminUser::apiResetPassword);
            }
            config.routes.get("/api/admin/workspace/rotation-rows", adminUser::apiWorkspaceRotationRows);
            config.routes.post("/api/admin/workspace/rotate",      adminUser::apiRotateWorkspaceKey);
            config.routes.delete("/api/admin/users/{userNo}",      adminUser::apiDeleteUser);

            // Admin: Tier-1 audit log - reachable by whoever "/oehub/admin/*" already lets in
            // (ws_adm, or self-hosted's 'adm' - see AuthController.isWorkspaceAdmin), no separate
            // gating needed since AuditLogController itself always scopes to the caller's own ws_no.
            config.routes.get("/oehub/admin/audit-log",           auditLogCtrl::showAuditLog);
            config.routes.get("/api/admin/audit-log",             auditLogCtrl::apiListAuditLog);

            // Admin: instance-wide workspace console (cloudGroupService design doc §2.5/§3 item 2)
            // - workspace mode only, meaningless in self-hosted which has exactly one workspace
            // (itself, design doc §2.7). 'adm'-only, gated above via settingsAdminOnly.
            if (workspaceMode) {
                config.routes.get("/oehub/admin/workspaces",              workspaceCtrl::showWorkspaces);
                config.routes.get("/api/admin/workspaces",                workspaceCtrl::apiListWorkspaces);
                config.routes.patch("/api/admin/workspaces/{wsNo}/status", workspaceCtrl::apiUpdateWorkspaceStatus);
            }

            // Admin: teams (cloudGroupService design doc §2.9) - workspace mode only, meaningless
            // in self-hosted's single-workspace-is-the-instance model (design doc §2.7). Same
            // ws_adm scoping/guard as the rest of "/api/admin/*" above - no separate "team admin" role.
            if (workspaceMode) {
                config.routes.get("/api/admin/teams",                 adminUser::apiListTeams);
                config.routes.post("/api/admin/teams",                adminUser::apiCreateTeam);
                config.routes.patch("/api/admin/teams/{teamNo}",      adminUser::apiRenameTeam);
                config.routes.delete("/api/admin/teams/{teamNo}",     adminUser::apiDeleteTeam);
                config.routes.patch("/api/admin/users/{userNo}/team", adminUser::apiSetUserTeam);
            }

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
            if (!workspaceMode) {
                config.routes.get("/api/proxy/conf/{name}",                     proxy::apiConfGet);
                config.routes.put("/api/proxy/conf/{name}",                     proxy::apiConfSet);

                // Proxy share (public, no auth)
                config.routes.get("/share/proxy/{vhostId}/view",                proxy::apiShare);
                config.routes.get("/share/proxy/{vhostId}/text",                proxy::apiShareText);
            }

            config.routes.exception(Exception.class, (e, ctx) -> {
                // Full detail (including e.getMessage(), which can contain internal paths or
                // driver/library text) goes to the server log only - returning it to the client
                // would leak implementation details to anonymous callers on any unauthenticated
                // route (e.g. /login, /register, /setup).
                logger.error("Uncaught exception on {}", ctx.path(), e);
                if (ctx.path().startsWith("/api/")) {
                    ctx.status(500).result("Internal Server Error");
                } else {
                    var model = new HashMap<String, Object>();
                    model.put("requestPath", ctx.path());
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
            // Templates hide Proxy nav/UI entirely under oe.mode=workspace - see AppHome.isWorkspaceMode.
            enriched.put("workspaceMode", AppHome.isWorkspaceMode());
            // Set by the CSRF before-filter on this same request; read from the Context attribute
            // (not ctx.cookie()) because a freshly-generated token isn't echoed back in the
            // request's own Cookie header until the browser's next request.
            enriched.put("csrfToken", ctx.attribute("csrfToken"));
            // Set by the CSP before-filter on this same request - every inline <script> tag must
            // carry this exact value (nonce="{{ cspNonce }}") or the browser refuses to run it.
            enriched.put("cspNonce", ctx.attribute("cspNonce"));
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
        var dbPath = AppHome.dbFilePath().toAbsolutePath().toString().replace("\\", "/");
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
        // Credentials intentionally not logged here (see DatabaseConfig) - keeps them out of log
        // files/aggregation even though the /oehub/h2 route itself already requires admin login.
        logger.info("H2 Console: http://localhost:{}/login.do?setting=oeHub", h2ConsolePort);

        var sqlSessionFactory = DatabaseConfig.buildSqlSessionFactory();
        ReverseProxyServer.init(sqlSessionFactory);
        ForwardProxyServer.init(sqlSessionFactory);
        BackupService.init(sqlSessionFactory);
        createApp(sqlSessionFactory).start(appPort);

        // oe.mode=workspace never starts any of the network-level Proxy servers - see
        // cloudGroupService design doc §2.6. ReverseProxyServer.init()/ForwardProxyServer.init()
        // just above are cheap, side-effect-light state setup (OID secret, whitelist load) left
        // unconditional rather than touching the restricted tricatch.oe.proxy package further.
        if (!AppHome.isWorkspaceMode()) {
            try {
                ReverseProxyServer.startSslPassServer();
            } catch (tricatch.oe.proxy.exception.NotReadyCaException e) {
                logger.warn("SSL proxy not started: CA certificate not configured yet. ({})", e.getMessage());
            } catch (Exception e) {
                logger.error("Failed to start SSL proxy server: " + e.getMessage(), e);
            }

            try {
                ForwardProxyServer.start();
            } catch (Exception e) {
                logger.error("Failed to start forward proxy server: " + e.getMessage(), e);
            }

            try {
                BlockedPageServer.start(SettingsController.caCertPath(), SettingsController.caKeyPath());
            } catch (Exception e) {
                logger.error("Failed to start forward-proxy blocked-page server: " + e.getMessage(), e);
            }
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
