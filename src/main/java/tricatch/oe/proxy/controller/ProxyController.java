package tricatch.oe.proxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.controller.AuthController;
import tricatch.oe.hub.controller.SettingsController;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.consumer.HttpEventMonitorConsumer;
import tricatch.oe.proxy.model.ProxyVhost;
import tricatch.oe.proxy.service.ProxyConfService;
import tricatch.oe.proxy.service.ProxyVhostService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.naming.ldap.LdapName;

public class ProxyController {

    private static final Logger logger = LoggerFactory.getLogger(ProxyController.class);

    private final ProxyVhostService vhostService;
    private final ProxyConfService  confService;
    private final ObjectMapper objectMapper;

    public ProxyController(SqlSessionFactory sqlSessionFactory, ObjectMapper objectMapper) {
        this.vhostService = new ProxyVhostService(sqlSessionFactory);
        this.confService  = new ProxyConfService(sqlSessionFactory);
        this.objectMapper = objectMapper;
    }

    public void showProxy(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var clientIp = ctx.ip();
        var localSvrOverride = confService.get("local_svr", hubUser.getUserNo());
        var model = new HashMap<String, Object>();
        model.put("user", hubUser);
        model.put("clientIp", clientIp);
        model.put("localSvr", localSvrOverride != null && !localSvrOverride.isBlank() ? localSvrOverride : clientIp);
        model.put("oid", hubUser.getOid());
        ctx.render("templates/oehub/proxy.pebble", model);
    }

    public void showMonitor(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var model = new HashMap<String, Object>();
        model.put("user", hubUser);
        model.put("proxyActive", !vhostService.listSelected(hubUser.getUserNo()).isEmpty());
        ctx.render("templates/oehub/proxy-monitor.pebble", model);
    }

    public void monitorEvent(Context ctx) throws IOException {
        ctx.res().setContentType("text/event-stream");
        ctx.res().setCharacterEncoding("UTF-8");
        ctx.res().setHeader("Cache-Control", "no-cache");
        ctx.res().setHeader("Connection", "keep-alive");
        ctx.res().setHeader("X-Accel-Buffering", "no");

        String clientId = ctx.ip();
        String channelId = clientId + "/hub-" + System.nanoTime();

        OutputStream out = ctx.res().getOutputStream();
        ReentrantLock writeLock = new ReentrantLock();

        writeLock.lock();
        try {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            writeLock.unlock();
        }

        HttpEventMonitorConsumer consumer = new HttpEventMonitorConsumer(clientId, channelId, out, writeLock);
        HttpEventManager.getInstance().addEventConsumer(consumer);

        try {
            for (;;) {
                Thread.sleep(5000);
                if (writeLock.tryLock()) {
                    try {
                        out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } finally {
                        writeLock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.debug("Monitor SSE client disconnected: channelId={}", channelId);
        } finally {
            HttpEventManager.getInstance().removeEventConsumer(consumer);
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    public void apiDownloadCa(Context ctx) throws IOException {
        var certPath = SettingsController.caCertPath();
        if (!java.nio.file.Files.exists(certPath)) {
            ctx.status(404).result("CA certificate not configured.");
            return;
        }
        var bytes = java.nio.file.Files.readAllBytes(certPath);
        ctx.res().setContentType("application/x-x509-ca-cert");
        ctx.res().setHeader("Content-Disposition", "attachment; filename=\"" + certFilename(bytes) + "\"");
        ctx.res().setContentLength(bytes.length);
        ctx.res().getOutputStream().write(bytes);
    }

    private static String certFilename(byte[] certBytes) {
        try {
            var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certBytes));
            var ldapName = new LdapName(cert.getSubjectX500Principal().getName());
            String cn = null, o = null;
            for (var rdn : ldapName.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType()) && cn == null) cn = rdn.getValue().toString();
                if ("O".equalsIgnoreCase(rdn.getType())  && o  == null) o  = rdn.getValue().toString();
            }
            var name = cn != null ? cn : o;
            if (name != null && !name.isBlank()) {
                name = name.trim().replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-{2,}", "-");
                return name + ".cer";
            }
        } catch (Exception ignored) {}
        return "oeHub-CA.cer";
    }

    public void apiList(Context ctx) {
        ctx.json(vhostService.list(AuthController.currentUser(ctx).getUserNo()));
    }

    public void apiCreate(Context ctx) {
        ctx.json(vhostService.create(AuthController.currentUser(ctx).getUserNo()));
        ctx.status(201);
    }

    public void apiUpdateContent(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var vhostId = ctx.pathParam("vhostId");
        var body    = objectMapper.readValue(ctx.body(), Map.class);
        var updated = vhostService.updateContent(vhostId, hubUser.getUserNo(), (String) body.get("content"));
        if (updated == null) { ctx.status(404); return; }
        if (updated.isSelected()) {
            applyMergedConfig(hubUser.getUserNo(), ctx.ip());
        }
        ctx.json(updated);
    }

    public void apiUpdateName(Context ctx) throws Exception {
        var hubUser    = AuthController.currentUser(ctx);
        var vhostId = ctx.pathParam("vhostId");
        var body    = objectMapper.readValue(ctx.body(), Map.class);
        var updated = vhostService.updateProfile(vhostId, hubUser.getUserNo(), (String) body.get("name"));
        if (updated == null) { ctx.status(404); return; }
        ctx.json(updated);
    }

    public void apiToggleSelected(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var updated = vhostService.toggleSelected(ctx.pathParam("vhostId"), hubUser.getUserNo());
        if (updated == null) { ctx.status(404); return; }
        applyMergedConfig(hubUser.getUserNo(), ctx.ip());
        ctx.json(updated);
    }

    public void apiUpdateVisibility(Context ctx) throws Exception {
        var hubUser       = AuthController.currentUser(ctx);
        var vhostId    = ctx.pathParam("vhostId");
        var body       = objectMapper.readValue(ctx.body(), Map.class);
        var visibility = (String) body.get("visibility");
        if (visibility == null || (!visibility.equals("public") && !visibility.equals("private") && !visibility.equals("collabo"))) {
            ctx.status(400); return;
        }
        var updated = vhostService.updateVisibility(vhostId, hubUser.getUserNo(), visibility);
        if (updated == null) { ctx.status(404); return; }
        ctx.json(updated);
    }

    public void apiRegister(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var vhostId = ctx.pathParam("vhostId");
        var registered = vhostService.registerCollabo(hubUser.getUserNo(), vhostId);
        if (registered == null) { ctx.status(404); return; }
        ctx.json(registered);
    }

    public void apiReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        vhostService.reorder(AuthController.currentUser(ctx).getUserNo(), ids);
        ctx.status(204);
    }

