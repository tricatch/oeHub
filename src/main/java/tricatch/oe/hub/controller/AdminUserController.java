package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.proxy.mapper.ProxyConfMapper;
import tricatch.oe.proxy.service.ProxyVhostService;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AdminUserController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // Excludes visually ambiguous characters (0/O, 1/l/I) since an admin reads this out loud or retypes it.
    private static final String PW_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SqlSessionFactory sqlSessionFactory;
    private final HostsProfService hostsProfService;
    private final ProxyVhostService proxyVhostService;

    public AdminUserController(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.hostsProfService = new HostsProfService(sqlSessionFactory);
        this.proxyVhostService = new ProxyVhostService(sqlSessionFactory);
    }

    public void showUsers(Context ctx) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var model = new HashMap<String, Object>();
            model.put("user", AuthController.currentUser(ctx));
            model.put("users", mapper.findAll());
            ctx.render("templates/oehub/users.pebble", model);
        }
    }

    public void apiSearch(Context ctx) {
        String q = ctx.queryParam("q");
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            List<HubUser> users = (q != null && !q.isBlank())
                    ? mapper.searchByUserId(q.trim())
                    : mapper.findAll();
            var result = new ArrayList<Map<String, Object>>(users.size());
            for (var u : users) {
                var m = new LinkedHashMap<String, Object>();
                m.put("userNo", u.getUserNo());
                m.put("userId", u.getUserId());
                m.put("role", u.getRole());
                m.put("createAt", u.getCreateAt() != null ? u.getCreateAt().format(FMT) : "");
                m.put("updatedAt", u.getUpdatedAt() != null ? u.getUpdatedAt().format(FMT) : "");
                m.put("lastLoginAt", u.getLastLoginAt() != null ? u.getLastLoginAt().format(FMT) : "");
                result.add(m);
            }
            ctx.json(result);
        }
    }

    public void apiDeleteUser(Context ctx) {
        Long userNo;
        try { userNo = Long.parseLong(ctx.pathParam("userNo")); }
        catch (NumberFormatException e) { ctx.status(400).result("Invalid user ID"); return; }
        var currentUser = AuthController.currentUser(ctx);
        if (currentUser != null && userNo.equals(currentUser.getUserNo())) {
            ctx.status(400).result("Cannot delete your own account");
            return;
        }
        try (var session = sqlSessionFactory.openSession()) {
            if (session.getMapper(HubUserMapper.class).findByUserNo(userNo) == null) {
                ctx.status(404).result("User not found");
                return;
            }
        }
        hostsProfService.deleteAll(userNo);
        proxyVhostService.deleteAll(userNo);
        try (var session = sqlSessionFactory.openSession()) {
            session.getMapper(HostsConfMapper.class).deleteAllByUserNo(userNo);
            session.getMapper(ProxyConfMapper.class).deleteAllByUserNo(userNo);
            session.getMapper(HubUserMapper.class).deleteByUserNo(userNo);
            session.commit();
        }
        ctx.status(200).result("OK");
    }

    public void apiSetRole(Context ctx) {
        Long userNo;
        try { userNo = Long.parseLong(ctx.pathParam("userNo")); }
        catch (NumberFormatException e) { ctx.status(400).result("Invalid user ID"); return; }
        @SuppressWarnings("unchecked")
        var body = ctx.bodyAsClass(Map.class);
        String newRole = (String) body.get("role");
        if (!"adm".equals(newRole) && !"usr".equals(newRole)) {
            ctx.status(400).result("Invalid role");
            return;
        }
        var currentUser = AuthController.currentUser(ctx);
        if (currentUser != null && userNo.equals(currentUser.getUserNo()) && "usr".equals(newRole)) {
            ctx.status(400).result("Cannot remove your own admin role");
            return;
        }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(userNo);
            if (target == null) {
                ctx.status(404).result("User not found");
                return;
            }
            target.setRole(newRole);
            target.setUpdatedAt(LocalDateTime.now());
            mapper.updateRole(target);
            session.commit();
            ctx.status(200).result("OK");
        }
    }

    public void apiResetPassword(Context ctx) {
        Long userNo;
        try { userNo = Long.parseLong(ctx.pathParam("userNo")); }
        catch (NumberFormatException e) { ctx.status(400).result("Invalid user ID"); return; }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(userNo);
            if (target == null) {
                ctx.status(404).result("User not found");
                return;
            }
            var newPassword = generatePassword();
            target.setPassword(PasswordUtil.hash(newPassword));
            target.setUpdatedAt(LocalDateTime.now());
            mapper.updatePassword(target);
            session.commit();
            ctx.json(Map.of("password", newPassword));
        }
    }

    private static String generatePassword() {
        var sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(PW_CHARS.charAt(RANDOM.nextInt(PW_CHARS.length())));
        return sb.toString();
    }
}
