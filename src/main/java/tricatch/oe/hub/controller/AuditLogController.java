package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hub.config.AppHome;
import tricatch.oe.hub.config.Role;
import tricatch.oe.hub.mapper.HubAuditLogMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// Tier-1 (security/access-control) audit trail viewer. Always scoped to the caller's own
// ws_no - both a self-hosted 'adm' and a workspace-mode 'wsa' only ever see their own single
// workspace's rows, which is also exactly what the "/wsa/*" route gate already limits who
// can reach this controller to (isWorkspaceAdmin excludes a workspace-mode instance 'adm' - see
// Role.isWorkspaceAdmin), so no separate "see everything" branch is needed here.
public class AuditLogController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int PAGE_SIZE = 50;

    private final SqlSessionFactory sqlSessionFactory;

    public AuditLogController(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public void showAuditLog(Context ctx) {
        var model = new HashMap<String, Object>();
        model.put("user", AuthController.currentUser(ctx));
        ctx.render("templates/oehub/audit-log.pebble", model);
    }

    public void apiListAuditLog(Context ctx) {
        var currentUser = AuthController.currentUser(ctx);
        int page = parsePage(ctx.queryParam("page"));

        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HubAuditLogMapper.class);
            var rows = mapper.findByWsNo(currentUser.getWsNo(), PAGE_SIZE, page * PAGE_SIZE);
            var total = mapper.countByWsNo(currentUser.getWsNo());

            var result = new ArrayList<Map<String, Object>>(rows.size());
            for (var r : rows) {
                var m = new LinkedHashMap<String, Object>();
                m.put("action", r.getAction());
                m.put("targetType", r.getTargetType());
                m.put("targetId", r.getTargetId());
                m.put("detail", r.getDetail());
                // Shown as "System" (actorSystem=true, label resolved client-side): the reserved 0
                // sentinel, and in workspace mode an instance admin acting on this workspace from
                // outside it (e.g. workspace.status_change) - the tenant sees the operator, not a
                // personal account ID. Otherwise the departed-user fallback applies: show the raw
                // member number instead of a generic placeholder.
                boolean system = Long.valueOf(0L).equals(r.getCreatedBy())
                    || (AppHome.isWorkspaceMode() && Role.ADM.equals(r.getActorRole()));
                m.put("actorSystem", system);
                m.put("actor", system ? null
                    : r.getActorUserId() != null ? r.getActorUserId() : "#" + r.getCreatedBy());
                m.put("createAt", r.getCreateAt() != null ? r.getCreateAt().format(FMT) : "");
                result.add(m);
            }

            var body = new LinkedHashMap<String, Object>();
            body.put("rows", result);
            body.put("total", total);
            body.put("page", page);
            body.put("pageSize", PAGE_SIZE);
            ctx.json(body);
        }
    }

    private int parsePage(String raw) {
        if (raw == null) return 0;
        try {
            var page = Integer.parseInt(raw.trim());
            return Math.max(page, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
