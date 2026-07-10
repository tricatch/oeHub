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

    private final SqlSessionFactory sqlSessionFactory;
    private final SettingsController settings;
    private final ObjectMapper objectMapper;

    public SetupController(SqlSessionFactory sqlSessionFactory, SettingsController settings, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public static boolean isSetupComplete() {
        return setupComplete;
    }

    public void refreshSetupState() {
        boolean adminOk;
        try (var session = sqlSessionFactory.openSession()) {
            adminOk = session.getMapper(HubConfMapper.class).findByConfKey("admin") != null;
        }
        boolean caOk = settings.isCaConfigured();
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
        if (password.length() < 4) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.password.too.short", "", "generate"));
            return;
        }
        if (!password.equals(confirm)) {
            ctx.render("templates/setup.pebble", buildModel("auth.error.password.mismatch", "", "generate"));
            return;
        }

        try (var session = sqlSessionFactory.openSession(true)) {
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
        }

        refreshSetupState();
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
            var url = mapper.findById(urlId);
            if (url == null) { ctx.status(404); return; }
            if (body.containsKey("urlName")) url.setUrlName(((String) body.get("urlName")).trim());
            if (body.containsKey("urlValue")) url.setUrlValue(((String) body.get("urlValue")).trim());
            url.setUpdatedAt(LocalDateTime.now());
            mapper.update(url);
            ctx.json(url);
        }
    }

    public void apiSetupUrlDelete(Context ctx) {
        var urlId = ctx.pathParam("urlId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUrlMapper.class).deleteById(urlId);
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiSetupUrlReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var url = mapper.findById((String) ids.get(i));
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
            var ua = mapper.findById(uaId);
            if (ua == null) { ctx.status(404); return; }
            if (body.containsKey("uaName")) ua.setUaName(((String) body.get("uaName")).trim());
            if (body.containsKey("uaValue")) ua.setUaValue(((String) body.get("uaValue")).trim());
            ua.setUpdatedAt(LocalDateTime.now());
            mapper.update(ua);
            ctx.json(ua);
        }
    }

    public void apiSetupUaDelete(Context ctx) {
        var uaId = ctx.pathParam("uaId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUaMapper.class).deleteById(uaId);
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiSetupUaReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var ua = mapper.findById((String) ids.get(i));
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
