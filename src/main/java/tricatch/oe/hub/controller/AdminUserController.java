package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.WsInviteMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.hub.model.WsInvite;
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
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var model = new HashMap<String, Object>();
            model.put("user", currentUser);
            var users = mapper.findAll(currentUser.getWsNo());
            for (var u : users) u.setPassword(null);
            model.put("users", users);
            ctx.render("templates/oehub/users.pebble", model);
        }
    }

    public void apiSearch(Context ctx) {
        String q = ctx.queryParam("q");
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            List<HubUser> users = (q != null && !q.isBlank())
                    ? mapper.searchByUserId(currentUser.getWsNo(), q.trim())
                    : mapper.findAll(currentUser.getWsNo());
            var result = new ArrayList<Map<String, Object>>(users.size());
            for (var u : users) {
                var m = new LinkedHashMap<String, Object>();
                m.put("userNo", u.getUserNo());
                m.put("userId", u.getUserId());
                m.put("role", u.getRole());
                m.put("createAt", u.getCreateAt() != null ? u.getCreateAt().format(FMT) : "");
                m.put("updatedAt", u.getUpdatedAt() != null ? u.getUpdatedAt().format(FMT) : "");
                m.put("lastLoginAt", u.getLastLoginAt() != null ? u.getLastLoginAt().format(FMT) : "");
                // Needed client-side to wrap a freshly-rotated workspace key for this member
                // when another member is deleted (e2eEncryption design doc §7).
                m.put("publicKey", u.getPublicKey());
                result.add(m);
            }
            ctx.json(result);
        }
    }

    // "가입 승인 대기" list (cloudGroupService design doc §2.8) - scoped to the caller's own
    // workspace, a no-op filter in standalone (exactly one workspace) but already correct once a
    // second workspace can exist.
    public void apiListPending(Context ctx) {
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var pending = session.getMapper(HubUserMapper.class).findAllPendingByWsNo(currentUser.getWsNo());
            var result = new ArrayList<Map<String, Object>>(pending.size());
            for (var u : pending) {
                var m = new LinkedHashMap<String, Object>();
                m.put("userNo", u.getUserNo());
                m.put("userId", u.getUserId());
                m.put("createAt", u.getCreateAt() != null ? u.getCreateAt().format(FMT) : "");
                // The approving ws_adm's browser wraps its cached workspace key for this public
                // key before calling apiApprovePending (e2eEncryption design doc §5).
                m.put("publicKey", u.getPublicKey());
                result.add(m);
            }
            ctx.json(result);
        }
    }

    // Approval bundles the role flip (pending -> usr) with the workspace-key wrap for the new
    // member into one transaction, so "usr without a key wrap" never exists as a state
    // (e2eEncryption design doc §5). wrappedWsKey is produced client-side, by the approving
    // ws_adm's browser, from its own cached (in-memory/session-keys.js) workspace key wrapped for
    // the pending user's public_key - the server never sees the unwrapped workspace key itself.
    public void apiApprovePending(Context ctx) {
        Long userNo;
        try { userNo = Long.parseLong(ctx.pathParam("userNo")); }
        catch (NumberFormatException e) { ctx.status(400).result("Invalid user ID"); return; }
        @SuppressWarnings("unchecked")
        var body = ctx.bodyAsClass(Map.class);
        String wrappedWsKey = (String) body.get("wrappedWsKey");
        if (wrappedWsKey == null || wrappedWsKey.isBlank()) {
            ctx.status(400).result("wrappedWsKey required");
            return;
        }
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(userNo);
            if (target == null || !"pending".equals(target.getRole()) || !target.getWsNo().equals(currentUser.getWsNo())) {
                ctx.status(404).result("User not found");
                return;
            }
            var now = LocalDateTime.now();
            target.setRole("usr");
            target.setUpdatedBy(currentUser.getUserNo());
            target.setUpdatedAt(now);
            mapper.updateRole(target);

            var wsKey = new tricatch.oe.hub.model.WsKey();
            wsKey.setWsNo(target.getWsNo());
            wsKey.setUserNo(target.getUserNo());
            wsKey.setWrappedWsKey(wrappedWsKey);
            wsKey.setCreatedBy(currentUser.getUserNo());
            wsKey.setUpdatedBy(currentUser.getUserNo());
            wsKey.setCreateAt(now);
            wsKey.setUpdatedAt(now);
            session.getMapper(tricatch.oe.hub.mapper.WsKeyMapper.class).insert(wsKey);

            session.commit();
            ctx.status(200).result("OK");
        }
    }

    // A rejected applicant never had a chance to own anything (login was blocked the whole time),
    // so this is a plain delete - no HostsProf/ProxyVhost/... cleanup needed, unlike apiDeleteUser
    // below (cloudGroupService design doc §2.8 "거부").
    public void apiRejectPending(Context ctx) {
        Long userNo;
        try { userNo = Long.parseLong(ctx.pathParam("userNo")); }
        catch (NumberFormatException e) { ctx.status(400).result("Invalid user ID"); return; }
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(userNo);
            if (target == null || !"pending".equals(target.getRole()) || !target.getWsNo().equals(currentUser.getWsNo())) {
                ctx.status(404).result("User not found");
                return;
            }
            mapper.deleteByUserNo(userNo);
            session.commit();
            ctx.status(200).result("OK");
        }
    }

    // Workspace-key rotation, step 1/2 (e2eEncryption design doc §7): the rows the rotating
    // ws_adm's browser needs to unwrap (with the OLD workspace key it already has cached) and
    // re-wrap (with a freshly-generated new one) - across the WHOLE workspace, not just the
    // caller's own rows, since 'public'/'collabo' content is shared. PROXY_VHOST is deliberately
    // excluded (never encrypted in any mode - design doc §1).
    public void apiWorkspaceRotationRows(Context ctx) {
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var rows = session.getMapper(tricatch.oe.hosts.mapper.HostsProfMapper.class).findEncryptedRowsByWsNo(currentUser.getWsNo());
            var result = new ArrayList<Map<String, Object>>(rows.size());
            for (var r : rows) {
                var m = new LinkedHashMap<String, Object>();
                m.put("hostsId", r.getHostsId());
                m.put("wrappedContentKey", r.getWrappedContentKey());
                m.put("wrappedLinkKey", r.getWrappedLinkKey());
                result.add(m);
            }
            ctx.json(result);
        }
    }

    // Workspace-key rotation, step 2/2: applies the ws_adm's browser's freshly-computed wraps.
    // The server never sees the unwrapped old or new workspace key, or any row's DEK - only the
    // already-wrapped output of client-side crypto, same server-blind pattern as approval (§5).
    // Both lists are silently scoped/clamped to the caller's own workspace (defense in depth - a
    // forged foreign userNo/hostsId is simply skipped rather than erroring, since a partial
    // rotation is still strictly better than none and the design doc §9 already flags full
    // resume-on-failure handling as unresolved).
    @SuppressWarnings("unchecked")
    public void apiRotateWorkspaceKey(Context ctx) {
        var currentUser = AuthController.currentUser(ctx);
        var body = ctx.bodyAsClass(Map.class);
        var memberWraps = (List<Map<String, Object>>) body.get("memberWraps");
        var contentRewraps = (List<Map<String, Object>>) body.get("contentRewraps");
        var now = LocalDateTime.now();
        try (var session = sqlSessionFactory.openSession()) {
            var hubUserMapper = session.getMapper(HubUserMapper.class);
            var wsKeyMapper = session.getMapper(tricatch.oe.hub.mapper.WsKeyMapper.class);
            if (memberWraps != null) {
                for (var entry : memberWraps) {
                    var userNo = ((Number) entry.get("userNo")).longValue();
                    var wrappedWsKey = (String) entry.get("wrappedWsKey");
                    var target = hubUserMapper.findByUserNo(userNo);
                    if (target == null || !target.getWsNo().equals(currentUser.getWsNo()) || wrappedWsKey == null) continue;
                    wsKeyMapper.updateWrappedWsKey(currentUser.getWsNo(), userNo, wrappedWsKey, currentUser.getUserNo(), now);
                }
            }
            if (contentRewraps != null) {
                var hostsMapper = session.getMapper(tricatch.oe.hosts.mapper.HostsProfMapper.class);
                for (var entry : contentRewraps) {
                    var hostsId = (String) entry.get("hostsId");
                    var wrappedContentKey = (String) entry.get("wrappedContentKey");
                    if (hostsId == null || wrappedContentKey == null) continue;
                    hostsMapper.updateWrappedContentKeyForRotation(hostsId, currentUser.getWsNo(), wrappedContentKey);
                    // Present only for rows that also have a live public link (e2eEncryption
                    // design doc §6 "living link" redesign) - missing this would silently break
                    // every live link the moment content is next saved after rotation.
                    var wrappedLinkKey = (String) entry.get("wrappedLinkKey");
                    if (wrappedLinkKey != null) {
                        hostsMapper.updateWrappedLinkKeyForRotation(hostsId, currentUser.getWsNo(), wrappedLinkKey);
                    }
                }
            }
            session.commit();
        }
        ctx.status(200).result("OK");
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
            var target = session.getMapper(HubUserMapper.class).findByUserNo(userNo);
            // 404 (not 403) for a cross-workspace target too, so this endpoint never confirms
            // another workspace's user_no exists (cloudGroupService design doc §2.5 isolation).
            if (target == null || !target.getWsNo().equals(currentUser.getWsNo())) {
                ctx.status(404).result("User not found");
                return;
            }
        }
        hostsProfService.deleteAll(userNo);
        proxyVhostService.deleteAll(userNo);
        try (var session = sqlSessionFactory.openSession()) {
            session.getMapper(HostsConfMapper.class).deleteAllByUserNo(userNo);
            session.getMapper(ProxyConfMapper.class).deleteAllByUserNo(userNo);
            session.getMapper(HostsUaMapper.class).deleteAllByUserNo(userNo);
            session.getMapper(HostsUrlMapper.class).deleteAllByUserNo(userNo);
            // Must run before HUB_USR's own delete just below - HUB_WS_KEY has a real (not soft)
            // FK on user_no (e2eEncryption design doc §3/§9), so deleting the user first would
            // fail the constraint outright rather than leaving an orphaned row.
            session.getMapper(tricatch.oe.hub.mapper.WsKeyMapper.class).deleteByUserNo(userNo);
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
        // The "promote to admin" toggle targets 'adm' in standalone (unchanged - there's no
        // separate ws_adm role in practice there, design doc §2.7) but 'ws_adm' in group mode:
        // this screen is workspace-scoped, so it must never be able to grant the instance-wide
        // 'adm' role (design doc §2.5 isolation).
        String adminRole = tricatch.oe.hub.config.AppHome.isGroupMode() ? "ws_adm" : "adm";
        if (!adminRole.equals(newRole) && !"usr".equals(newRole)) {
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
            if (target == null || currentUser == null || !target.getWsNo().equals(currentUser.getWsNo())) {
                ctx.status(404).result("User not found");
                return;
            }
            target.setRole(newRole);
            target.setUpdatedBy(currentUser.getUserNo());
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
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubUserMapper.class);
            var target = mapper.findByUserNo(userNo);
            if (target == null || currentUser == null || !target.getWsNo().equals(currentUser.getWsNo())) {
                ctx.status(404).result("User not found");
                return;
            }
            var newPassword = generatePassword();
            target.setPassword(PasswordUtil.hash(newPassword));
            target.setUpdatedBy(currentUser != null ? currentUser.getUserNo() : null);
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

    // Outstanding invite codes for the caller's own workspace (cloudGroupService design doc
    // §2.8) - group mode only in practice, since standalone never exposes the issuance UI, but
    // the endpoint itself has no mode check: any ws_adm (or standalone adm) can call it.
    public void apiListInvites(Context ctx) {
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var invites = session.getMapper(WsInviteMapper.class).findOutstandingByWsNo(currentUser.getWsNo(), LocalDateTime.now());
            var result = new ArrayList<Map<String, Object>>(invites.size());
            for (var i : invites) {
                var m = new LinkedHashMap<String, Object>();
                m.put("inviteCode", i.getInviteCode());
                m.put("createAt", i.getCreateAt() != null ? i.getCreateAt().format(FMT) : "");
                m.put("expiresAt", i.getExpiresAt() != null ? i.getExpiresAt().format(FMT) : "");
                result.add(m);
            }
            ctx.json(result);
        }
    }

    // 10-12 char code, confusion-chars excluded + SecureRandom - reuses generatePassword()'s
    // pattern per cloudGroupService design doc §2.8. 7-day expiry, same as AuthController's
    // login-lockout-style fixed constants (not admin-configurable).
    public void apiCreateInvite(Context ctx) {
        var currentUser = AuthController.currentUser(ctx);
        var now = LocalDateTime.now();
        var invite = new WsInvite();
        invite.setInviteCode(generatePassword());
        invite.setWsNo(currentUser.getWsNo());
        invite.setCreatedBy(currentUser.getUserNo());
        invite.setUpdatedBy(currentUser.getUserNo());
        invite.setCreateAt(now);
        invite.setUpdatedAt(now);
        invite.setExpiresAt(now.plusDays(7));
        try (var session = sqlSessionFactory.openSession(true)) {
            session.getMapper(WsInviteMapper.class).insert(invite);
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("inviteCode", invite.getInviteCode());
        m.put("createAt", invite.getCreateAt().format(FMT));
        m.put("expiresAt", invite.getExpiresAt().format(FMT));
        ctx.json(m).status(201);
    }
}
