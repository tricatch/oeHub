package tricatch.oe.hub.controller;

import io.javalin.http.Context;
import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hub.mapper.WsInviteMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What any member of a workspace (not only its admin) may do about new members: issue invite codes and
 * approve sign-ups. Workspace mode only. Everything else about people - roles, removal, rejecting,
 * team assignment - stays with the workspace admin ("/wsa/*").
 *
 * <p>Only the pieces that differ from the admin's screen live here: the page, and the list of the
 * codes the caller issued themselves. Creating an invite, listing the teams to pick from, listing
 * and approving the pending sign-ups are the AdminUserController handlers registered under
 * "/api/members/*": they already act on the caller's own workspace, so no role check is involved.
 * Approving is recorded in the audit log under the approving member.
 */
public class MemberController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SqlSessionFactory sqlSessionFactory;

    public MemberController(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public void showMembers(Context ctx) {
        var model = new HashMap<String, Object>();
        model.put("user", AuthController.currentUser(ctx));
        ctx.render("templates/oehub/members.pebble", model);
    }

    /** The outstanding invite codes the caller issued - not other members'. */
    public void apiListMyInvites(Context ctx) {
        var currentUser = AuthController.currentUser(ctx);
        try (var session = sqlSessionFactory.openSession()) {
            var invites = session.getMapper(WsInviteMapper.class)
                .findOutstandingByWsNoAndCreator(currentUser.getWsNo(), currentUser.getUserNo(), LocalDateTime.now());
            var result = new ArrayList<Map<String, Object>>(invites.size());
            for (var i : invites) {
                var m = new LinkedHashMap<String, Object>();
                m.put("inviteCode", i.getInviteCode());
                m.put("teamName", i.getTeamName());
                m.put("createAt", i.getCreateAt() != null ? i.getCreateAt().format(FMT) : "");
                m.put("expiresAt", i.getExpiresAt() != null ? i.getExpiresAt().format(FMT) : "");
                result.add(m);
            }
            ctx.json(result);
        }
    }
}
