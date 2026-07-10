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

public class AdminHostsUaController {

    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;

    public AdminHostsUaController(SqlSessionFactory sqlSessionFactory, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.objectMapper = objectMapper;
    }

    public void apiList(Context ctx) {
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUaMapper.class).findAll());
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
        var ua = new HostsUa();
        ua.setUaId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        ua.setUaName(uaName.trim());
        ua.setUaValue(uaValue.trim());
        ua.setCreateAt(now);
        ua.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            ua.setSortOrder(mapper.nextSortOrder());
            mapper.insert(ua);
        }
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUaMapper.class).findById(ua.getUaId()));
        }
        ctx.status(201);
    }

    public void apiUpdate(Context ctx) throws Exception {
        var uaId = ctx.pathParam("uaId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var ua = mapper.findById(uaId);
            if (ua == null) { ctx.status(404); return; }
            if (body.containsKey("uaName")) ua.setUaName(((String) body.get("uaName")).trim());
            if (body.containsKey("uaValue")) ua.setUaValue(((String) body.get("uaValue")).trim());
            if (body.containsKey("sortOrder")) ua.setSortOrder(((Number) body.get("sortOrder")).intValue());
            ua.setUpdatedAt(LocalDateTime.now());
            mapper.update(ua);
            ctx.json(ua);
        }
    }

    public void apiDelete(Context ctx) {
        var uaId = ctx.pathParam("uaId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUaMapper.class).deleteById(uaId);
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var ua = mapper.findById((String) ids.get(i));
                if (ua == null) continue;
                ua.setSortOrder(i);
                ua.setUpdatedAt(LocalDateTime.now());
                mapper.update(ua);
            }
        }
        ctx.status(204);
    }
}
