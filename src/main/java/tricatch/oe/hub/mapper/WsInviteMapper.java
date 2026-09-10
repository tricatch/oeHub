package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.WsInvite;

import java.time.LocalDateTime;
import java.util.List;

public interface WsInviteMapper {

    @Select("SELECT invite_code, ws_no, used, created_by, updated_by, create_at, updated_at, expires_at FROM HUB_WS_INVITE WHERE invite_code = #{inviteCode}")
    WsInvite findByCode(String inviteCode);

    // Outstanding (unused, unexpired) codes for one workspace's invite-management screen.
    @Select("SELECT invite_code, ws_no, used, created_by, updated_by, create_at, updated_at, expires_at FROM HUB_WS_INVITE WHERE ws_no = #{wsNo} AND used = FALSE AND expires_at > #{now} ORDER BY create_at DESC")
    List<WsInvite> findOutstandingByWsNo(@Param("wsNo") Long wsNo, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO HUB_WS_INVITE (invite_code, ws_no, created_by, updated_by, create_at, updated_at, expires_at) VALUES (#{inviteCode}, #{wsNo}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt}, #{expiresAt})")
    void insert(WsInvite invite);

    // Atomic consume: 0 affected rows means already used, expired, or never existed - the caller
    // treats all three the same way (generic "invalid code" error), see cloudGroupService design
    // doc §2.8. Also defends against two people racing to consume the same code.
    @Update("UPDATE HUB_WS_INVITE SET used = TRUE, updated_by = #{newUserNo}, updated_at = #{now} WHERE invite_code = #{inviteCode} AND used = FALSE AND expires_at > #{now}")
    int consume(@Param("inviteCode") String inviteCode, @Param("newUserNo") Long newUserNo, @Param("now") LocalDateTime now);
}
