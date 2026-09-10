package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hosts.service.HostConfService;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.WsKeyMapper;
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

    // Fetched by login.pebble right after a successful login (while the just-typed password is
    // still in JS memory) so the browser can unwrap the private key, then the workspace key, and
    // cache both for the session (e2eEncryption design doc §3). Scoped to the caller's own
    // row/ws via their session cookie - never takes a userId param, so it can't be used to probe
    // another user's key material.
    public void apiMyCryptoKeys(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var target = session.getMapper(HubUserMapper.class).findByUserNo(hubUser.getUserNo());
            if (target == null) { ctx.status(404); return; }
            var wsKey = session.getMapper(WsKeyMapper.class).findByWsNoAndUserNo(target.getWsNo(), target.getUserNo());
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("publicKey", target.getPublicKey());
            result.put("wrappedPrivateKey", target.getWrappedPrivateKey());
            result.put("wrappedPrivateKeyRecovery", target.getWrappedPrivateKeyRecovery());
            // null until this member has been through the approval-time key-wrap bundling
            // (e2eEncryption design doc §5) or founded the workspace themselves - not every
            // existing member has this yet, since that wiring isn't in place for the invite/
            // pending-approval path yet, only the founder path.
            result.put("wrappedWsKey", wsKey != null ? wsKey.getWrappedWsKey() : null);
            ctx.json(result);
        }
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
                    // Same round-trip reasoning as HostsController.apiImport: apiBackup serialized
                    // wrapped_content_key verbatim and it's still valid unchanged on restore into
                    // the same account (e2eEncryption design doc §9) - without this, an encrypted
                    // row's ciphertext would land with no key and be shown as if it were plaintext.
                    h.setWrappedContentKey((String) m.get("wrappedContentKey"));
                    h.setSelected(Boolean.TRUE.equals(m.get("selected")));
                    h.setSortOrder(m.get("sortOrder") != null ? ((Number) m.get("sortOrder")).intValue() : 0);
                    // Was missing entirely (every restored row silently became 'public' via
                    // importProfiles' null-visibility default) - harmless bookkeeping drift in
                    // standalone, but paired with wrappedContentKey above it matters for real in
                    // group mode: a restored 'private' row's wrap is personal-key-wrapped, and
                    // claiming 'public' would make decryptProfileInPlace try to unwrap it with the
                    // workspace key instead, failing (same "private" vs "collabo" limits as
                    // apiImport's identical comment above - collabo can't be reconstructed either).
                    h.setVisibility("private".equals(m.get("visibility")) ? "private" : "public");
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
        // Produced client-side from this session's already-unwrapped private key, re-wrapped for
        // the new password's KEK (e2eEncryption design doc §3) - the old wrap becomes unusable
        // the instant the password changes, so this must land in the same request/transaction as
        // the password itself, never as a separate follow-up call.
        var newWrappedPrivateKey = (String) body.get("newWrappedPrivateKey");

        if (newPassword == null || newPassword.isBlank()) {
            ctx.status(400).json(Map.of("error", "password_required"));
            return;
        }
        if (newPassword.length() < 8) {
            ctx.status(400).json(Map.of("error", "password_too_short"));
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            ctx.status(400).json(Map.of("error", "password_mismatch"));
            return;
        }
        if (newWrappedPrivateKey == null || newWrappedPrivateKey.isBlank()) {
            ctx.status(400).json(Map.of("error", "crypto_required"));
            return;
        }

        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(hubUser.getUserNo());
            if (target == null) { ctx.status(404); return; }
            if (currentPassword == null || !PasswordUtil.matches(currentPassword, target.getPassword())) {
                ctx.status(400).json(Map.of("error", "current_password_invalid"));
                return;
            }
            target.setPassword(PasswordUtil.hash(newPassword));
            target.setWrappedPrivateKey(newWrappedPrivateKey);
            target.setUpdatedBy(hubUser.getUserNo());
            target.setUpdatedAt(LocalDateTime.now());
            mapper.updatePasswordAndRewrapPrivateKey(target);
            session.commit();
        }
        ctx.status(204);
    }

    // Reissues the recovery-code wrap (e2eEncryption design doc §3/§9 "재발급"): the browser
    // already holds the unwrapped private key (from login) and simply wraps it again under a
    // freshly-generated recovery code, exactly like change-password's re-wrap but for the
    // recovery KEK instead of the password KEK - the private key itself never changes. The old
    // recovery code stops working the instant this lands, since wrapped_private_key_recovery is a
    // single column, not a list.
    public void apiReissueRecoveryKey(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var wrappedPrivateKeyRecovery = (String) body.get("wrappedPrivateKeyRecovery");
        if (wrappedPrivateKeyRecovery == null || wrappedPrivateKeyRecovery.isBlank()) {
            ctx.status(400).json(Map.of("error", "crypto_required"));
            return;
        }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(hubUser.getUserNo());
            if (target == null) { ctx.status(404); return; }
            target.setWrappedPrivateKeyRecovery(wrappedPrivateKeyRecovery);
            target.setUpdatedBy(hubUser.getUserNo());
            target.setUpdatedAt(LocalDateTime.now());
            mapper.updateWrappedPrivateKeyRecovery(target);
            session.commit();
        }
        ctx.status(204);
    }
}
