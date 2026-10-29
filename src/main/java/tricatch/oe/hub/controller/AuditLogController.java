package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
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
                // "System" would be wrong here (0 is never used for a Tier-1 action - it's always
                // an authenticated admin) but the same departed-user fallback as elsewhere in the
                // app still applies: show the raw member number instead of a generic placeholder.
                m.put("actor", r.getActorUserId() != null ? r.getActorUserId() : "#" + r.getCreatedBy());
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
