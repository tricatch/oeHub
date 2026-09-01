package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tricatch.gotpache.cert.CertificateKeyPair;
import io.github.tricatch.gotpache.cert.KeyTool;
import io.github.tricatch.gotpache.cert.RootCertificateCreator;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.config.AppHome;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.model.HubConf;
import tricatch.oe.fwdproxy.ForwardProxyServer;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.service.ProxyConfService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class SettingsController {

    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static final String KEY_OID_DOMAIN_LIST = "oid.domainList";

    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;
    private final ProxyConfService proxyConfService;

    public SettingsController(SqlSessionFactory sqlSessionFactory, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.objectMapper = objectMapper;
        this.proxyConfService = new ProxyConfService(sqlSessionFactory);
    }

    // ── CA file paths ──────────────────────────────────────────────────────────

    public static Path caDir() {
        return AppHome.oeHubDir().resolve("root-ca");
    }

    public static Path caCertPath() { return caDir().resolve("ca.cer"); }
    public static Path caKeyPath()  { return caDir().resolve("ca.pfx"); }

    // ── settings ──────────────────────────────────────────────────────────────

    public void showSettings(Context ctx) {
        renderSettings(ctx, "", "", "generate");
    }

    // ── oeOID domain list ─────────────────────────────────────────────────────

    public void apiSaveOidDomainDefault(Context ctx) throws IOException {
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var domainList = ((String) body.getOrDefault("domainList", "")).trim();
        saveOidDomainDefault(domainList);
        ctx.json(Map.of("domainList", domainList));
    }

    public String getOidDomainDefault() {
        var v = proxyConfService.get(KEY_OID_DOMAIN_LIST, null);
        return v != null ? v : "";
    }

    public void saveOidDomainDefault(String domainList) {
        proxyConfService.set(KEY_OID_DOMAIN_LIST, null, domainList);
    }

    /** Personal domain list for a user, falling back to the admin default when unset. */
    public String getOidDomainListForUser(Long userNo) {
        var personal = proxyConfService.get(KEY_OID_DOMAIN_LIST, userNo);
        return personal != null && !personal.isBlank() ? personal : getOidDomainDefault();
    }

    public void saveOidDomainListForUser(Long userNo, String domainList) {
        proxyConfService.set(KEY_OID_DOMAIN_LIST, userNo, domainList);
    }

    // ── oeProxy identifier (OID / IP) ─────────────────────────────────────────

    public void apiSaveIdentifier(Context ctx) throws IOException {
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var ipEnabled = Boolean.TRUE.equals(body.get("ipEnabled"));
        ReverseProxyServer.setIpIdentifierEnabled(ipEnabled);
        ctx.json(Map.of("ipEnabled", ipEnabled));
    }

    // ── forward proxy relay whitelist ─────────────────────────────────────────

    public void apiSaveFwdProxyWhitelist(Context ctx) throws IOException {
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var whitelist = ((String) body.getOrDefault("whitelist", "")).trim();
        ForwardProxyServer.setWhitelist(whitelist);
        ctx.json(Map.of("whitelist", whitelist));
    }

    // ── CA certificate ────────────────────────────────────────────────────────

    public boolean isCaConfigured() {
        return Files.exists(caCertPath());
    }

    public void generateCa(Context ctx) {
        var caName = ctx.formParam("caName");
        if (caName == null || caName.isBlank()) {
            renderSettings(ctx, "settings.ca.error.name.required", "", "generate");
            return;
        }
        try {
            writeCaFiles(caName);
            logger.info("CA certificate generated: {}", caName);
            startProxyServer();
            renderSettings(ctx, "", "settings.ca.success.generated", "generate");
        } catch (Exception e) {
            logger.error("Failed to generate CA certificate", e);
            renderSettings(ctx, "settings.ca.error.generate.failed", "", "generate");
        }
    }

    public void importCa(Context ctx) {
        var certFile = ctx.uploadedFile("certFile");
        var keyFile  = ctx.uploadedFile("keyFile");

        if (certFile == null) {
            renderSettings(ctx, "settings.ca.error.cert.required", "", "import");
            return;
        }
        if (keyFile == null) {
            renderSettings(ctx, "settings.ca.error.key.required", "", "import");
            return;
        }
        try {
            byte[] certBytes = certFile.content().readAllBytes();
            byte[] keyBytes  = keyFile.content().readAllBytes();
            Files.createDirectories(caDir());
            Files.write(caCertPath(), certBytes);
            Files.write(caKeyPath(), keyBytes);
            logger.info("CA certificate imported.");
            startProxyServer();
            renderSettings(ctx, "", "settings.ca.success.imported", "generate");
        } catch (Exception e) {
            logger.error("Failed to import CA certificate", e);
            renderSettings(ctx, "settings.ca.error.import.failed", "", "import");
        }
    }

    /** Used by SetupCaController to generate CA during initial setup. */
    public void generateCaToDir(String caName) throws Exception {
        writeCaFiles(caName);
    }

    /** Used by SetupCaController to import CA during initial setup. */
    public void importCaToDir(byte[] certBytes, byte[] keyBytes) throws IOException {
        Files.createDirectories(caDir());
        Files.write(caCertPath(), certBytes);
        Files.write(caKeyPath(), keyBytes);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void startProxyServer() {
        try {
            ReverseProxyServer.startSslPassServer();
            logger.info("SSL proxy server started.");
        } catch (Exception e) {
            logger.warn("SSL proxy server could not be started: {}", e.getMessage());
        }
    }

    private void writeCaFiles(String caName) throws Exception {
        Files.createDirectories(caDir());
        CertificateKeyPair rootCert = new RootCertificateCreator().generateRootCertificate(caName);
        KeyTool keyTool = new KeyTool();
        keyTool.writeCertificate(rootCert.getCertificate(), caDir().toString(), "ca.cer");
        keyTool.writePrivateKey(rootCert.getPrivateKey(), caDir().toString(), "ca.pfx");
    }

    private void renderSettings(Context ctx, String caError, String caSuccess, String caActiveTab) {
        var model = new HashMap<String, Object>();
        model.put("user", AuthController.currentUser(ctx));
        model.put("oidDomainDefault", getOidDomainDefault());
        model.put("ipIdentifierEnabled", ReverseProxyServer.isIpIdentifierEnabled());
        model.put("fwdproxyPort", ForwardProxyServer.getPort());
        model.put("fwdproxyWhitelist", ForwardProxyServer.getWhitelist());
        model.put("ca", loadCaInfo());
        model.put("caError", caError);
        model.put("caSuccess", caSuccess);
        model.put("caActiveTab", caActiveTab);
        model.put("defaultCaName", "oeProxy Self Root CA - " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd-HHmm")));
        ctx.render("templates/oehub/settings.pebble", model);
    }

    public Map<String, Object> loadCaInfo() {
        var info = new HashMap<String, Object>();
        if (!Files.exists(caCertPath())) {
            info.put("configured", false);
            return info;
        }
        info.put("configured", true);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert;
            try (var is = Files.newInputStream(caCertPath())) {
                cert = (X509Certificate) cf.generateCertificate(is);
            }

            String subject = cert.getSubjectX500Principal().getName();
            String name = subject;
            for (String part : subject.split(",")) {
                if (part.trim().startsWith("CN=")) { name = part.trim().substring(3); break; }
            }
            info.put("name", name);
            info.put("subject", subject);
            info.put("notBefore", cert.getNotBefore().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime().format(DT_FMT));
            info.put("notAfter", cert.getNotAfter().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime().format(DT_FMT));
            info.put("serial", cert.getSerialNumber().toString(16).toUpperCase());
        } catch (Exception e) {
            logger.warn("Failed to parse CA certificate for display", e);
            info.put("parseError", true);
        }
        return info;
    }

    private String getConf(String confKey) {
        try (var session = sqlSessionFactory.openSession()) {
            var conf = session.getMapper(HubConfMapper.class).findByConfKey(confKey);
            return conf != null ? conf.getConfVal() : "";
        }
    }

    private void saveConf(String kid, String val) {
        try (var session = sqlSessionFactory.openSession(true)) {
            var conf = new HubConf();
            conf.setConfKey(kid);
            conf.setConfVal(val);
            conf.setUpdatedAt(LocalDateTime.now());
            session.getMapper(HubConfMapper.class).upsert(conf);
        }
    }
}
