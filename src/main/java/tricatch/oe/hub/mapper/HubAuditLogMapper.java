package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import tricatch.oe.hub.model.HubAuditLog;

import java.time.LocalDateTime;
import java.util.List;

public interface HubAuditLogMapper {

    @Insert("INSERT INTO HUB_AUDIT_LOG (ws_no, action, target_type, target_id, detail, created_by, updated_by, create_at, updated_at) "
        + "VALUES (#{wsNo}, #{action}, #{targetType}, #{targetId}, #{detail}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt})")
    void insert(HubAuditLog log);

    // created_by resolved to a display user_id (CLAUDE.md rule) - a departed member's row still
    // shows via the fallback in AuditLogController (raw created_by number) since the LEFT JOIN
    // simply yields a null actorUserId for them.
    @Select("SELECT l.audit_id, l.ws_no, l.action, l.target_type, l.target_id, l.detail, "
        + "l.created_by, l.updated_by, l.create_at, l.updated_at, u.user_id AS actor_user_id "
        + "FROM HUB_AUDIT_LOG l LEFT JOIN HUB_USR u ON u.user_no = l.created_by "
        + "WHERE l.ws_no = #{wsNo} ORDER BY l.create_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<HubAuditLog> findByWsNo(@Param("wsNo") Long wsNo, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM HUB_AUDIT_LOG WHERE ws_no = #{wsNo}")
    long countByWsNo(Long wsNo);

    // Whole-workspace deletion takes the workspace's audit trail with it.
    @Delete("DELETE FROM HUB_AUDIT_LOG WHERE ws_no = #{wsNo}")
    void deleteByWsNo(Long wsNo);

    // Rows about one user as the target - the only rows whose detail may carry that user's ID string.
    @Select("SELECT audit_id, detail FROM HUB_AUDIT_LOG "
        + "WHERE ws_no = #{wsNo} AND target_type = 'user' AND target_id = #{targetId} AND detail IS NOT NULL")
    List<HubAuditLog> findUserTargetRows(@Param("wsNo") Long wsNo, @Param("targetId") String targetId);

    // Pseudonymization rewrite: the only place an audit row is ever modified.
    @Update("UPDATE HUB_AUDIT_LOG SET detail = #{detail}, updated_by = #{updatedBy}, updated_at = #{updatedAt} "
        + "WHERE audit_id = #{auditId}")
    void updateDetail(@Param("auditId") Long auditId, @Param("detail") String detail,
                      @Param("updatedBy") Long updatedBy, @Param("updatedAt") LocalDateTime updatedAt);
}
