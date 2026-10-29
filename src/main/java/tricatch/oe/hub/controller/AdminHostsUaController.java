package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.model.HostsUa;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared User-Agent presets of the acting admin's OWN workspace (user_no IS NULL rows of that ws_no).
 * Registered under both "/api/adm/hosts/..." (instance admin - in workspace mode that is the SYSTEM
 * workspace's admin) and "/api/wsa/hosts/..." (workspace admin), plus the setup wizard; every handler
 * takes the workspace from the logged-in user, so no caller can reach another workspace's presets.
 */
public class AdminHostsUaController {

    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;

    public AdminHostsUaController(SqlSessionFactory sqlSessionFactory, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.objectMapper = objectMapper;
    }

    public void apiList(Context ctx) {
        var wsNo = AuthController.currentUser(ctx).getWsNo();
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUaMapper.class).findAllByWs(wsNo));
        }
    }

    public void apiCreate(Context ctx) throws Exception {
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var uaName = ((String) body.get("uaName"));
        var uaValue = ((String) body.get("uaValue"));
        if (uaName == null || uaName.isBlank() || uaValue == null || uaValue.isBlank()) {
            ctx.status(400).result("uaName and uaValue are required");
            return;
        }
        var now = LocalDateTime.now();
        var actor = AuthController.currentUser(ctx);
        var actorUserNo = actor.getUserNo();
        var ua = new HostsUa();
        ua.setWsNo(actor.getWsNo());
        ua.setUaId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        ua.setUaName(uaName.trim());
        ua.setUaValue(uaValue.trim());
        ua.setCreatedBy(actorUserNo);
        ua.setUpdatedBy(actorUserNo);
        ua.setCreateAt(now);
        ua.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            ua.setSortOrder(mapper.nextSortOrder(ua.getWsNo()));
            mapper.insert(ua);
        }
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUaMapper.class).findById(ua.getUaId()));
        }
        ctx.status(201);
    }

    public void apiUpdate(Context ctx) throws Exception {
        var uaId = ctx.pathParam("uaId");
        var wsNo = AuthController.currentUser(ctx).getWsNo();
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var ua = mapper.findByIdGlobal(uaId, wsNo);
            if (ua == null) { ctx.status(404); return; }
            if (body.get("uaName") instanceof String s) ua.setUaName(s.trim());
            if (body.get("uaValue") instanceof String s) ua.setUaValue(s.trim());
            if (body.get("sortOrder") instanceof Number n) ua.setSortOrder(n.intValue());
            ua.setUpdatedBy(AuthController.currentUser(ctx).getUserNo());
            ua.setUpdatedAt(LocalDateTime.now());
            mapper.update(ua);
            ctx.json(ua);
        }
    }

    public void apiDelete(Context ctx) {
        var uaId = ctx.pathParam("uaId");
        var wsNo = AuthController.currentUser(ctx).getWsNo();
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUaMapper.class).deleteByIdGlobal(uaId, wsNo);
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        var actorUser = AuthController.currentUser(ctx);
        var actorUserNo = actorUser.getUserNo();
        var wsNo = actorUser.getWsNo();
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var ua = mapper.findByIdGlobal((String) ids.get(i), wsNo);
                if (ua == null) continue;
                ua.setSortOrder(i);
                ua.setUpdatedBy(actorUserNo);
                ua.setUpdatedAt(LocalDateTime.now());
                mapper.update(ua);
            }
        }
        ctx.status(204);
    }
}
