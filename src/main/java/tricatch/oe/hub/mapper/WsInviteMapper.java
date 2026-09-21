package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.WsInvite;

import java.time.LocalDateTime;
import java.util.List;

public interface WsInviteMapper {

    @Select("SELECT invite_code, ws_no, team_no, used, created_by, updated_by, create_at, updated_at, expires_at FROM HUB_WS_INVITE WHERE invite_code = #{inviteCode}")
    WsInvite findByCode(String inviteCode);

    // Outstanding (unused, unexpired) codes for one workspace's invite-management screen. LEFT
    // JOIN HUB_TEAM so the list can show which team (if any) each code pre-assigns (cloudGroupService
    // design doc §2.8/§2.9) without a second round trip per row.
    @Select("SELECT i.invite_code, i.ws_no, i.team_no, i.used, i.created_by, i.updated_by, i.create_at, i.updated_at, i.expires_at, t.team_name "
        + "FROM HUB_WS_INVITE i LEFT JOIN HUB_TEAM t ON t.team_no = i.team_no "
        + "WHERE i.ws_no = #{wsNo} AND i.used = FALSE AND i.expires_at > #{now} ORDER BY i.create_at DESC")
    List<WsInvite> findOutstandingByWsNo(@Param("wsNo") Long wsNo, @Param("now") LocalDateTime now);

    // The same list restricted to the codes one member issued - what a regular member sees on their
    // invite screen (a workspace admin sees the whole workspace through findOutstandingByWsNo).
    @Select("SELECT i.invite_code, i.ws_no, i.team_no, i.used, i.created_by, i.updated_by, i.create_at, i.updated_at, i.expires_at, t.team_name "
        + "FROM HUB_WS_INVITE i LEFT JOIN HUB_TEAM t ON t.team_no = i.team_no "
        + "WHERE i.ws_no = #{wsNo} AND i.created_by = #{createdBy} AND i.used = FALSE AND i.expires_at > #{now} ORDER BY i.create_at DESC")
    List<WsInvite> findOutstandingByWsNoAndCreator(@Param("wsNo") Long wsNo, @Param("createdBy") Long createdBy, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO HUB_WS_INVITE (invite_code, ws_no, team_no, created_by, updated_by, create_at, updated_at, expires_at) VALUES (#{inviteCode}, #{wsNo}, #{teamNo}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt}, #{expiresAt})")
    void insert(WsInvite invite);

    // Atomic consume: 0 affected rows means already used, expired, or never existed - the caller
    // treats all three the same way (generic "invalid code" error), see cloudGroupService design
    // doc §2.8. Also defends against two people racing to consume the same code.
    @Update("UPDATE HUB_WS_INVITE SET used = TRUE, updated_by = #{newUserNo}, updated_at = #{now} WHERE invite_code = #{inviteCode} AND used = FALSE AND expires_at > #{now}")
    int consume(@Param("inviteCode") String inviteCode, @Param("newUserNo") Long newUserNo, @Param("now") LocalDateTime now);
}
