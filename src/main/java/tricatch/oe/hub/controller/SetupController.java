package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.hosts.model.HostsUa;
import tricatch.oe.hosts.model.HostsUrl;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubConf;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.proxy.ReverseProxyServer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SetupController {

    private static final Logger logger = LoggerFactory.getLogger(SetupController.class);

    private static volatile boolean setupComplete = false;
    // Read by the /setup/* auth-gating filter in OeHubApplication to lock down ca/generate,
    // ca/import, and the preset CRUD routes as soon as an admin account exists — independent of
    // whether the CA step is done yet. See that filter's comment for why.
    private static volatile boolean adminConfigured = false;
    // Guards the admin-existence-check + insert in processSetup so two concurrent first-run
    // submissions can't both pass the check and create two admin accounts (only one JVM ever
    // runs this, so a plain in-process lock is enough — no need for DB-level locking).
    private static final Object SETUP_LOCK = new Object();

    private final SqlSessionFactory sqlSessionFactory;
    private final SettingsController settings;
    private final ObjectMapper objectMapper;
    private final AuthController authController;

    public SetupController(SqlSessionFactory sqlSessionFactory, SettingsController settings, ObjectMapper objectMapper,
                            AuthController authController) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.authController = authController;
    }

    public static boolean isSetupComplete() {
        return setupComplete;
    }

    public static boolean isAdminConfigured() {
        return adminConfigured;
    }

    public void refreshSetupState() {
        boolean adminOk;
        try (var session = sqlSessionFactory.openSession()) {
            adminOk = session.getMapper(HubConfMapper.class).findByConfKey("admin") != null;
        }
        boolean caOk = settings.isCaConfigured();
        adminConfigured = adminOk;
        setupComplete = adminOk && caOk;
        logger.info("Setup state refreshed — admin={}, ca={}, complete={}", adminOk, caOk, setupComplete);
    }

    public void showSetup(Context ctx) {
        ctx.render("templates/setup.pebble", buildModel("", "", "generate"));
    }

    public void processSetup(Context ctx) {
        try (var session = sqlSessionFactory.openSession()) {
            if (session.getMapper(HubConfMapper.class).findByConfKey("admin") != null) {
                ctx.render("templates/setup.pebble", buildModel("setup.error.admin.already.configured", "", "generate"));
                return;
            }
        }

        var userId   = ctx.formParam("userId");
        var password = ctx.formParam("password");
        var confirm  = ctx.formParam("confirm");

        if (userId == null || userId.isBlank()) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.userid.required", "", "generate"));
            return;
        }
        if (userId.length() < 4) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.userid.too.short", "", "generate"));
            return;
        }
        if (!userId.matches("[A-Za-z0-9._-]+")) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.userid.invalid.chars", "", "generate"));
            return;
        }
        if (password == null || password.isBlank()) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.password.required", "", "generate"));
            return;
        }
        if (password.length() < 8) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.password.too.short", "", "generate"));
            return;
        }
        if (!password.equals(confirm)) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.password.mismatch", "", "generate"));
            return;
        }

        synchronized (SETUP_LOCK) {
            try (var session = sqlSessionFactory.openSession()) {
                if (session.getMapper(HubConfMapper.class).findByConfKey("admin") != null) {
                    ctx.render("templates/setup.pebble", buildModel("setup.error.admin.already.configured", "", "generate"));
                    return;
                }

                var now = LocalDateTime.now();
                var hubUser = new HubUser();
                hubUser.setUserId(userId);
                hubUser.setPassword(PasswordUtil.hash(password));
                hubUser.setRole("adm");
                hubUser.setUpdatedAt(now);
                hubUser.setCreateAt(now);
                session.getMapper(HubUserMapper.class).insert(hubUser);

                var conf = new HubConf();
                conf.setConfKey("admin");
                conf.setConfVal(userId);
                conf.setUpdatedAt(now);
                session.getMapper(HubConfMapper.class).upsert(conf);

                session.commit();

                refreshSetupState();
                // Log the new admin in immediately: as soon as adminConfigured flips to true (just
                // above), OeHubApplication's /setup/* filter requires an authenticated admin for
                // the remaining wizard steps (CA generate/import, presets) — without this, the
                // creator would be locked out of their own setup wizard.
                authController.loginAs(ctx, hubUser, true);
            }
        }

        ctx.redirect("/setup");
    }

    public void generateCa(Context ctx) {
        if (settings.isCaConfigured()) {
            ctx.render("templates/setup.pebble", buildModel("", "setup.error.ca.already.configured", "generate"));
            return;
        }
        var caName = ctx.formParam("caName");
        if (caName == null || caName.isBlank()) {
            ctx.render("templates/setup.pebble", buildModel("", "settings.ca.error.name.required", "generate"));
            return;
        }
        try {
            settings.generateCaToDir(caName);
            logger.info("Setup: CA certificate generated — {}", caName);
            startProxyServer();
            refreshSetupState();
            ctx.redirect("/setup");
        } catch (Exception e) {
            logger.error("Setup: failed to generate CA certificate", e);
            ctx.render("templates/setup.pebble", buildModel("", "settings.ca.error.generate.failed", "generate"));
        }
    }

    public void importCa(Context ctx) {
        if (settings.isCaConfigured()) {
            ctx.render("templates/setup.pebble", buildModel("", "setup.error.ca.already.configured", "import"));
            return;
        }
        var certFile = ctx.uploadedFile("certFile");
        var keyFile  = ctx.uploadedFile("keyFile");

        if (certFile == null) {
            ctx.render("templates/setup.pebble", buildModel("", "settings.ca.error.cert.required", "import"));
            return;
        }
        if (keyFile == null) {
            ctx.render("templates/setup.pebble", buildModel("", "settings.ca.error.key.required", "import"));
            return;
        }
        try {
            settings.importCaToDir(certFile.content().readAllBytes(), keyFile.content().readAllBytes());
            logger.info("Setup: CA certificate imported.");
            startProxyServer();
            refreshSetupState();
            ctx.redirect("/setup");
        } catch (Exception e) {
            logger.error("Setup: failed to import CA certificate", e);
            ctx.render("templates/setup.pebble", buildModel("", "settings.ca.error.import.failed", "import"));
        }
    }

    private void startProxyServer() {
        try {
            ReverseProxyServer.startSslPassServer();
            logger.info("SSL proxy server started.");
        } catch (Exception e) {
            logger.warn("SSL proxy server could not be started: {}", e.getMessage());
        }
    }

    public void apiSetupUrlList(Context ctx) {
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUrlMapper.class).findAll());
        }
    }

    public void apiSetupUrlCreate(Context ctx) throws Exception {
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var urlName = (String) body.get("urlName");
        var urlValue = (String) body.get("urlValue");
        if (urlName == null || urlName.isBlank() || urlValue == null || urlValue.isBlank()) {
            ctx.status(400); return;
        }
        var now = LocalDateTime.now();
        var url = new HostsUrl();
        url.setUrlId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        url.setUrlName(urlName.trim());
        url.setUrlValue(urlValue.trim());
        url.setCreateAt(now);
        url.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            url.setSortOrder(mapper.nextSortOrder());
            mapper.insert(url);
        }
        ctx.json(url).status(201);
    }

    public void apiSetupUrlUpdate(Context ctx) throws Exception {
        var urlId = ctx.pathParam("urlId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var url = mapper.findByIdGlobal(urlId);
            if (url == null) { ctx.status(404); return; }
            if (body.get("urlName") instanceof String s) url.setUrlName(s.trim());
            if (body.get("urlValue") instanceof String s) url.setUrlValue(s.trim());
            url.setUpdatedAt(LocalDateTime.now());
            mapper.update(url);
            ctx.json(url);
        }
    }

    public void apiSetupUrlDelete(Context ctx) {
        var urlId = ctx.pathParam("urlId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUrlMapper.class).deleteByIdGlobal(urlId);
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiSetupUrlReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var url = mapper.findByIdGlobal((String) ids.get(i));
                if (url == null) continue;
                url.setSortOrder(i);
                url.setUpdatedAt(LocalDateTime.now());
                mapper.update(url);
            }
        }
        ctx.status(204);
    }

    public void apiSetupUaList(Context ctx) {
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUaMapper.class).findAll());
        }
    }

    public void apiSetupUaCreate(Context ctx) throws Exception {
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var uaName = (String) body.get("uaName");
        var uaValue = (String) body.get("uaValue");
        if (uaName == null || uaName.isBlank() || uaValue == null || uaValue.isBlank()) {
            ctx.status(400); return;
        }
        var now = LocalDateTime.now();
        var ua = new HostsUa();
        ua.setUaId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        ua.setUaName(uaName.trim());
        ua.setUaValue(uaValue.trim());
        ua.setCreateAt(now);
        ua.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            ua.setSortOrder(mapper.nextSortOrder());
            mapper.insert(ua);
        }
        ctx.json(ua).status(201);
    }

    public void apiSetupUaUpdate(Context ctx) throws Exception {
        var uaId = ctx.pathParam("uaId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var ua = mapper.findByIdGlobal(uaId);
            if (ua == null) { ctx.status(404); return; }
            if (body.get("uaName") instanceof String s) ua.setUaName(s.trim());
            if (body.get("uaValue") instanceof String s) ua.setUaValue(s.trim());
            ua.setUpdatedAt(LocalDateTime.now());
            mapper.update(ua);
            ctx.json(ua);
        }
    }

    public void apiSetupUaDelete(Context ctx) {
        var uaId = ctx.pathParam("uaId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUaMapper.class).deleteByIdGlobal(uaId);
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiSetupUaReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var ua = mapper.findByIdGlobal((String) ids.get(i));
                if (ua == null) continue;
                ua.setSortOrder(i);
                ua.setUpdatedAt(LocalDateTime.now());
                mapper.update(ua);
            }
        }
        ctx.status(204);
    }

    public void saveOidDomainDefault(Context ctx) {
        var domainList = ctx.formParam("oidDomainDefault");
        if (domainList == null) domainList = "";
        settings.saveOidDomainDefault(domainList.trim());
        ctx.redirect("/setup?savedOid=1");
    }

    private HashMap<String, Object> buildModel(String adminError, String caError, String caActiveTab) {
        var model = new HashMap<String, Object>();
        model.put("adminError", adminError);
        model.put("caError", caError);
        model.put("caActiveTab", caActiveTab);
        model.put("defaultCaName", "oeProxy Self Root CA - " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd-HHmm")));

        try (var session = sqlSessionFactory.openSession()) {
            var adminConf = session.getMapper(HubConfMapper.class).findByConfKey("admin");
            model.put("adminConfigured", adminConf != null);
            model.put("adminUserId", adminConf != null ? adminConf.getConfVal() : "");
        }

        model.put("caConfigured", settings.isCaConfigured());
        model.put("ca", settings.loadCaInfo());

        var oidDomainDefault = settings.getOidDomainDefault();
        model.put("oidDomainConfigured", !oidDomainDefault.isBlank());
        model.put("oidDomainDefault", oidDomainDefault);

        return model;
    }
}
