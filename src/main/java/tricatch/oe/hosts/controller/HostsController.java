package tricatch.oe.hosts.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.hosts.service.HostConfService;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.controller.AuthController;
import tricatch.oe.hub.controller.SettingsController;

import tricatch.oe.hosts.model.HostsUa;
import tricatch.oe.hosts.model.HostsUrl;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HostsController {

    private final SqlSessionFactory sqlSessionFactory;
    private final HostsProfService hostsProfService;
    private final HostConfService hostConfService;
    private final SettingsController settingsController;
    private final ObjectMapper objectMapper;

    public HostsController(SqlSessionFactory sqlSessionFactory, SettingsController settingsController, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.hostsProfService = new HostsProfService(sqlSessionFactory);
        this.hostConfService = new HostConfService(sqlSessionFactory);
        this.settingsController = settingsController;
        this.objectMapper = objectMapper;
    }

    public void showHosts(Context ctx) {
        var model = new HashMap<String, Object>();
        model.put("user", AuthController.currentUser(ctx));
        model.put("proxyIp", extractProxyIp(ctx));
        ctx.render("templates/oehub/hosts.pebble", model);
    }

    public void apiList(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        ctx.json(hostsProfService.list(hubUser.getUserNo()));
    }

    public void apiCreate(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        ctx.json(hostsProfService.create(hubUser.getUserNo()));
        ctx.status(201);
    }

    public void apiUpdateContent(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var hostId = ctx.pathParam("hostsId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var content = (String) body.get("content");
        var updated = hostsProfService.updateContent(hostId, hubUser.getUserNo(), content);
        if (updated == null) { ctx.status(404); return; }
        ctx.json(updated);
    }

    public void apiUpdateName(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var hostId = ctx.pathParam("hostsId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var name = (String) body.get("name");
        var updated = hostsProfService.updateProfile(hostId, hubUser.getUserNo(), name);
        if (updated == null) { ctx.status(404); return; }
        ctx.json(updated);
    }

    public void apiToggleSelected(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var hostId = ctx.pathParam("hostsId");
        var updated = hostsProfService.toggleSelected(hostId, hubUser.getUserNo());
        if (updated == null) { ctx.status(404); return; }
        ctx.json(updated);
    }

    public void apiReorder(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var ids = objectMapper.readValue(ctx.body(), List.class);
        hostsProfService.reorder(hubUser.getUserNo(), ids);
        ctx.status(204);
    }

    public void apiDelete(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var hostId = ctx.pathParam("hostsId");
        hostsProfService.delete(hostId, hubUser.getUserNo());
        ctx.status(204);
    }

    public void apiDeleteAll(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        hostsProfService.deleteAll(hubUser.getUserNo());
        ctx.status(204);
    }

    public void apiCopy(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var sourceHostId = ctx.pathParam("hostsId");
        var copy = hostsProfService.copyProfile(hubUser.getUserNo(), sourceHostId);
        if (copy == null) { ctx.status(404); return; }
        ctx.json(copy);
        ctx.status(201);
    }

    public void apiSearch(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var keyword = ctx.queryParam("q");
        if (keyword == null || keyword.isBlank()) { ctx.json(List.of()); return; }
        ctx.json(hostsProfService.searchOthers(hubUser.getUserNo(), keyword));
    }

    public void apiShare(Context ctx) throws Exception {
        var hostsId = ctx.pathParam("hostsId");
        var hosts = hostsProfService.get(hostsId);
        if (hosts == null) { ctx.status(404); return; }
        if ("private".equals(hosts.getVisibility())) { ctx.status(403); return; }
        var owner = hostsProfService.getOwnerUserId(hostsId);
        var model = new HashMap<String, Object>();
        model.put("hosts", hosts);
        model.put("owner", owner != null ? owner : "");
        model.put("contentJson", objectMapper.writeValueAsString(hosts.getHostsContent()));
        model.put("proxyIp", extractProxyIp(ctx));
        ctx.render("templates/oehub/hosts-share.pebble", model);
    }

    public void apiExport(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var profiles = hostsProfService.list(hubUser.getUserNo());
        var loadingUrl = hostConfService.get(hubUser.getUserNo(), "open_url");
        var incognito = hostConfService.get(hubUser.getUserNo(), "incognito");
        var export = Map.of(
            "version", 1,
            "exportedAt", java.time.LocalDateTime.now().toString(),
            "hosts", profiles,
            "settings", Map.of(
                "open_url",  loadingUrl != null ? loadingUrl : "",
                "incognito", incognito  != null ? incognito  : "false"
            )
        );
        var json = objectMapper.writeValueAsString(export);
        var ts = java.time.LocalDateTime.now().toString().replace(":", "-").substring(0, 19);
        ctx.contentType("application/json")
           .header("Content-Disposition", "attachment; filename=\"oehosts-" + ts + ".json\"")
           .result(json);
    }

    public void apiImport(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var merge = "true".equals(ctx.queryParam("merge"));
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var hostsRaw = (List<Map<String, Object>>) body.get("hosts");
        if (hostsRaw == null) { ctx.status(400).result("Missing 'hosts' field"); return; }
        var entries = hostsRaw.stream().map(m -> {
            var h = new HostsProf();
            h.setHostsProfile((String) m.get("hostsProfile"));
            h.setHostsContent((String) m.get("hostsContent"));
            h.setSelected(Boolean.TRUE.equals(m.get("selected")));
            h.setSortOrder(m.get("sortOrder") != null ? ((Number) m.get("sortOrder")).intValue() : 0);
            return h;
        }).toList();
        var updated = hostsProfService.importProfiles(hubUser.getUserNo(), entries, merge);
        var settingsRaw = (Map<String, String>) body.get("settings");
        if (settingsRaw != null) {
            settingsRaw.forEach((k, v) -> hostConfService.set(hubUser.getUserNo(), k, v));
        }
        ctx.json(updated);
    }

    public void apiShareText(Context ctx) {
        var hostId = ctx.pathParam("hostsId");
        var hosts = hostsProfService.get(hostId);
        if (hosts == null) { ctx.status(404); return; }
        if ("private".equals(hosts.getVisibility())) { ctx.status(403); return; }
        var owner = hostsProfService.getOwnerUserId(hostId);
        var modDt = hosts.getUpdatedAt() != null ? hosts.getUpdatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "";
        var header = "# " + hosts.getHostsProfile() + " / " + owner + " / " + modDt + "\n\n";
        ctx.contentType("text/plain; charset=utf-8").result(header + hosts.getHostsContent());
    }

    public void apiUpdateVisibility(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var hostId = ctx.pathParam("hostsId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var visibility = (String) body.get("visibility");
        if (visibility == null || (!visibility.equals("public") && !visibility.equals("private") && !visibility.equals("collabo"))) {
            ctx.status(400); return;
        }
        var updated = hostsProfService.updateVisibility(hostId, hubUser.getUserNo(), visibility);
        if (updated == null) { ctx.status(404); return; }
        ctx.json(updated);
    }

    public void apiRegister(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var hostId = ctx.pathParam("hostsId");
        var registered = hostsProfService.registerCollabo(hubUser.getUserNo(), hostId);
        if (registered == null) { ctx.status(404); return; }
        ctx.json(registered);
    }

    public void showMySetting(Context ctx) {
        var user = AuthController.currentUser(ctx);
        var model = new HashMap<String, Object>();
        model.put("user", user);
        model.put("myOidDomainList", settingsController.getOidDomainListForUser(user.getUserNo()));
        ctx.render("templates/oehub/my-setting.pebble", model);
    }

    public void apiMyOidDomainSave(Context ctx) throws Exception {
        var user = AuthController.currentUser(ctx);
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var domainList = ((String) body.getOrDefault("domainList", "")).trim();
        settingsController.saveOidDomainListForUser(user.getUserNo(), domainList);
        ctx.json(Map.of("domainList", domainList));
    }

    public void apiMyUaList(Context ctx) {
        var user = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var presets = session.getMapper(HostsUaMapper.class).findAllForUser(user.getUserNo());
            ctx.json(presets.stream().filter(p -> p.getUserNo() != null).toList());
        }
    }

    public void apiMyUaUpdate(Context ctx) throws Exception {
        var user = AuthController.currentUser(ctx);
        var uaId = ctx.pathParam("uaId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var ua = mapper.findByIdAndUserNo(uaId, user.getUserNo());
            if (ua == null) { ctx.status(404); return; }
            if (body.containsKey("uaName")) ua.setUaName(((String) body.get("uaName")).trim());
            if (body.containsKey("uaValue")) ua.setUaValue(((String) body.get("uaValue")).trim());
            ua.setUpdatedAt(LocalDateTime.now());
            mapper.update(ua);
            ua.setMine(true);
            ctx.json(ua);
        }
    }

    public void apiUaPresets(Context ctx) {
        var user = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var presets = session.getMapper(HostsUaMapper.class).findAllForUser(user.getUserNo());
            presets.forEach(p -> p.setMine(p.getUserNo() != null));
            ctx.json(presets);
        }
    }

    public void apiMyUaCreate(Context ctx) throws Exception {
        var user = AuthController.currentUser(ctx);
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
        ua.setUserNo(user.getUserNo());
        ua.setCreateAt(now);
        ua.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            ua.setSortOrder(mapper.nextSortOrderForUser(user.getUserNo()));
            mapper.insert(ua);
        }
        ua.setMine(true);
        ctx.json(ua).status(201);
    }

    public void apiMyUaDelete(Context ctx) {
        var user = AuthController.currentUser(ctx);
        var uaId = ctx.pathParam("uaId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUaMapper.class).deleteByIdAndUserNo(uaId, user.getUserNo());
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiMyUaReorder(Context ctx) throws Exception {
        var user = AuthController.currentUser(ctx);
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var ua = mapper.findByIdAndUserNo((String) ids.get(i), user.getUserNo());
                if (ua == null) continue;
                ua.setSortOrder(i);
                ua.setUpdatedAt(LocalDateTime.now());
                mapper.update(ua);
            }
        }
        ctx.status(204);
    }

    public void apiMyUrlList(Context ctx) {
        var user = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var presets = session.getMapper(HostsUrlMapper.class).findAllForUser(user.getUserNo());
            ctx.json(presets.stream().filter(p -> p.getUserNo() != null).toList());
        }
    }

    public void apiUrlPresets(Context ctx) {
        var user = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var presets = session.getMapper(HostsUrlMapper.class).findAllForUser(user.getUserNo());
            presets.forEach(p -> p.setMine(p.getUserNo() != null));
            ctx.json(presets);
        }
    }

    public void apiMyUrlCreate(Context ctx) throws Exception {
        var user = AuthController.currentUser(ctx);
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
        url.setUserNo(user.getUserNo());
        url.setCreateAt(now);
        url.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            url.setSortOrder(mapper.nextSortOrderForUser(user.getUserNo()));
            mapper.insert(url);
        }
        url.setMine(true);
        ctx.json(url).status(201);
    }

    public void apiMyUrlUpdate(Context ctx) throws Exception {
        var user = AuthController.currentUser(ctx);
        var urlId = ctx.pathParam("urlId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var url = mapper.findByIdAndUserNo(urlId, user.getUserNo());
            if (url == null) { ctx.status(404); return; }
            if (body.containsKey("urlName")) url.setUrlName(((String) body.get("urlName")).trim());
            if (body.containsKey("urlValue")) url.setUrlValue(((String) body.get("urlValue")).trim());
            url.setUpdatedAt(LocalDateTime.now());
            mapper.update(url);
            url.setMine(true);
            ctx.json(url);
        }
    }

    public void apiMyUrlDelete(Context ctx) {
        var user = AuthController.currentUser(ctx);
        var urlId = ctx.pathParam("urlId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUrlMapper.class).deleteByIdAndUserNo(urlId, user.getUserNo());
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiMyUrlReorder(Context ctx) throws Exception {
        var user = AuthController.currentUser(ctx);
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var url = mapper.findByIdAndUserNo((String) ids.get(i), user.getUserNo());
                if (url == null) continue;
                url.setSortOrder(i);
                url.setUpdatedAt(LocalDateTime.now());
                mapper.update(url);
            }
        }
        ctx.status(204);
    }

    public void apiEnvGet(Context ctx) {

        var hubUser = AuthController.currentUser(ctx);
        var name = ctx.pathParam("name");
        var value = hostConfService.get(hubUser.getUserNo(), name);

        if ((value == null || value.isBlank()) && "open_url".equals(name)) {
            try (var session = sqlSessionFactory.openSession()) {
                var globalUrls = session.getMapper(HostsUrlMapper.class).findAll();
                value = globalUrls.isEmpty() ? "" : globalUrls.get(0).getUrlValue();
            }
        }

        ctx.json(Map.of("value", value != null ? value : ""));
    }

    public void apiEnvSet(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var name = ctx.pathParam("name");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var value = (String) body.get("value");
        hostConfService.set(hubUser.getUserNo(), name, value);
        ctx.status(204);
    }

    private static String extractProxyIp(Context ctx) {
        var host = ctx.header("Host");
        if (host == null || host.isBlank()) {
            return ctx.req().getLocalAddr();
        }
        String hostname;
        if (host.startsWith("[")) {
            var end = host.indexOf(']');
            hostname = end > 0 ? host.substring(1, end) : host;
        } else {
            var colon = host.indexOf(':');
            hostname = colon > 0 ? host.substring(0, colon) : host;
        }
        try {
            return InetAddress.getByName(hostname).getHostAddress();
        } catch (Exception e) {
            return ctx.req().getLocalAddr();
        }
    }
}
