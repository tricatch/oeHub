package tricatch.oe.hub.folder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hub.controller.AuthController;
import tricatch.oe.hub.util.NameTakenException;

import java.util.List;
import java.util.Map;

/**
 * Folder API for one sidebar list ({@code /api/hosts/folders/**} or
 * {@code /api/proxy/vhosts/folders/**}, plus the whole tree's {@code .../tree-order}). Any
 * logged-in account, on its own folders and rows only.
 */
public class ListFolderController {

    private final ListFolderService service;
    private final ObjectMapper objectMapper;

    public ListFolderController(SqlSessionFactory sqlSessionFactory, FolderKind kind, ObjectMapper objectMapper) {
        this.service = new ListFolderService(sqlSessionFactory, kind);
        this.objectMapper = objectMapper;
    }

    public void apiList(Context ctx) {
        ctx.json(service.list(AuthController.currentUser(ctx).getUserNo()));
    }

    public void apiCreate(Context ctx) throws Exception {
        var userNo = AuthController.currentUser(ctx).getUserNo();
        String name = null;
        if (ctx.body() != null && !ctx.body().isBlank()) {
            var body = objectMapper.readValue(ctx.body(), Map.class);
            if (body.get("name") instanceof String s) name = s;
        }
        try {
            ctx.status(201).json(service.create(userNo, name));
        } catch (IllegalArgumentException e) {
            ctx.status(400);
        } catch (NameTakenException e) {
            ctx.status(409);
        }
    }

    // Body: {"name": "..."} and/or {"collapsed": true|false}.
    public void apiUpdate(Context ctx) throws Exception {
        var userNo = AuthController.currentUser(ctx).getUserNo();
        var folderId = ctx.pathParam("folderId");
        var body = objectMapper.readValue(ctx.body(), Map.class);
        ListFolder updated = null;
        try {
            if (body.get("name") instanceof String name) {
                updated = service.rename(userNo, folderId, name);
                if (updated == null) { ctx.status(404); return; }
            }
            if (body.get("collapsed") instanceof Boolean collapsed) {
                updated = service.setCollapsed(userNo, folderId, collapsed);
                if (updated == null) { ctx.status(404); return; }
            }
        } catch (IllegalArgumentException e) {
            ctx.status(400); return;
        } catch (NameTakenException e) {
            ctx.status(409); return;
        }
        if (updated == null) { ctx.status(400); return; }
        ctx.json(updated);
    }

    public void apiDelete(Context ctx) {
        var userNo = AuthController.currentUser(ctx).getUserNo();
        ctx.status(service.delete(userNo, ctx.pathParam("folderId")) ? 204 : 404);
    }

    // Body: the whole tree in display order, e.g.
    // [{"type":"item","id":"..","folderId":null}, {"type":"folder","id":".."},
    //  {"type":"item","id":"..","folderId":".."}, ...]
    public void apiSaveTreeOrder(Context ctx) throws Exception {
        var userNo = AuthController.currentUser(ctx).getUserNo();
        var raw = objectMapper.readValue(ctx.body(), List.class);
        var entries = new java.util.ArrayList<ListFolderService.TreeEntry>();
        for (var o : raw) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("id") instanceof String id)) { ctx.status(400); return; }
            var folder = "folder".equals(m.get("type"));
            var folderId = !folder && m.get("folderId") instanceof String s && !s.isBlank() ? s : null;
            entries.add(new ListFolderService.TreeEntry(folder, id, folderId));
        }
        try {
            service.saveTreeOrder(userNo, entries);
        } catch (IllegalArgumentException e) {
            ctx.status(400); return;
        }
        ctx.status(204);
    }
}