    public void apiDelete(Context ctx) {
        vhostService.delete(ctx.pathParam("vhostId"), AuthController.currentUser(ctx).getUserNo());
        ctx.status(204);
    }

    public void apiDeleteAll(Context ctx) {
        vhostService.deleteAll(AuthController.currentUser(ctx).getUserNo());
        ctx.status(204);
    }

    public void apiCopy(Context ctx) {
        var copy = vhostService.copyVhost(AuthController.currentUser(ctx).getUserNo(), ctx.pathParam("vhostId"));
        if (copy == null) { ctx.status(404); return; }
        ctx.json(copy);
        ctx.status(201);
    }

    public void apiSearch(Context ctx) {
        var hubUser    = AuthController.currentUser(ctx);
        var keyword = ctx.queryParam("q");
        if (keyword == null || keyword.isBlank()) { ctx.json(List.of()); return; }
        ctx.json(vhostService.searchOthers(hubUser.getUserNo(), keyword));
    }

    public void apiExport(Context ctx) throws Exception {
        var hubUser   = AuthController.currentUser(ctx);
        var vhosts = vhostService.list(hubUser.getUserNo());
        var export = Map.of("version", 1, "exportedAt", java.time.LocalDateTime.now().toString(), "vhosts", vhosts);
        var ts     = java.time.LocalDateTime.now().toString().replace(":", "-").substring(0, 19);
        ctx.contentType("application/json")
           .header("Content-Disposition", "attachment; filename=\"oevhost-" + ts + ".json\"")
           .result(objectMapper.writeValueAsString(export));
    }

    public void apiImport(Context ctx) throws Exception {
        var hubUser    = AuthController.currentUser(ctx);
        var merge   = "true".equals(ctx.queryParam("merge"));
        var body    = objectMapper.readValue(ctx.body(), Map.class);
        var raw     = (List<Map<String, Object>>) body.get("vhosts");
        if (raw == null) { ctx.status(400).result("Missing 'vhosts' field"); return; }
        var entries = raw.stream().map(m -> {
            var v = new ProxyVhost();
            v.setVhostProfile((String) m.get("vhostProfile"));
            v.setVhostContent((String) m.get("vhostContent"));
            v.setSelected(Boolean.TRUE.equals(m.get("selected")));
            v.setSortOrder(m.get("sortOrder") != null ? ((Number) m.get("sortOrder")).intValue() : 0);
            return v;
        }).toList();
        ctx.json(vhostService.importVhosts(hubUser.getUserNo(), entries, merge));
    }

