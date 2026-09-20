package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hosts.service.HostConfService;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.config.ApiTokenUtil;
import tricatch.oe.hub.config.AuditLogger;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubApiTokenMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.WsKeyMapper;
import tricatch.oe.hub.model.HubApiToken;
import tricatch.oe.proxy.model.ProxyVhost;
import tricatch.oe.proxy.service.ProxyVhostService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class UserController {

    // Both apiChangePassword and apiReissueRecoveryKey re-confirm identity via the account's
    // CURRENT password before a sensitive, session-scoped mutation - neither actually needs it
    // for the crypto itself (both re-wrap this session's already-cached private key), so without
    // a limit either one is just an online brute-force target for a hijacked session cookie that
    // never knew the real password. Sharing one counter/budget across both endpoints (rather than
    // 5 tries each = 10 total) keeps the actual guarantee "5 wrong guesses at this account's
    // password, from any angle, kills the session" - not per-endpoint. Keyed by userNo (both
    // actions always require an already-authenticated session, so there's no anonymous-caller
    // case to rate-limit by IP for instead). In-memory only, same trust boundary as
    // AuthController's per-IP login throttle.
    //
    // expireAfterAccess (not expireAfterWrite): each new failed attempt pushes the 30-minute idle
    // clock back out, so the count only resets after a real lull, not on some fixed schedule from
    // the first failure - a slow, patient guesser is still caught the same as a fast one.
    private static final int MAX_PASSWORD_CONFIRM_ATTEMPTS = 5;
    private final Cache<Long, AtomicInteger> passwordConfirmFailuresByUserNo = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(30))
        .build();

    /** Call when a current-password re-confirmation didn't match; writes the response itself
     *  (either a 400 with a remaining-attempts count, or - past the limit - a 429 after killing
     *  every session for this account) and the caller should return immediately afterward. */
    private void rejectWrongPasswordConfirm(Context ctx, HubUserMapper mapper,
                                             org.apache.ibatis.session.SqlSession session, Long userNo) {
        var attempts = passwordConfirmFailuresByUserNo.get(userNo, k -> new AtomicInteger());
        int count = attempts.incrementAndGet();
        if (count >= MAX_PASSWORD_CONFIRM_ATTEMPTS) {
            mapper.bumpTokenVersionByUserNo(userNo);
            session.commit();
            passwordConfirmFailuresByUserNo.invalidate(userNo);
            ctx.status(429).json(Map.of("error", "too_many_attempts"));
            return;
        }
        ctx.status(400).json(Map.of(
            "error", "current_password_invalid",
            "remainingAttempts", MAX_PASSWORD_CONFIRM_ATTEMPTS - count));
    }

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
        // Same exclusion as HostsController.apiExport - the public link is per-workspace derived
        // data and meaningless (wrappedLinkKey can't be unwrapped) outside it (design doc §6).
        hostsProfiles.forEach(p -> { p.setLinkContent(null); p.setWrappedLinkKey(null); });
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
                    // self-hosted, but paired with wrappedContentKey above it matters for real in
                    // workspace mode: a restored 'private' row's wrap is personal-key-wrapped, and
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
                // Same reasoning as ProxyController.apiImport: keep the live routing in step with
                // what was just written. oeProxy doesn't run in workspace mode, so nothing to do there.
                if (!tricatch.oe.hub.config.AppHome.isWorkspaceMode()) {
                    tricatch.oe.proxy.controller.ProxyController.refreshLiveRouting(sqlSessionFactory, userNo, ctx.ip(), !merge);
                }
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

        var passwordError = PasswordUtil.validateNewPassword(newPassword, confirmPassword);
        if (passwordError != null) {
            ctx.status(400).json(Map.of("error", passwordError));
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
                rejectWrongPasswordConfirm(ctx, mapper, session, hubUser.getUserNo());
                return;
            }
            passwordConfirmFailuresByUserNo.invalidate(hubUser.getUserNo());
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
        var currentPassword = (String) body.get("currentPassword");
        var wrappedPrivateKeyRecovery = (String) body.get("wrappedPrivateKeyRecovery");
        var recoveryVerifier = (String) body.get("recoveryVerifier");
        if (wrappedPrivateKeyRecovery == null || wrappedPrivateKeyRecovery.isBlank()
                || recoveryVerifier == null || recoveryVerifier.isBlank()) {
            ctx.status(400).json(Map.of("error", "crypto_required"));
            return;
        }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(hubUser.getUserNo());
            if (target == null) { ctx.status(404); return; }
            // Re-wrapping itself needs no password (it uses this session's already-unwrapped
            // private key, same as change-password) - this check exists purely so a hijacked
            // session cookie can't silently mint a lasting recovery code without ever knowing the
            // account's actual password. Same convention (and shared attempt budget) as
            // apiChangePassword above.
            if (currentPassword == null || !PasswordUtil.matches(currentPassword, target.getPassword())) {
                rejectWrongPasswordConfirm(ctx, mapper, session, hubUser.getUserNo());
                return;
            }
            passwordConfirmFailuresByUserNo.invalidate(hubUser.getUserNo());
            target.setWrappedPrivateKeyRecovery(wrappedPrivateKeyRecovery);
            target.setRecoveryVerifier(PasswordUtil.hash(recoveryVerifier));
            target.setUpdatedBy(hubUser.getUserNo());
            target.setUpdatedAt(LocalDateTime.now());
            mapper.updateWrappedPrivateKeyRecovery(target);
            session.commit();
        }
        ctx.status(204);
    }

    // Personal API tokens (HUB_API_TOKEN) - see AuthController.resolveUserFromApiToken for the
    // matching auth-side lookup. A token authenticates as its owner with that account's full
    // permissions, so this list/create/delete surface is intentionally self-service only - there
    // is no admin view or revoke-other-users'-tokens endpoint.
    private static final java.time.format.DateTimeFormatter API_TOKEN_FMT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public void apiListApiTokens(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var tokens = session.getMapper(HubApiTokenMapper.class).findByUserNo(hubUser.getUserNo());
            var result = tokens.stream().map(t -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("tokenId", t.getTokenId());
                m.put("tokenName", t.getTokenName());
                m.put("createAt", t.getCreateAt() != null ? t.getCreateAt().format(API_TOKEN_FMT) : "");
                m.put("lastUsedAt", t.getLastUsedAt() != null ? t.getLastUsedAt().format(API_TOKEN_FMT) : null);
                m.put("expiresAt", t.getExpiresAt() != null ? t.getExpiresAt().format(API_TOKEN_FMT) : null);
                return m;
            }).toList();
            ctx.json(result);
        }
    }

    // The generated token is returned exactly once here - only its hash is persisted (see
    // ApiTokenUtil), so this response is the caller's only chance to see/copy it.
    @SuppressWarnings("unchecked")
    public void apiCreateApiToken(Context ctx) throws Exception {
        var hubUser = AuthController.currentUser(ctx);
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var tokenName = (String) body.get("tokenName");
        if (tokenName == null || tokenName.isBlank()) {
            ctx.status(400).json(Map.of("error", "token_name_required"));
            return;
        }
        // Absent/null means "never expires" (the agreed default) - only reject a present-but-
        // non-positive value.
        Integer expiresInDays = body.get("expiresInDays") != null
            ? ((Number) body.get("expiresInDays")).intValue() : null;
        if (expiresInDays != null && expiresInDays <= 0) {
            ctx.status(400).json(Map.of("error", "invalid_expiry"));
            return;
        }

        var generated = ApiTokenUtil.generate();
        var now = LocalDateTime.now();
        var row = new HubApiToken();
        row.setTokenId(UUID.randomUUID().toString().replace("-", ""));
        row.setUserNo(hubUser.getUserNo());
        row.setTokenName(tokenName.trim());
        row.setTokenHash(generated.tokenHash());
        row.setCreatedBy(hubUser.getUserNo());
        row.setUpdatedBy(hubUser.getUserNo());
        row.setCreateAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(expiresInDays != null ? now.plusDays(expiresInDays) : null);

        try (var session = sqlSessionFactory.openSession(true)) {
            session.getMapper(HubApiTokenMapper.class).insert(row);
            AuditLogger.record(session, hubUser.getWsNo(), "api_token.create", "api_token", row.getTokenId(),
                AuditLogger.detail("tokenName", row.getTokenName()), hubUser.getUserNo());
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("tokenId", row.getTokenId());
        result.put("tokenName", row.getTokenName());
        result.put("token", generated.token());
        result.put("expiresAt", row.getExpiresAt() != null ? row.getExpiresAt().format(API_TOKEN_FMT) : null);
        ctx.json(result);
    }

    public void apiDeleteApiToken(Context ctx) {
        var hubUser = AuthController.currentUser(ctx);
        var tokenId = ctx.pathParam("tokenId");
        try (var session = sqlSessionFactory.openSession(true)) {
            int deleted = session.getMapper(HubApiTokenMapper.class).deleteByIdAndUserNo(tokenId, hubUser.getUserNo());
            if (deleted == 0) { ctx.status(404); return; }
            AuditLogger.record(session, hubUser.getWsNo(), "api_token.revoke", "api_token", tokenId,
                null, hubUser.getUserNo());
        }
        ctx.status(204);
    }
}
