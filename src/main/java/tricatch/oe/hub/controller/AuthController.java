package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.config.JwtService;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
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

    /** Issues a JWT for hubUser and sets the oe_auth cookie - shared by processLogin and
     *  SetupController (which logs the newly-created admin in immediately after account
     *  creation, see the /setup/* auth-gating note in OeHubApplication). */
    public void loginAs(Context ctx, HubUser hubUser, boolean rememberMe) {
        String jwt = jwtService.issue(hubUser.getUserNo(), hubUser.getTokenVersion(), rememberMe);
        ctx.res().addHeader("Set-Cookie", authCookieHeader(ctx, jwt, rememberMe ? 365L * 24 * 3600 : null));
    }

    public void resolveUser(Context ctx) {

        String jwt = ctx.cookie(COOKIE_NAME);
        var verified = jwtService.verify(jwt);
        // Never log the JWT itself: it's a bearer credential — anyone who reads the log could
        // replay it as that user until it expires (up to 365 days with rememberMe).
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

    public void showLogin(Context ctx) {
        var redirect = ctx.queryParam("redirect");
        ctx.render("templates/login.pebble", Map.of(
            "redirect", redirect != null ? redirect : "",
            "error", ""
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

        if(logger.isDebugEnabled() ) logger.debug( "login, userId={}, rememberMe={}", userId, rememberMe);

        try (var session = sqlSessionFactory.openSession()) {
            hubUser.setLastLoginAt(LocalDateTime.now());
            session.getMapper(HubUserMapper.class).updateLastLoginAt(hubUser);
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
        ctx.render("templates/register.pebble", Map.of("error", "", "userId", ""));
    }

    public void processRegister(Context ctx) {
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

        var user = new HubUser();
        var now = LocalDateTime.now();
        user.setUserId(userId);
        user.setPassword(PasswordUtil.hash(password));
        user.setRole("usr");
        user.setUpdatedAt(now);
        user.setCreateAt(now);

        try (var session = sqlSessionFactory.openSession()) {
            session.getMapper(HubUserMapper.class).insert(user);
            session.commit();
        }

        ctx.redirect("/login");
    }

    private void renderRegisterError(Context ctx, String error, String userId) {
        ctx.render("templates/register.pebble", Map.of("error", error, "userId", userId));
    }

    public void logout(Context ctx) {
        ctx.res().addHeader("Set-Cookie", authCookieHeader(ctx, "", 0L));
        ctx.redirect("/login");
    }

    public static HubUser currentUser(Context ctx) {
        return ctx.attribute(ATTR_USER);
    }

    private HubUser findUser(String userId) {
        if (userId == null) return null;
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HubUserMapper.class).findByUserId(userId);
        }
    }
}
