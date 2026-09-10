package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.Workspace;

public interface WorkspaceMapper {

    @Select("SELECT ws_no, ws_name, status, created_by, updated_by, create_at, updated_at FROM HUB_WS WHERE ws_no = #{wsNo}")
    Workspace findByWsNo(Long wsNo);

    @Select("SELECT ws_no, ws_name, status, created_by, updated_by, create_at, updated_at FROM HUB_WS WHERE ws_name = #{wsName}")
    Workspace findByWsName(String wsName);

    // oe.mode=standalone has exactly one workspace by construction (cloudGroupService design
    // doc §2.7) - this is how standalone code looks it up without a group-mode workspace picker.
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
}
