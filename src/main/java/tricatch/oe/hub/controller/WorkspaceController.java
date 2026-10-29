package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hub.config.AuditLogger;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.WorkspaceMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// Instance-admin workspace console (cloudGroupService design doc §2.5 "인스턴스 admin과의 격리",
// §3 item 2): the instance admin ('adm') can see the workspace list and toggle suspend/reactivate,
// and nothing else - never a workspace's member list, hosts profiles, or other tenant content.
// Workspace mode only - self-hosted has exactly one workspace, which is the instance itself, so there
// is nothing here to manage (design doc §2.7).
public class WorkspaceController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SqlSessionFactory sqlSessionFactory;

    public WorkspaceController(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public void showWorkspaces(Context ctx) {
        var model = new HashMap<String, Object>();
        model.put("user", AuthController.currentUser(ctx));
        ctx.render("templates/oehub/workspaces.pebble", model);
    }

    public void apiListWorkspaces(Context ctx) {
        try (var session = sqlSessionFactory.openSession()) {
            var wsMapper = session.getMapper(WorkspaceMapper.class);
            var userMapper = session.getMapper(HubUserMapper.class);
            var workspaces = wsMapper.findAll();
            var result = new ArrayList<Map<String, Object>>(workspaces.size());
            for (var w : workspaces) {
                var m = new LinkedHashMap<String, Object>();
                m.put("wsNo", w.getWsNo());
                m.put("wsName", w.getWsName());
                m.put("status", w.getStatus());
                // A count only, never names/details - the isolation boundary this screen must
                // respect (design doc §2.5).
                m.put("memberCount", userMapper.countMembersByWsNo(w.getWsNo()));
                m.put("createAt", w.getCreateAt() != null ? w.getCreateAt().format(FMT) : "");
                result.add(m);
            }
            ctx.json(result);
        }
    }

    @SuppressWarnings("unchecked")
    public void apiUpdateWorkspaceStatus(Context ctx) {
        Long wsNo;
        try { wsNo = Long.parseLong(ctx.pathParam("wsNo")); }
        catch (NumberFormatException e) { ctx.status(400).result("Invalid workspace ID"); return; }

        var body = ctx.bodyAsClass(Map.class);
        String status = (String) body.get("status");
        if (!"active".equals(status) && !"suspended".equals(status)) {
            ctx.status(400).result("Invalid status");
            return;
        }

        var currentUser = AuthController.currentUser(ctx);
        // The instance admin belongs to one workspace themselves; suspending that one bumps their
        // own token_version and blocks their next login, leaving nobody who can reactivate it.
        if (wsNo.equals(currentUser.getWsNo())) {
            ctx.status(400).result("Cannot change the status of your own workspace");
            return;
        }
        var now = LocalDateTime.now();
        try (var session = sqlSessionFactory.openSession()) {
            var wsMapper = session.getMapper(WorkspaceMapper.class);
            int updated = wsMapper.updateStatus(wsNo, status, currentUser.getUserNo(), now);
            if (updated == 0) {
                ctx.status(404).result("Workspace not found");
                return;
            }
            // Suspension must invalidate every already-issued session immediately, not just block
            // future logins (design doc §2.5 "워크스페이스 정지 처리") - reuses the same
            // token_version mismatch check AuthController.resolveUser already applies on every
            // request after a password change.
            if ("suspended".equals(status)) {
                session.getMapper(HubUserMapper.class).bumpTokenVersionByWsNo(wsNo);
            }
            // Logged under the TARGET workspace's ws_no, not the instance admin's own - so that
            // workspace's own wsa can see "who suspended us and when" in their own audit view,
            // even though the instance admin has no audit view of their own into this table
            // (isolation principle, design doc §2.5).
            AuditLogger.record(session, wsNo, "workspace.status_change", "workspace", String.valueOf(wsNo),
                AuditLogger.detail("status", status), currentUser.getUserNo());
            session.commit();
        }
        ctx.status(200).result("OK");
    }
}