    public void apiShare(Context ctx) throws Exception {
        var vhostId = ctx.pathParam("vhostId");
        var vhost   = vhostService.get(vhostId);
        if (vhost == null) { ctx.status(404); return; }
        if ("private".equals(vhost.getVisibility())) { ctx.status(403); return; }
        var model = new HashMap<String, Object>();
        model.put("vhost",       vhost);
        model.put("owner",       vhostService.getOwnerUsername(vhostId));
        model.put("contentJson", objectMapper.writeValueAsString(vhost.getVhostContent()));
        ctx.render("templates/oehub/proxy-share.pebble", model);
    }

    public void apiShareText(Context ctx) {
        var vhostId = ctx.pathParam("vhostId");
        var vhost   = vhostService.get(vhostId);
        if (vhost == null) { ctx.status(404); return; }
        if ("private".equals(vhost.getVisibility())) { ctx.status(403); return; }
        var owner  = vhostService.getOwnerUsername(vhostId);
        var modDt  = vhost.getUpdatedAt() != null
            ? vhost.getUpdatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "";
        ctx.contentType("text/plain; charset=utf-8")
           .result("# " + vhost.getVhostProfile() + " / " + owner + " / " + modDt + "\n\n" + vhost.getVhostContent());
    }

    public void apiConfGet(Context ctx) {
        var hubUser  = AuthController.currentUser(ctx);
        var value = confService.get(ctx.pathParam("name"), hubUser.getUserNo());
        ctx.json(Map.of("value", value != null ? value : ""));
    }

    public void apiConfSet(Context ctx) throws Exception {
        var hubUser  = AuthController.currentUser(ctx);
        var body  = objectMapper.readValue(ctx.body(), Map.class);
        var name  = ctx.pathParam("name");
        var value = (String) body.get("value");
        confService.set(name, hubUser.getUserNo(), value);
        if ("vhost".equals(name)) {
            if (value != null && !value.isBlank()) {
                try {
                    var localSvr = resolveLocalSvr(confService, hubUser.getUserNo(), ctx.ip());
                    ReverseProxyServer.setVirtualHosts(ctx.ip(), hubUser.getUserNo(), substituteLocalSvr(value, localSvr));
                } catch (Exception e) {
                    logger.warn("Failed to apply virtual hosts for user {}: {}", hubUser.getUserNo(), e.getMessage());
                }
            } else {
                ReverseProxyServer.clearVirtualHosts(ctx.ip(), hubUser.getUserNo());
            }
        } else if ("local_svr".equals(name)) {
            // Re-push the currently selected vhosts so the new LOCAL_SVR override takes effect immediately.
            applyMergedConfig(hubUser.getUserNo(), ctx.ip());
        }
        ctx.status(204);
    }

    private void applyMergedConfig(Long userNo, String routeIp) {
        applyMergedConfig(vhostService, confService, userNo, routeIp);
    }

    // Recompute the merged vhost config from the user's currently selected virtual hosts
    // and push it live into the running proxy — usable from outside this controller
    // (e.g. on login) without needing a ProxyController instance.
    public static void applyMergedConfig(SqlSessionFactory sqlSessionFactory, Long userNo, String routeIp) {
        applyMergedConfig(new ProxyVhostService(sqlSessionFactory), new ProxyConfService(sqlSessionFactory), userNo, routeIp);
    }

    // routeIp identifies the connecting client for the proxy's IP-to-owner lookup and must
    // stay the real observed address; the ${LOCAL_SVR} substitution below is allowed to diverge
    // from it via a user-set override (e.g. when routeIp is contaminated by an intermediate hop).
    private static void applyMergedConfig(ProxyVhostService vhostService, ProxyConfService confService, Long userNo, String routeIp) {
        try {
            var selected = vhostService.listSelected(userNo);
            var merged   = ReverseProxyServer.mergeVhostYaml(selected);
            confService.set("vhost", userNo, merged != null ? merged : "");
            if (merged != null && !merged.isBlank()) {
                var localSvr = resolveLocalSvr(confService, userNo, routeIp);
                ReverseProxyServer.setVirtualHosts(routeIp, userNo, substituteLocalSvr(merged, localSvr));
            }
        } catch (Exception e) {
            logger.warn("Failed to apply merged config for user {}: {}", userNo, e.getMessage());
        }
    }

    private static String resolveLocalSvr(ProxyConfService confService, Long userNo, String fallback) {
        var override = confService.get("local_svr", userNo);
        return override != null && !override.isBlank() ? override : fallback;
    }

    private static String substituteLocalSvr(String content, String localSvr) {
        if (localSvr == null || localSvr.isBlank()) return content;
        return content.replace("${LOCAL_SVR}", localSvr);
    }
}
