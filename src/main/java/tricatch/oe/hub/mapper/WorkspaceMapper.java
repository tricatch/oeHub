package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.Workspace;

import java.time.LocalDateTime;
import java.util.List;

public interface WorkspaceMapper {

    @Select("SELECT ws_no, ws_name, status, created_by, updated_by, create_at, updated_at FROM HUB_WS WHERE ws_no = #{wsNo}")
    Workspace findByWsNo(Long wsNo);

    // Instance-admin workspace console (cloudGroupService design doc §2.5 "인스턴스 admin과의 격리")
    // - lists every workspace instance-wide, but only the columns already exposed here (name,
    // status, audit) - never a workspace's member list/hosts data, which stays off-limits to the
    // instance admin by design.
    @Select("SELECT ws_no, ws_name, status, created_by, updated_by, create_at, updated_at FROM HUB_WS ORDER BY create_at")
    List<Workspace> findAll();

    @Select("SELECT ws_no, ws_name, status, created_by, updated_by, create_at, updated_at FROM HUB_WS WHERE ws_name = #{wsName}")
    Workspace findByWsName(String wsName);

    // oe.mode=self-hosted has exactly one workspace by construction (cloudGroupService design
    // doc §2.7) - this is how self-hosted code looks it up without a workspace-mode workspace picker.
    @Select("SELECT ws_no, ws_name, status, created_by, updated_by, create_at, updated_at FROM HUB_WS ORDER BY ws_no LIMIT 1")
    Workspace findFirst();

    @Insert("INSERT INTO HUB_WS (ws_name, status, create_at, updated_at) VALUES (#{wsName}, #{status}, #{createAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "wsNo")
    void insert(Workspace workspace);

    // created_by/updated_by are NULL at insert() (the founding user's user_no isn't known until
    // after HUB_USR insert, in the same transaction) and are backfilled right after - see
    // cloudGroupService design doc §2.1's insert-order note.
    @Update("UPDATE HUB_WS SET created_by = #{userNo}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE ws_no = #{wsNo}")
    void backfillAudit(@Param("wsNo") Long wsNo, @Param("userNo") Long userNo, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Update("UPDATE HUB_WS SET status = #{status}, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE ws_no = #{wsNo}")
    int updateStatus(@Param("wsNo") Long wsNo, @Param("status") String status,
                      @Param("updatedBy") Long updatedBy, @Param("updatedAt") LocalDateTime updatedAt);
}
