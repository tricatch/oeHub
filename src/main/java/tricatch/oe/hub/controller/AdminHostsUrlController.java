package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.hosts.model.HostsUrl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared Open URL presets of the acting admin's OWN workspace (user_no IS NULL rows of that ws_no).
 * Registered under both "/api/adm/hosts/..." (instance admin - in workspace mode that is the SYSTEM
 * workspace's admin) and "/api/wsa/hosts/..." (workspace admin), plus the setup wizard; every handler
 * takes the workspace from the logged-in user, so no caller can reach another workspace's presets.
 */
public class AdminHostsUrlController {

    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;

    public AdminHostsUrlController(SqlSessionFactory sqlSessionFactory, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.objectMapper = objectMapper;
    }

    public void apiList(Context ctx) {
        var wsNo = AuthController.currentUser(ctx).getWsNo();
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUrlMapper.class).findAllByWs(wsNo));
        }
    }

    public void apiCreate(Context ctx) throws Exception {
        var body = objectMapper.readValue(ctx.body(), Map.class);
        var urlName = ((String) body.get("urlName"));
        var urlValue = ((String) body.get("urlValue"));
        if (urlName == null || urlName.isBlank() || urlValue == null || urlValue.isBlank()) {
            ctx.status(400).result("urlName and urlValue are required");
            return;
        }
        var now = LocalDateTime.now();
        var actor = AuthController.currentUser(ctx);
        var actorUserNo = actor.getUserNo();
        var url = new HostsUrl();
        url.setWsNo(actor.getWsNo());
        url.setUrlId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        url.setUrlName(urlName.trim());
        url.setUrlValue(urlValue.trim());
        url.setCreatedBy(actorUserNo);
        url.setUpdatedBy(actorUserNo);
        url.setCreateAt(now);
        url.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            url.setSortOrder(mapper.nextSortOrder(url.getWsNo()));
            mapper.insert(url);
        }
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUrlMapper.class).findById(url.getUrlId()));
        }
        ctx.status(201);
    }

    public void apiUpdate(Context ctx) throws Exception {
        var urlId = ctx.pathParam("urlId");
        var wsNo = AuthController.currentUser(ctx).getWsNo();
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var url = mapper.findByIdGlobal(urlId, wsNo);
            if (url == null) { ctx.status(404); return; }
            if (body.get("urlName") instanceof String s) url.setUrlName(s.trim());
            if (body.get("urlValue") instanceof String s) url.setUrlValue(s.trim());
            if (body.get("sortOrder") instanceof Number n) url.setSortOrder(n.intValue());
            url.setUpdatedBy(AuthController.currentUser(ctx).getUserNo());
            url.setUpdatedAt(LocalDateTime.now());
            mapper.update(url);
            ctx.json(url);
        }
    }

    public void apiDelete(Context ctx) {
        var urlId = ctx.pathParam("urlId");
        var wsNo = AuthController.currentUser(ctx).getWsNo();
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUrlMapper.class).deleteByIdGlobal(urlId, wsNo);
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
            var mapper = session.getMapper(HostsUrlMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var url = mapper.findByIdGlobal((String) ids.get(i), wsNo);
                if (url == null) continue;
                url.setSortOrder(i);
                url.setUpdatedBy(actorUserNo);
                url.setUpdatedAt(LocalDateTime.now());
                mapper.update(url);
            }
        }
        ctx.status(204);
    }
}
