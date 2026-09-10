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
import tricatch.oe.fwdproxy.ForwardProxyServer;

import tricatch.oe.hosts.model.HostsUa;
import tricatch.oe.hosts.model.HostsUrl;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HostsController {

    private final SqlSessionFactory sqlSessionFactory;
    private final HostsProfService hostsProfService;
    private final HostConfService hostConfService;
    private final SettingsController settingsController;
    private final ObjectMapper objectMapper;

    // apiShare (below) is public/unauthenticated and resolves the caller-supplied Host header via
    // DNS. Bounding that lookup by a timeout on its own virtual thread keeps a slow/unresponsive
    // domain in the Host header from tying up a request-handling thread indefinitely (a trivial
    // DoS otherwise, since this endpoint requires no login).
    private static final ExecutorService DNS_RESOLVER = Executors.newVirtualThreadPerTaskExecutor();
    private static final long DNS_RESOLVE_TIMEOUT_MS = 500;

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

    public void apiCreate(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        String encryptedContent = null;
        String wrappedContentKey = null;
        if (ctx.body() != null && !ctx.body().isBlank()) {
            @SuppressWarnings("unchecked")
            var body = objectMapper.readValue(ctx.body(), Map.class);
            encryptedContent = (String) body.get("encryptedContent");
            wrappedContentKey = (String) body.get("wrappedContentKey");
        }
        ctx.json(hostsProfService.create(hubUser.getUserNo(), encryptedContent, wrappedContentKey));
        ctx.status(201);
    }

    public void apiUpdateContent(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var hostId = ctx.pathParam("hostsId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var content = (String) body.get("content");
        // Present only right after create(), to encrypt the server-generated example content the
        // client couldn't have produced ahead of time (e2eEncryption design doc §1) - an ordinary
        // content edit never sends this, since the key never changes on its own.
        var wrappedContentKey = (String) body.get("wrappedContentKey");
        // Present when the row has a live public link (e2eEncryption design doc §6 "living link"
        // redesign) - the caller's browser already re-encrypted the same content with the link's
        // own DEK, so the link stays up to date with this save.
        var linkContent = (String) body.get("linkContent");
        var updated = wrappedContentKey != null
                ? hostsProfService.updateContentAndKey(hostId, hubUser.getUserNo(), content, wrappedContentKey)
                : hostsProfService.updateContent(hostId, hubUser.getUserNo(), content, linkContent);
        if (updated == null) { ctx.status(404); return; }
        if (updated.isSelected()) {
            ForwardProxyServer.refreshUserHosts(hubUser);
        }
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
        ForwardProxyServer.refreshUserHosts(hubUser);
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

    public void apiCopy(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var sourceHostId = ctx.pathParam("hostsId");
        String encryptedContent = null;
        String wrappedContentKey = null;
        if (ctx.body() != null && !ctx.body().isBlank()) {
            @SuppressWarnings("unchecked")
            var body = objectMapper.readValue(ctx.body(), Map.class);
            encryptedContent = (String) body.get("encryptedContent");
            wrappedContentKey = (String) body.get("wrappedContentKey");
        }
        var copy = hostsProfService.copyProfile(hubUser.getUserNo(), sourceHostId, encryptedContent, wrappedContentKey);
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

    // Authenticated JSON counterpart to /share/.../text (below): the app itself (not an
    // anonymous visitor) needs another workspace member's public/collabo row - to view it
    // read-only from search results, or for the owner's own "open as text" action - and a plain
    // synchronous text/plain response can't carry encrypted (group mode) content, since the
    // server never holds the key to decrypt it (design doc §1/§9). Callers decrypt client-side
    // with OE_CONTENT_CRYPTO.decrypt(), which also handles the plaintext/standalone case as a
    // no-op passthrough. /api/* already requires a session (OeHubApplication's before-filter).
    public void apiGetForView(Context ctx) {
        var hostsId = ctx.pathParam("hostsId");
        var hosts = hostsProfService.get(hostsId);
        if (hosts == null) { ctx.status(404); return; }
        if ("private".equals(hosts.getVisibility())) { ctx.status(403); return; }
        if (tricatch.oe.hub.config.AppHome.isGroupMode()) {
            var viewer = AuthController.currentUser(ctx);
            var ownerWsNo = hostsProfService.getOwnerWsNo(hostsId);
            if (ownerWsNo == null || !ownerWsNo.equals(viewer.getWsNo())) { ctx.status(403); return; }
        }
        var owner = hostsProfService.getOwnerUserId(hostsId);
        var result = new HashMap<String, Object>();
        result.put("hostsId", hosts.getHostsId());
        result.put("hostsProfile", hosts.getHostsProfile());
        result.put("hostsContent", hosts.getHostsContent());
        result.put("wrappedContentKey", hosts.getWrappedContentKey());
        result.put("visibility", hosts.getVisibility());
        result.put("userId", owner != null ? owner : "");
        result.put("updatedAt", hosts.getUpdatedAt());
        ctx.json(result);
    }

    public void apiShare(Context ctx) throws Exception {
        var hostsId = ctx.pathParam("hostsId");
        var hosts = hostsProfService.get(hostsId);
        if (hosts == null) { ctx.status(404); return; }
        if ("private".equals(hosts.getVisibility())) { ctx.status(403); return; }
        var groupMode = tricatch.oe.hub.config.AppHome.isGroupMode();
        // Encrypted content (oe.mode=group) can't be decrypted by an anonymous visitor - the
        // server never holds the workspace key. So under group mode this route stops being a
        // fully public link and instead requires the viewer to already be logged in to the SAME
        // workspace (e2eEncryption design doc §9's reinterpretation of cloudGroupService doc
        // §2.4) - their browser then decrypts with its own cached workspace key, same as
        // hosts.pebble. Standalone content is never encrypted (design doc §1), so it keeps the
        // original fully-public, no-login behavior below.
        if (groupMode) {
            var viewer = AuthController.currentUser(ctx);
            if (viewer == null) { ctx.redirect("/login?redirect=" + ctx.path()); return; }
            var ownerWsNo = hostsProfService.getOwnerWsNo(hostsId);
            if (ownerWsNo == null || !ownerWsNo.equals(viewer.getWsNo())) { ctx.status(403); return; }
        }
        var owner = hostsProfService.getOwnerUserId(hostsId);
        var model = new HashMap<String, Object>();
        model.put("hosts", hosts);
        model.put("owner", owner != null ? owner : "");
        model.put("contentJson", tricatch.oe.hub.util.HtmlJsonUtil.escapeForScript(objectMapper.writeValueAsString(hosts.getHostsContent())));
        model.put("wrappedContentKeyJson", tricatch.oe.hub.util.HtmlJsonUtil.escapeForScript(objectMapper.writeValueAsString(hosts.getWrappedContentKey())));
        model.put("proxyIp", extractProxyIp(ctx));
        model.put("groupMode", groupMode);
        model.put("linkMode", false);
        ctx.render("templates/oehub/hosts-share.pebble", model);
    }

    // Issues (both fields present) or revokes (empty body) the fully-public, no-login link
    // (e2eEncryption design doc §6 "living link" redesign) - linkContent is already ciphertext from
    // the caller's browser, and wrappedLinkKey is that same DEK wrapped with the caller's
    // workspace key so any member's browser can refresh linkContent on a later save; the raw key
    // itself never reaches this server, only the URL fragment. Authenticated (/api/* filter) but
    // otherwise open to any member of the row's own workspace (mirrors §8's "public content is
    // jointly owned" model - HostsProfMapper.updateLinkContent enforces the scope).
    @SuppressWarnings("unchecked")
    public void apiSetLink(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var hostsId = ctx.pathParam("hostsId");
        String linkContent = null;
        String wrappedLinkKey = null;
        if (ctx.body() != null && !ctx.body().isBlank()) {
            var body = objectMapper.readValue(ctx.body(), Map.class);
            linkContent = (String) body.get("linkContent");
            wrappedLinkKey = (String) body.get("wrappedLinkKey");
        }
        if ((linkContent == null) != (wrappedLinkKey == null)) { ctx.status(400); return; }
        var updated = hostsProfService.setPublicLink(hostsId, hubUser.getUserNo(), linkContent, wrappedLinkKey);
        if (updated == null) { ctx.status(404); return; }
        ctx.status(204);
    }

    // Public, unauthenticated viewer for an issued link - a completely separate trust boundary
    // from apiShare above (which, under group mode, now requires login to the same workspace):
    // this route never checks who's asking, only that a link was actually issued and not revoked.
    // Reuses hosts-share.pebble in "linkMode": the page gets the link_content ciphertext instead
    // of hosts_content, and the client pulls the raw (never-wrapped) decryption key out of its
    // own URL fragment - which this handler, like the browser's own request itself, never sees.
    public void apiPublicLink(Context ctx) throws Exception {
        var hostsId = ctx.pathParam("hostsId");
        var hosts = hostsProfService.getForPublicLink(hostsId);
        if (hosts == null) { ctx.status(404); return; }
        var owner = hostsProfService.getOwnerUserId(hostsId);
        var model = new HashMap<String, Object>();
        model.put("hosts", hosts);
        model.put("owner", owner != null ? owner : "");
        model.put("contentJson", tricatch.oe.hub.util.HtmlJsonUtil.escapeForScript(objectMapper.writeValueAsString(hosts.getLinkContent())));
        model.put("wrappedContentKeyJson", "null");
        model.put("proxyIp", extractProxyIp(ctx));
        model.put("groupMode", tricatch.oe.hub.config.AppHome.isGroupMode());
        model.put("linkMode", true);
        ctx.render("templates/oehub/hosts-share.pebble", model);
    }

    public void apiExport(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var profiles = hostsProfService.list(hubUser.getUserNo());
        // The public link (linkContent/wrappedLinkKey) is per-workspace derived data - wrappedLinkKey
        // can never be unwrapped outside the workspace it was wrapped in, so it's meaningless (and
        // potentially misleading) in an export (e2eEncryption design doc §6).
        profiles.forEach(p -> { p.setLinkContent(null); p.setWrappedLinkKey(null); });
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
            // Round-tripping the same account's own export back in: apiExport (below) serializes
            // the row's wrapped_content_key verbatim, and it stays valid here unchanged - re-import
            // never touches the DEK or which key wraps it (only visibility can be downgraded just
            // below, and collabo/public share the identical workspace-key wrap anyway, e2eEncryption
            // design doc §6). Without this, an encrypted row's hostsContent (ciphertext) would land
            // with no key at all and get treated/displayed as if it were plaintext.
            h.setWrappedContentKey((String) m.get("wrappedContentKey"));
            h.setSelected(Boolean.TRUE.equals(m.get("selected")));
            h.setSortOrder(m.get("sortOrder") != null ? ((Number) m.get("sortOrder")).intValue() : 0);
            // "collabo" only makes sense with a live parent reference, which an import can't
            // recreate, so treat anything but an explicit "private" as public (import creates
            // standalone entries, never collabo refs).
            h.setVisibility("private".equals(m.get("visibility")) ? "private" : "public");
            return h;
        }).toList();
        var updated = hostsProfService.importProfiles(hubUser.getUserNo(), entries, merge);
        var settingsRaw = (Map<String, String>) body.get("settings");
        if (settingsRaw != null) {
            settingsRaw.forEach((k, v) -> hostConfService.set(hubUser.getUserNo(), k, v));
        }
        ctx.json(updated);
    }

    // Plain-text {locale -> messages} lookup for the handful of controller-rendered (non-Pebble)
    // responses below - mirrors tricatch.oe.proxy.util.HtmlUtil's identical need in that package.
    private static final Map<String, Map<String, String>> SHARE_MESSAGES = Map.of(
        "en", new tricatch.oe.hub.i18n.Messages("en").asMap(),
        "ko", new tricatch.oe.hub.i18n.Messages("ko").asMap()
    );

    public void apiShareText(Context ctx) {
        var hostId = ctx.pathParam("hostsId");
        var hosts = hostsProfService.get(hostId);
        if (hosts == null) { ctx.status(404); return; }
        if ("private".equals(hosts.getVisibility())) { ctx.status(403); return; }
        if (hosts.getWrappedContentKey() != null) {
            // Encrypted (oe.mode=group): this endpoint returns a single synchronous plaintext
            // body, but the server never holds the workspace key needed to decrypt (server-blind
            // by design, e2eEncryption design doc §1) - only the interactive /share page can, via
            // the logged-in viewer's own browser. See apiShare's group-mode gate above.
            var locale = tricatch.oe.hub.i18n.LocaleContext.get();
            var msg = SHARE_MESSAGES.getOrDefault(locale, SHARE_MESSAGES.get("en")).get("share.text.encrypted");
            ctx.status(409).contentType("text/plain; charset=utf-8").result(msg);
            return;
        }
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
        var wrappedContentKey = (String) body.get("wrappedContentKey");
        if (visibility == null || (!visibility.equals("public") && !visibility.equals("private") && !visibility.equals("collabo"))) {
            ctx.status(400); return;
        }
        var updated = hostsProfService.updateVisibility(hostId, hubUser.getUserNo(), visibility, wrappedContentKey);
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
            if (body.get("uaName") instanceof String s) ua.setUaName(s.trim());
            if (body.get("uaValue") instanceof String s) ua.setUaValue(s.trim());
            ua.setUpdatedBy(user.getUserNo());
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
        ua.setCreatedBy(user.getUserNo());
        ua.setUpdatedBy(user.getUserNo());
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
                ua.setUpdatedBy(user.getUserNo());
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
        url.setCreatedBy(user.getUserNo());
        url.setUpdatedBy(user.getUserNo());
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
            if (body.get("urlName") instanceof String s) url.setUrlName(s.trim());
            if (body.get("urlValue") instanceof String s) url.setUrlValue(s.trim());
            url.setUpdatedBy(user.getUserNo());
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
                url.setUpdatedBy(user.getUserNo());
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
            var future = DNS_RESOLVER.submit(() -> InetAddress.getByName(hostname).getHostAddress());
            return future.get(DNS_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return ctx.req().getLocalAddr();
        }
    }
}
