package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hosts.service.HostConfService;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.proxy.model.ProxyVhost;
import tricatch.oe.proxy.service.ProxyVhostService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class UserController {

    private final SqlSessionFactory sqlSessionFactory;
    private final HostsProfService hostsProfService;
    private final HostConfService hostConfService;
    private final ProxyVhostService proxyVhostService;
    private final ObjectMapper objectMapper;

    public UserController(SqlSessionFactory sqlSessionFactory, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.hostsProfService = new HostsProfService(sqlSessionFactory);
        this.hostConfService = new HostConfService(sqlSessionFactory);
        this.proxyVhostService = new ProxyVhostService(sqlSessionFactory);
        this.objectMapper = objectMapper;
    }

    public void apiBackup(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var userNo = hubUser.getUserNo();

        var hostsProfiles = hostsProfService.list(userNo);
        var openUrl  = hostConfService.get(userNo, "open_url");
        var incognito = hostConfService.get(userNo, "incognito");
        var proxyVhosts = proxyVhostService.list(userNo);

        var backup = Map.of(
            "version", 1,
            "exportedAt", java.time.LocalDateTime.now().toString(),
            "hosts", Map.of(
                "profiles", hostsProfiles,
                "settings", Map.of(
                    "open_url",  openUrl   != null ? openUrl   : "",
                    "incognito", incognito != null ? incognito : "false"
                )
            ),
            "proxy", Map.of(
                "vhosts", proxyVhosts
            )
        );

        var ts = java.time.LocalDateTime.now().toString().replace(":", "-").substring(0, 19);
        ctx.contentType("application/json")
           .header("Content-Disposition", "attachment; filename=\"oehub-backup-" + ts + ".json\"")
           .result(objectMapper.writeValueAsString(backup));
    }

    @SuppressWarnings("unchecked")
    public void apiRestore(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var userNo = hubUser.getUserNo();
        var merge = "true".equals(ctx.queryParam("merge"));

        var body = objectMapper.readValue(ctx.body(), Map.class);

        var hostsSection = (Map<String, Object>) body.get("hosts");
        if (hostsSection != null) {
            var hostsRaw = (List<Map<String, Object>>) hostsSection.get("profiles");
            if (hostsRaw != null) {
                var entries = hostsRaw.stream().map(m -> {
                    var h = new HostsProf();
                    h.setHostsProfile((String) m.get("hostsProfile"));
                    h.setHostsContent((String) m.get("hostsContent"));
                    h.setSelected(Boolean.TRUE.equals(m.get("selected")));
                    h.setSortOrder(m.get("sortOrder") != null ? ((Number) m.get("sortOrder")).intValue() : 0);
                    return h;
                }).toList();
                hostsProfService.importProfiles(userNo, entries, merge);
            }
            var settingsRaw = (Map<String, String>) hostsSection.get("settings");
            if (settingsRaw != null) {
                settingsRaw.forEach((k, v) -> hostConfService.set(userNo, k, v));
            }
        }

        var proxySection = (Map<String, Object>) body.get("proxy");
        if (proxySection != null) {
            var vhostsRaw = (List<Map<String, Object>>) proxySection.get("vhosts");
            if (vhostsRaw != null) {
                var entries = vhostsRaw.stream().map(m -> {
                    var v = new ProxyVhost();
                    v.setVhostProfile((String) m.get("vhostProfile"));
                    v.setVhostContent((String) m.get("vhostContent"));
                    v.setSelected(Boolean.TRUE.equals(m.get("selected")));
                    v.setSortOrder(m.get("sortOrder") != null ? ((Number) m.get("sortOrder")).intValue() : 0);
                    return v;
                }).toList();
                proxyVhostService.importVhosts(userNo, entries, merge);
            }
        }

        ctx.status(200).result("OK");
    }

    @SuppressWarnings("unchecked")
    public void apiChangePassword(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var currentPassword = (String) body.get("currentPassword");
        var newPassword     = (String) body.get("newPassword");
        var confirmPassword = (String) body.get("confirmPassword");

        if (newPassword == null || newPassword.isBlank()) {
            ctx.status(400).json(Map.of("error", "password_required"));
            return;
        }
        if (newPassword.length() < 4) {
            ctx.status(400).json(Map.of("error", "password_too_short"));
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            ctx.status(400).json(Map.of("error", "password_mismatch"));
            return;
        }

        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(hubUser.getUserNo());
            if (currentPassword == null || !PasswordUtil.matches(currentPassword, target.getPassword())) {
                ctx.status(400).json(Map.of("error", "current_password_invalid"));
                return;
            }
            target.setPassword(PasswordUtil.hash(newPassword));
            target.setUpdatedAt(LocalDateTime.now());
            mapper.updatePassword(target);
            session.commit();
        }
        ctx.status(204);
    }
}
