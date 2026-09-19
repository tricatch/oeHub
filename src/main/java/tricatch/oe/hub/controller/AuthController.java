package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.config.ApiTokenUtil;
import tricatch.oe.hub.config.AppHome;
import tricatch.oe.hub.config.JwtService;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubApiTokenMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.WorkspaceMapper;
import tricatch.oe.hub.mapper.WsInviteMapper;
import tricatch.oe.hub.mapper.WsKeyMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.hub.model.Workspace;
import tricatch.oe.proxy.controller.ProxyController;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Map;

public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private static final String COOKIE_NAME = "oe_auth";
    private static final String ATTR_USER   = "currentUser";

    // A fixed bcrypt hash checked (and discarded) whenever there's no real user record to compare
    // against, so a login attempt for a nonexistent userId takes about as long as one for a real
    // account with a wrong password — otherwise the early return made account existence
    // enumerable via response timing.
    private static final String DUMMY_PASSWORD_HASH = PasswordUtil.hash("no-such-user-timing-parity");

    // Per-IP login throttle: without it, bcrypt cost is the only thing slowing an online
    // brute-force attempt against a single account. In-memory only (single JVM, same trust
    // boundary as SetupController.SETUP_LOCK) - resets on restart, which is an acceptable
    // trade-off for a self-hosted admin tool.
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final java.time.Duration ATTEMPT_WINDOW = java.time.Duration.ofMinutes(15);
    private static final java.time.Duration LOCKOUT_DURATION = java.time.Duration.ofMinutes(15);

    private static final class LoginAttempts {
        int count;
        java.time.Instant windowStart;
        java.time.Instant lockedUntil;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, LoginAttempts> loginAttemptsByIp = new java.util.concurrent.ConcurrentHashMap<>();

    // Per-IP registration throttle: self-registration otherwise checks only userId format and
    // password strength — nothing stops one IP from scripting unlimited account creation, which
    // would let an attacker mint fresh accounts to route around ForwardProxyServer's per-account
    // auth lockout (each new account starts with a clean lockout counter) or just abuse
    // resources. Separate counters/thresholds from the login throttle since the abuse pattern
    // (repeated POSTs regardless of outcome, not necessarily failures) differs.
    private static final int MAX_REGISTRATIONS_PER_WINDOW = 5;
    private static final java.time.Duration REGISTER_WINDOW = java.time.Duration.ofHours(1);
    private static final java.time.Duration REGISTER_LOCKOUT_DURATION = java.time.Duration.ofHours(1);

    private static final class RegisterAttempts {
        int count;
        java.time.Instant windowStart;
        java.time.Instant lockedUntil;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, RegisterAttempts> registerAttemptsByIp = new java.util.concurrent.ConcurrentHashMap<>();

    private final SqlSessionFactory sqlSessionFactory;
    private final JwtService        jwtService;

    public AuthController(SqlSessionFactory sqlSessionFactory, JwtService jwtService) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.jwtService        = jwtService;
    }

    private boolean isLoginLocked(String ip) {
        var a = loginAttemptsByIp.get(ip);
        if (a == null) return false;
        synchronized (a) {
            return a.lockedUntil != null && java.time.Instant.now().isBefore(a.lockedUntil);
        }
    }

    private void recordLoginFailure(String ip) {
        var a = loginAttemptsByIp.computeIfAbsent(ip, k -> new LoginAttempts());
        synchronized (a) {
            var now = java.time.Instant.now();
            if (a.windowStart == null || java.time.Duration.between(a.windowStart, now).compareTo(ATTEMPT_WINDOW) > 0) {
                a.windowStart = now;
                a.count = 0;
            }
            a.count++;
            if (a.count >= MAX_FAILED_ATTEMPTS) {
                a.lockedUntil = now.plus(LOCKOUT_DURATION);
            }
        }
    }

    private void recordLoginSuccess(String ip) {
        loginAttemptsByIp.remove(ip);
    }

    private boolean isRegisterLocked(String ip) {
        var a = registerAttemptsByIp.get(ip);
        if (a == null) return false;
        synchronized (a) {
            return a.lockedUntil != null && java.time.Instant.now().isBefore(a.lockedUntil);
        }
    }

    private void recordRegisterAttempt(String ip) {
        var a = registerAttemptsByIp.computeIfAbsent(ip, k -> new RegisterAttempts());
        synchronized (a) {
            var now = java.time.Instant.now();
            if (a.windowStart == null || java.time.Duration.between(a.windowStart, now).compareTo(REGISTER_WINDOW) > 0) {
                a.windowStart = now;
                a.count = 0;
            }
            a.count++;
            if (a.count >= MAX_REGISTRATIONS_PER_WINDOW) {
                a.lockedUntil = now.plus(REGISTER_LOCKOUT_DURATION);
            }
        }
    }

    /** Issues a JWT for hubUser and sets the oe_auth cookie - shared by processLogin and
     *  SetupController (which logs the newly-created admin in immediately after account
     *  creation, see the /setup/* auth-gating note in OeHubApplication). */
    public void loginAs(Context ctx, HubUser hubUser, boolean rememberMe) {
        String jwt = jwtService.issue(hubUser.getUserNo(), hubUser.getTokenVersion(), rememberMe);
        ctx.res().addHeader("Set-Cookie", authCookieHeader(ctx, jwt, rememberMe ? JwtService.REMEMBER_ME_SECONDS : null));
    }

    public void resolveUser(Context ctx) {

        String jwt = ctx.cookie(COOKIE_NAME);
        var verified = jwtService.verify(jwt);
        // Never log the JWT itself: it's a bearer credential — anyone who reads the log could
        // replay it as that user until it expires (up to JwtService.REMEMBER_ME_SECONDS with rememberMe).
        if( logger.isDebugEnabled() ) logger.debug("auth, userNo={}, uri={}", verified != null ? verified.userNo() : null, ctx.path());

        if (verified == null) {
            if (jwt != null) {
                ctx.res().addHeader("Set-Cookie", authCookieHeader(ctx, "", 0L));
            }
            return;
        }

        try (var session = sqlSessionFactory.openSession()) {
            var user = session.getMapper(HubUserMapper.class).findByUserNo(verified.userNo());
            if (user != null && user.getTokenVersion() == verified.tokenVersion()) {
                user.setPassword(null);
                ctx.attribute(ATTR_USER, user);
            } else if (user != null) {
                // token_version mismatch: this token was issued before a password change/reset
                // and must no longer be honored, even though its signature/expiry are still valid.
                ctx.res().addHeader("Set-Cookie", authCookieHeader(ctx, "", 0L));
            }
        }
    }

    // Personal API tokens (HUB_API_TOKEN, created via UserController.apiCreateApiToken): a
    // fallback for callers with no browser session cookie, e.g. cron jobs or CLI scripts. Only
    // tried once no valid cookie session was found above, so a browser session always wins if a
    // request somehow carries both. A token authenticates as its owning user_no with that
    // account's full permissions - same as a browser session, no separate scope model.
    //
    // Disabled for now (2026-09-16, unclear real-world use case yet) - resolveUser above no
    // longer calls this, so a Bearer header never authenticates anything even if a token row
    // still exists in HUB_API_TOKEN. Left in place, along with UserController's create/list/
    // delete endpoints (unrouted - see OeHubApplication), so re-enabling later is just restoring
    // the two call sites and three routes.
    @SuppressWarnings("unused")
    private void resolveUserFromApiToken(Context ctx) {
        var header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return;
        var token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) return;

        var tokenHash = ApiTokenUtil.hash(token);
        var now = LocalDateTime.now();
        try (var session = sqlSessionFactory.openSession(true)) {
            var tokenMapper = session.getMapper(HubApiTokenMapper.class);
            var tokenRow = tokenMapper.findValidByHash(tokenHash, now);
            if (tokenRow == null) return;
            var user = session.getMapper(HubUserMapper.class).findByUserNo(tokenRow.getUserNo());
            if (user == null) return;
            user.setPassword(null);
            ctx.attribute(ATTR_USER, user);
            tokenMapper.touchLastUsed(tokenRow.getTokenId(), now);
        }
    }

    public void showLogin(Context ctx) {
        var redirect = ctx.queryParam("redirect");
        var registered = ctx.queryParam("registered");
        var reset = ctx.queryParam("reset");
        ctx.render("templates/login.pebble", Map.of(
            "redirect", redirect != null ? redirect : "",
            "error", "",
            "info", "pending".equals(registered) ? "auth.info.registered.pending"
                  : "done".equals(reset) ? "auth.info.password.reset"
                  : ""
        ));
    }

    public void processLogin(Context ctx) {
        var userId   = ctx.formParam("userId");
        var password   = ctx.formParam("password");
        var redirect   = ctx.formParam("redirect");
        var rememberMe = "on".equals(ctx.formParam("rememberMe"));

        if( logger.isDebugEnabled() ) logger.debug( "login, userId={}", userId);

        String ip = ctx.ip();
        if (isLoginLocked(ip)) {
            ctx.status(429).render("templates/login.pebble", Map.of(
                "redirect", redirect != null ? redirect : "",
                "error", "auth.error.too.many.attempts"
            ));
            return;
        }

        HubUser hubUser = findUser(userId);

        // Always run exactly one bcrypt comparison, real user or not, so a login attempt's
        // response time doesn't reveal whether userId belongs to an existing account.
        String hashToCheck = hubUser != null ? hubUser.getPassword() : DUMMY_PASSWORD_HASH;
        boolean isCorrectPassword = PasswordUtil.matches(password != null ? password : "", hashToCheck);
        logger.debug("login, userId={}, found={}, isCorrectPassword={}", userId, hubUser != null, isCorrectPassword);

        if (hubUser == null || password == null || !isCorrectPassword) {
            recordLoginFailure(ip);
            ctx.render("templates/login.pebble", Map.of(
                "redirect", redirect != null ? redirect : "",
                "error", "auth.error.invalid.credentials"
            ));
            return;
        }
        recordLoginSuccess(ip);

        // 'pending' (awaiting workspace-admin approval) and 'ws_system' (non-login, workspace-
        // owned account) never get a session, even with the right password - cloudGroupService
        // design doc §2.2/§2.3. Same generic error as a wrong password so account state isn't
        // enumerable from the login response.
        if ("pending".equals(hubUser.getRole()) || "ws_system".equals(hubUser.getRole())) {
            ctx.render("templates/login.pebble", Map.of(
                "redirect", redirect != null ? redirect : "",
                "error", "pending".equals(hubUser.getRole()) ? "auth.error.pending.approval" : "auth.error.invalid.credentials"
            ));
            return;
        }

        // Instance-admin workspace suspension (cloudGroupService design doc §2.5 "워크스페이스
        // 정지 처리", §3 item 2) - a distinct, meaningful error rather than reusing the pending-
        // approval one, since this is a different situation for the user to understand (their
        // account itself is fine; the whole workspace was suspended by the instance operator).
        // Only workspace mode has more than one workspace, so self-hosted never needs this check
        // (design doc §2.7).
        if (AppHome.isWorkspaceMode()) {
            try (var session = sqlSessionFactory.openSession()) {
                var workspace = session.getMapper(WorkspaceMapper.class).findByWsNo(hubUser.getWsNo());
                if (workspace != null && "suspended".equals(workspace.getStatus())) {
                    ctx.render("templates/login.pebble", Map.of(
                        "redirect", redirect != null ? redirect : "",
                        "error", "auth.error.workspace.suspended"
                    ));
                    return;
                }
            }
        }

        if(logger.isDebugEnabled() ) logger.debug( "login, userId={}, rememberMe={}", userId, rememberMe);

        try (var session = sqlSessionFactory.openSession()) {
            hubUser.setLastLoginAt(LocalDateTime.now());
            session.getMapper(HubUserMapper.class).updateLastLoginAt(hubUser);
            // Defensive integrity check (e2eEncryption design doc §9): a real, logged-in
            // 'usr'/'ws_adm' member is supposed to always hold a HUB_WS_KEY wrap - approval-time
            // bundling (§5) and workspace founding (§4) both create it in the same transaction as
            // the role/account itself, so this should never actually fire. If it does (e.g. a
            // hand-edited DB, or a bug in one of those paths), the user isn't locked out - their
            // own 'private' content still works via their personal key - but they silently can't
            // decrypt any 'public'/'collabo' content, which is confusing without a trace. Just log
            // for now rather than auto-reconcile (no browser is available server-side to mint a
            // fresh wrap, and only a ws_adm's browser could ever re-wrap the real workspace key).
            if (AppHome.isWorkspaceMode() && ("usr".equals(hubUser.getRole()) || "ws_adm".equals(hubUser.getRole()))) {
                var wsKey = session.getMapper(WsKeyMapper.class).findByWsNoAndUserNo(hubUser.getWsNo(), hubUser.getUserNo());
                if (wsKey == null) {
                    logger.warn("Integrity check failed: user '{}' (userNo={}, wsNo={}) has no HUB_WS_KEY wrap - "
                        + "they will be unable to decrypt workspace-shared (public/collabo) content until a "
                        + "ws_adm re-wraps the workspace key for them.", userId, hubUser.getUserNo(), hubUser.getWsNo());
                }
            }
            session.commit();
        }

        ProxyController.applyMergedConfig(sqlSessionFactory, hubUser.getUserNo(), ctx.ip());

        loginAs(ctx, hubUser, rememberMe);

        ctx.redirect(isSafeRedirect(redirect) ? redirect : "/");
    }

    /**
     * Builds the oe_auth Set-Cookie header value. maxAgeSeconds null = session cookie (no
     * Max-Age); non-null (including 0, used to clear the cookie) sets it explicitly. Adds
     * "Secure" only when this request itself arrived over HTTPS - unconditionally adding it would
     * make the cookie silently stop being sent on a plain-HTTP deployment of oeHub itself.
     */
    private static String authCookieHeader(Context ctx, String value, Long maxAgeSeconds) {
        var sb = new StringBuilder(COOKIE_NAME).append('=').append(value).append("; Path=/");
        if (maxAgeSeconds != null) sb.append("; Max-Age=").append(maxAgeSeconds);
        sb.append("; HttpOnly; SameSite=Lax");
        if ("https".equalsIgnoreCase(ctx.scheme())) sb.append("; Secure");
        return sb.toString();
    }

    /** Only an absolute path with no scheme/host smuggled in - rejects "//evil.com" and the
     *  backslash variant "/\evil.com" some browsers normalize into a protocol-relative URL.
     *  Package-visible (not private) so a test can exercise it directly. */
    static boolean isSafeRedirect(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return false;
        return path.length() == 1 || (path.charAt(1) != '/' && path.charAt(1) != '\\');
    }

    public void showRegister(Context ctx) {
        var model = new java.util.HashMap<String, Object>();
        model.put("error", "");
        model.put("userId", "");
        model.put("inviteCode", "");
        model.put("wsName", "");
        model.put("inviteWsName", "");
        model.put("inviteError", "");

        // Only workspace mode has multiple workspaces to create/join - self-hosted always joins the
        // single existing one, no picker needed (cloudGroupService design doc §2.7).
        if (AppHome.isWorkspaceMode()) {
            var inviteCode = ctx.queryParam("invite");
            if (inviteCode != null && !inviteCode.isBlank()) {
                try (var session = sqlSessionFactory.openSession()) {
                    var invite = session.getMapper(WsInviteMapper.class).findByCode(inviteCode.trim());
                    if (invite == null || invite.isUsed() || invite.getExpiresAt().isBefore(LocalDateTime.now())) {
                        model.put("inviteError", "auth.error.invite.invalid");
                    } else {
                        var workspace = session.getMapper(WorkspaceMapper.class).findByWsNo(invite.getWsNo());
                        model.put("inviteCode", inviteCode.trim());
                        model.put("inviteWsName", workspace != null ? workspace.getWsName() : "");
                    }
                }
            }
        }

        ctx.render("templates/register.pebble", model);
    }

    public void processRegister(Context ctx) {
        String ip = ctx.ip();
        if (isRegisterLocked(ip)) {
            ctx.status(429).render("templates/register.pebble", Map.of("error", "auth.error.register.too.many.attempts", "userId", ""));
            return;
        }
        recordRegisterAttempt(ip);

        var userId        = ctx.formParam("userId");
        var password        = ctx.formParam("password");
        var confirmPassword = ctx.formParam("confirmPassword");

        if (userId == null || userId.isBlank()) {
            renderRegisterError(ctx, "auth.error.userid.required", "");
            return;
        }
        if (userId.length() < 4) {
            renderRegisterError(ctx, "auth.error.userid.too.short", userId);
            return;
        }
        if (!userId.matches("[A-Za-z0-9._-]+")) {
            renderRegisterError(ctx, "auth.error.userid.invalid.chars", userId);
            return;
        }
        if (password == null || password.isBlank()) {
            renderRegisterError(ctx, "auth.error.password.required", userId);
            return;
        }
        if (password.length() < 8) {
            renderRegisterError(ctx, "auth.error.password.too.short", userId);
            return;
        }
        if (!password.equals(confirmPassword)) {
            renderRegisterError(ctx, "auth.error.password.mismatch", userId);
            return;
        }
        if (findUser(userId) != null) {
            renderRegisterError(ctx, "auth.error.userid.exists", userId);
            return;
        }

        // Every account gets a personal keypair from here on (e2eEncryption design doc §9's
        // "언제 필수로 만들지" question, resolved: mandatory as of this wiring) - register.pebble
        // generates it client-side via crypto.js before this endpoint is ever called, so a
        // missing value means the browser couldn't do WebCrypto (or JS was disabled/bypassed).
        var publicKey = ctx.formParam("publicKey");
        var wrappedPrivateKey = ctx.formParam("wrappedPrivateKey");
        var wrappedPrivateKeyRecovery = ctx.formParam("wrappedPrivateKeyRecovery");
        var recoveryVerifier = ctx.formParam("recoveryVerifier");
        if (publicKey == null || publicKey.isBlank() || wrappedPrivateKey == null || wrappedPrivateKey.isBlank()
                || wrappedPrivateKeyRecovery == null || wrappedPrivateKeyRecovery.isBlank()
                || recoveryVerifier == null || recoveryVerifier.isBlank()) {
            renderRegisterError(ctx, "auth.error.crypto.required", userId);
            return;
        }

        var user = new HubUser();
        var now = LocalDateTime.now();
        user.setUserId(userId);
        user.setPassword(PasswordUtil.hash(password));
        user.setPublicKey(publicKey);
        user.setWrappedPrivateKey(wrappedPrivateKey);
        user.setWrappedPrivateKeyRecovery(wrappedPrivateKeyRecovery);
        user.setRecoveryVerifier(PasswordUtil.hash(recoveryVerifier));
        user.setUpdatedAt(now);
        user.setCreateAt(now);

        // Single rule, no oe.mode branching on the ROLE OUTCOME (cloudGroupService design doc
        // §2.3/§2.7, e2eEncryption design doc §5): only the person founding a new workspace
        // becomes its ws_adm immediately, everyone else starts 'pending'. What DOES depend on
        // mode is which of those two paths this form even offers - self-hosted has exactly one
        // workspace by construction, so it never creates a new one or takes an invite code here.
        boolean workspaceMode = AppHome.isWorkspaceMode();
        var inviteCode = ctx.formParam("inviteCode");
        var wsName = ctx.formParam("wsName");

        try (var session = sqlSessionFactory.openSession()) {
            var wsMapper = session.getMapper(WorkspaceMapper.class);
            var userMapper = session.getMapper(HubUserMapper.class);

            if (workspaceMode && inviteCode != null && !inviteCode.isBlank()) {
                var invite = session.getMapper(WsInviteMapper.class).findByCode(inviteCode.trim());
                if (invite == null || invite.isUsed() || invite.getExpiresAt().isBefore(now)) {
                    renderRegisterError(ctx, "auth.error.invite.invalid", userId);
                    return;
                }
                user.setRole("pending");
                user.setWsNo(invite.getWsNo());
                // A team-scoped invite (cloudGroupService design doc §2.8/§2.9) auto-assigns the
                // new member to that team at signup - null when the invite didn't specify one.
                user.setTeamNo(invite.getTeamNo());
                userMapper.insert(user);
                userMapper.selfReferenceAudit(user.getUserNo());
                // Atomic consume, after the insert so it has the new user's user_no for
                // updated_by - 0 affected rows means someone else consumed/expired it in the
                // gap since findByCode() above (cloudGroupService design doc §2.8 race defense).
                if (session.getMapper(WsInviteMapper.class).consume(inviteCode.trim(), user.getUserNo(), now) == 0) {
                    renderRegisterError(ctx, "auth.error.invite.invalid", userId);
                    return;
                }
                session.commit();
                ctx.redirect("/login?registered=pending");
                return;
            }

            if (workspaceMode) {
                if (wsName == null || wsName.isBlank()) {
                    renderRegisterError(ctx, "auth.error.wsname.required", userId);
                    return;
                }
                if (wsMapper.findByWsName(wsName.trim()) != null) {
                    renderRegisterError(ctx, "auth.error.wsname.exists", userId);
                    return;
                }
                // The founder generates the workspace key itself, client-side, and wraps it for
                // their own public key in the same step as the personal keypair above
                // (e2eEncryption design doc §4 "워크스페이스키는 워크스페이스 생성과 함께 만든다").
                var founderWrappedWsKey = ctx.formParam("founderWrappedWsKey");
                if (founderWrappedWsKey == null || founderWrappedWsKey.isBlank()) {
                    renderRegisterError(ctx, "auth.error.crypto.required", userId);
                    return;
                }
                var workspace = new Workspace();
                workspace.setWsName(wsName.trim());
                workspace.setStatus("active");
                workspace.setCreateAt(now);
                workspace.setUpdatedAt(now);
                wsMapper.insert(workspace);

                user.setRole("ws_adm");
                user.setWsNo(workspace.getWsNo());
                userMapper.insert(user);
                userMapper.selfReferenceAudit(user.getUserNo());
                wsMapper.backfillAudit(workspace.getWsNo(), user.getUserNo(), now);

                var wsKey = new tricatch.oe.hub.model.WsKey();
                wsKey.setWsNo(workspace.getWsNo());
                wsKey.setUserNo(user.getUserNo());
                wsKey.setWrappedWsKey(founderWrappedWsKey);
                wsKey.setCreatedBy(user.getUserNo());
                wsKey.setUpdatedBy(user.getUserNo());
                wsKey.setCreateAt(now);
                wsKey.setUpdatedAt(now);
                session.getMapper(tricatch.oe.hub.mapper.WsKeyMapper.class).insert(wsKey);

                // Non-login, workspace-owned system account for orphaned-resource ownership later
                // (cloudGroupService design doc §2.2) - created alongside every new workspace, the
                // same as SetupController.processSetup's self-hosted bootstrap, so a "no ws_system
                // yet" state never exists here either.
                var wsSystemUser = new HubUser();
                wsSystemUser.setUserId("__ws_system_" + workspace.getWsNo());
                wsSystemUser.setPassword(PasswordUtil.hash(java.util.UUID.randomUUID().toString()));
                wsSystemUser.setRole("ws_system");
                wsSystemUser.setWsNo(workspace.getWsNo());
                wsSystemUser.setCreatedBy(user.getUserNo());
                wsSystemUser.setUpdatedBy(user.getUserNo());
                wsSystemUser.setCreateAt(now);
                wsSystemUser.setUpdatedAt(now);
                userMapper.insert(wsSystemUser);

                session.commit();
                ctx.redirect("/login");
                return;
            }

            // self-hosted: always joins the single existing workspace as 'pending'.
            var workspace = wsMapper.findFirst();
            if (workspace == null) {
                // Can't happen in practice - /setup always creates the workspace before
                // registration is reachable - but fail loudly rather than insert an orphaned user.
                renderRegisterError(ctx, "auth.error.no.workspace", userId);
                return;
            }
            user.setRole("pending");
            user.setWsNo(workspace.getWsNo());
            userMapper.insert(user);
            userMapper.selfReferenceAudit(user.getUserNo());
            session.commit();
        }

        ctx.redirect("/login?registered=pending");
    }

    private void renderRegisterError(Context ctx, String error, String userId) {
        // Re-echo whatever invite/workspace-name fields were actually submitted, so retrying
        // after a validation error (e.g. password mismatch) doesn't silently fall through to the
        // "create new workspace" branch on the next submit just because the hidden field went
        // missing from the rendered form.
        var inviteCode = ctx.formParam("inviteCode");
        var wsName = ctx.formParam("wsName");
        var model = new java.util.HashMap<String, Object>();
        model.put("error", error);
        model.put("userId", userId);
        model.put("inviteCode", inviteCode != null ? inviteCode : "");
        model.put("wsName", wsName != null ? wsName : "");
        model.put("inviteWsName", "");
        model.put("inviteError", "");
        if (AppHome.isWorkspaceMode() && inviteCode != null && !inviteCode.isBlank()) {
            try (var session = sqlSessionFactory.openSession()) {
                var invite = session.getMapper(WsInviteMapper.class).findByCode(inviteCode.trim());
                if (invite != null && !invite.isUsed() && invite.getExpiresAt().isAfter(LocalDateTime.now())) {
                    var workspace = session.getMapper(WorkspaceMapper.class).findByWsNo(invite.getWsNo());
                    model.put("inviteWsName", workspace != null ? workspace.getWsName() : "");
                }
            }
        }
        ctx.render("templates/register.pebble", model);
    }

    public void showRecover(Context ctx) {
        ctx.render("templates/recover.pebble", Map.of());
    }

    /** Step 1 of the recovery flow (e2eEncryption design doc §3 "복구 플로우 프로토콜"): verifies
     *  the caller holds the recovery code without the server ever seeing the code itself, by
     *  checking a one-way HKDF derivation of it (recoveryVerifier) against the bcrypt hash stored
     *  at signup/reissue/reset time. Shares the login throttle and its DUMMY_PASSWORD_HASH timing-
     *  parity pattern, and returns one generic error for every failure mode (unknown user, a
     *  pre-this-feature account with no verifier yet, or a wrong code) so account existence isn't
     *  enumerable. */
    @SuppressWarnings("unchecked")
    public void apiRecoverVerify(Context ctx) {
        String ip = ctx.ip();
        if (isLoginLocked(ip)) {
            ctx.status(429).json(Map.of("error", "too_many_attempts"));
            return;
        }

        var body = ctx.bodyAsClass(Map.class);
        var userId = (String) body.get("userId");
        var recoveryVerifier = (String) body.get("recoveryVerifier");

        HubUser hubUser = findUser(userId);
        String hashToCheck = (hubUser != null && hubUser.getRecoveryVerifier() != null)
            ? hubUser.getRecoveryVerifier() : DUMMY_PASSWORD_HASH;
        boolean isCorrect = PasswordUtil.matches(recoveryVerifier != null ? recoveryVerifier : "", hashToCheck);

        if (hubUser == null || hubUser.getRecoveryVerifier() == null || recoveryVerifier == null || !isCorrect) {
            recordLoginFailure(ip);
            ctx.status(400).json(Map.of("error", "invalid"));
            return;
        }

        ctx.json(Map.of("wrappedPrivateKeyRecovery", hubUser.getWrappedPrivateKeyRecovery()));
    }

    /** Step 2 of the recovery flow: re-verifies the same old recoveryVerifier (proof from step 1
     *  isn't carried server-side anywhere - re-sending it here is what makes a separate reset
     *  token/session unnecessary, design doc §3), then replaces password + both recovery columns
     *  in one update and bumps token_version to invalidate any existing session/remember-me
     *  cookie - same reasoning as a self-service password change. recordLoginSuccess only fires
     *  here, on the actual reset, not on step 1's mere verification. */
    @SuppressWarnings("unchecked")
    public void apiRecoverReset(Context ctx) {
        String ip = ctx.ip();
        if (isLoginLocked(ip)) {
            ctx.status(429).json(Map.of("error", "too_many_attempts"));
            return;
        }

        var body = ctx.bodyAsClass(Map.class);
        var userId = (String) body.get("userId");
        var recoveryVerifier = (String) body.get("recoveryVerifier");
        var newPassword = (String) body.get("newPassword");
        var confirmPassword = (String) body.get("confirmPassword");
        var newWrappedPrivateKey = (String) body.get("newWrappedPrivateKey");
        var newWrappedPrivateKeyRecovery = (String) body.get("newWrappedPrivateKeyRecovery");
        var newRecoveryVerifier = (String) body.get("newRecoveryVerifier");

        HubUser hubUser = findUser(userId);
        String hashToCheck = (hubUser != null && hubUser.getRecoveryVerifier() != null)
            ? hubUser.getRecoveryVerifier() : DUMMY_PASSWORD_HASH;
        boolean isCorrect = PasswordUtil.matches(recoveryVerifier != null ? recoveryVerifier : "", hashToCheck);

        if (hubUser == null || hubUser.getRecoveryVerifier() == null || recoveryVerifier == null || !isCorrect) {
            recordLoginFailure(ip);
            ctx.status(400).json(Map.of("error", "invalid"));
            return;
        }

        var passwordError = PasswordUtil.validateNewPassword(newPassword, confirmPassword);
        if (passwordError != null) {
            ctx.status(400).json(Map.of("error", passwordError));
            return;
        }
        if (newWrappedPrivateKey == null || newWrappedPrivateKey.isBlank()
                || newWrappedPrivateKeyRecovery == null || newWrappedPrivateKeyRecovery.isBlank()
                || newRecoveryVerifier == null || newRecoveryVerifier.isBlank()) {
            ctx.status(400).json(Map.of("error", "crypto_required"));
            return;
        }

        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            hubUser.setPassword(PasswordUtil.hash(newPassword));
            hubUser.setWrappedPrivateKey(newWrappedPrivateKey);
            hubUser.setWrappedPrivateKeyRecovery(newWrappedPrivateKeyRecovery);
            hubUser.setRecoveryVerifier(PasswordUtil.hash(newRecoveryVerifier));
            hubUser.setUpdatedBy(hubUser.getUserNo());
            hubUser.setUpdatedAt(LocalDateTime.now());
            mapper.resetPasswordViaRecovery(hubUser);
            session.commit();
        }

        recordLoginSuccess(ip);
        ctx.status(204);
    }

    public void logout(Context ctx) {
        ctx.res().addHeader("Set-Cookie", authCookieHeader(ctx, "", 0L));
        ctx.redirect("/login");
    }

    public static HubUser currentUser(Context ctx) {
        return ctx.attribute(ATTR_USER);
    }

    // A user can manage their own workspace's members if they hold 'ws_adm', or - only in
    // self-hosted, which never assigns 'ws_adm' at all (cloudGroupService design doc §2.7's
    // simplification) - the instance-wide 'adm'. In workspace mode 'adm' is deliberately excluded:
    // the instance operator must not manage workspace-internal user data (design doc §2.5).
    public static boolean isWorkspaceAdmin(HubUser user) {
        if (user == null) return false;
        if ("ws_adm".equals(user.getRole())) return true;
        return !tricatch.oe.hub.config.AppHome.isWorkspaceMode() && "adm".equals(user.getRole());
    }

    private HubUser findUser(String userId) {
        if (userId == null) return null;
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HubUserMapper.class).findByUserId(userId);
        }
    }
}
