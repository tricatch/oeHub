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

public class AdminHostsUrlController {

    private final SqlSessionFactory sqlSessionFactory;
    private final ObjectMapper objectMapper;

    public AdminHostsUrlController(SqlSessionFactory sqlSessionFactory, ObjectMapper objectMapper) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.objectMapper = objectMapper;
    }

    public void apiList(Context ctx) {
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUrlMapper.class).findAll());
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
        var url = new HostsUrl();
        url.setUrlId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        url.setUrlName(urlName.trim());
        url.setUrlValue(urlValue.trim());
        url.setCreateAt(now);
        url.setUpdatedAt(now);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            url.setSortOrder(mapper.nextSortOrder());
            mapper.insert(url);
        }
        try (var session = sqlSessionFactory.openSession()) {
            ctx.json(session.getMapper(HostsUrlMapper.class).findById(url.getUrlId()));
        }
        ctx.status(201);
    }

    public void apiUpdate(Context ctx) throws Exception {
        var urlId = ctx.pathParam("urlId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var url = mapper.findById(urlId);
            if (url == null) { ctx.status(404); return; }
            if (body.containsKey("urlName")) url.setUrlName(((String) body.get("urlName")).trim());
            if (body.containsKey("urlValue")) url.setUrlValue(((String) body.get("urlValue")).trim());
            if (body.containsKey("sortOrder")) url.setSortOrder(((Number) body.get("sortOrder")).intValue());
            url.setUpdatedAt(LocalDateTime.now());
            mapper.update(url);
            ctx.json(url);
        }
    }

    public void apiDelete(Context ctx) {
        var urlId = ctx.pathParam("urlId");
        try (var session = sqlSessionFactory.openSession(true)) {
            var deleted = session.getMapper(HostsUrlMapper.class).deleteById(urlId);
            if (deleted == 0) { ctx.status(404); return; }
        }
        ctx.status(204);
    }

    public void apiReorder(Context ctx) throws Exception {
        var ids = objectMapper.readValue(ctx.body(), List.class);
        try (var session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            for (int i = 0; i < ids.size(); i++) {
                var url = mapper.findById((String) ids.get(i));
                if (url == null) continue;
                url.setSortOrder(i);
                url.setUpdatedAt(LocalDateTime.now());
                mapper.update(url);
            }
        }
        ctx.status(204);
    }
}
