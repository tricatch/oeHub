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

    private final SqlSessionFactory sqlSessionFactory;
    private final JwtService        jwtService;

    public AuthController(SqlSessionFactory sqlSessionFactory, JwtService jwtService) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.jwtService        = jwtService;
    }

    public void resolveUser(Context ctx) {

        String jwt = ctx.cookie(COOKIE_NAME);
        Long userNo = jwtService.verify(jwt);
        if( logger.isDebugEnabled() ) logger.debug("auth, userNo={}, uri={}, jwt={}", userNo, ctx.path(), jwt);

        if (userNo == null) {
            if (jwt != null) {
                ctx.res().addHeader("Set-Cookie", COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
            }
            return;
        }

        try (var session = sqlSessionFactory.openSession()) {
            var user = session.getMapper(HubUserMapper.class).findByUserNo(userNo);
            if (user != null) {
                user.setPassword(null);
                ctx.attribute(ATTR_USER, user);
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

        HubUser hubUser = findUser(userId);
        if( hubUser == null ){
            logger.warn("login, not found user - {}", userId);
            ctx.render("templates/login.pebble", Map.of(
                    "redirect", redirect != null ? redirect : "",
                    "error", "auth.error.invalid.credentials"
            ));
            return;
        }

        if (password == null) {
            ctx.render("templates/login.pebble", Map.of(
                "redirect", redirect != null ? redirect : "",
                "error", "auth.error.invalid.credentials"
            ));
            return;
        }
        boolean isCorrectPassword = PasswordUtil.matches(password, hubUser.getPassword());
        logger.debug("login, userId={}, isCorrectPassword={}", userId, isCorrectPassword);

        if (!isCorrectPassword) {
            ctx.render("templates/login.pebble", Map.of(
                "redirect", redirect != null ? redirect : "",
                "error", "auth.error.invalid.credentials"
            ));
            return;
        }

        String jwt = jwtService.issue(hubUser.getUserNo(), rememberMe);

        if(logger.isDebugEnabled() ) logger.debug( "login, userId={}, jwt={}", userId, jwt);

        try (var session = sqlSessionFactory.openSession()) {
            hubUser.setLastLoginAt(LocalDateTime.now());
            session.getMapper(HubUserMapper.class).updateLastLoginAt(hubUser);
            session.commit();
        }

        ProxyController.applyMergedConfig(sqlSessionFactory, hubUser.getUserNo(), ctx.ip());

        String header = rememberMe
            ? COOKIE_NAME + "=" + jwt + "; Path=/; Max-Age=" + (365L * 24 * 3600) + "; HttpOnly; SameSite=Lax"
            : COOKIE_NAME + "=" + jwt + "; Path=/; HttpOnly; SameSite=Lax";
        ctx.res().addHeader("Set-Cookie", header);

        if (redirect != null && redirect.startsWith("/") && !redirect.startsWith("//")) {
            ctx.redirect(redirect);
        } else {
            ctx.redirect("/");
        }
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
        if (password.length() < 4) {
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
        ctx.res().addHeader("Set-Cookie",
            COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
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
