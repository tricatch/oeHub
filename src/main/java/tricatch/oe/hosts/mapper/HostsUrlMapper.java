package tricatch.oe.hosts.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hosts.model.HostsUrl;

import java.util.List;

public interface HostsUrlMapper {

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, ws_no, created_by, updated_by, create_at, updated_at FROM HOSTS_URL WHERE ws_no = #{wsNo} AND user_no IS NULL ORDER BY sort_order, create_at")
    List<HostsUrl> findAllByWs(@Param("wsNo") Long wsNo);

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, ws_no, created_by, updated_by, create_at, updated_at FROM HOSTS_URL WHERE ws_no = #{wsNo} AND (user_no IS NULL OR user_no = #{userNo}) ORDER BY CASE WHEN user_no IS NOT NULL THEN 0 ELSE 1 END, sort_order, create_at")
    List<HostsUrl> findAllForUser(@Param("wsNo") Long wsNo, @Param("userNo") Long userNo);

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, ws_no, created_by, updated_by, create_at, updated_at FROM HOSTS_URL WHERE url_id = #{urlId}")
    HostsUrl findById(String urlId);

    // Scoped to one workspace's shared presets (user_no IS NULL) — for the workspace-admin preset
    // endpoints, so an admin editing a preset by id can never reach into another user's personal
    // preset (user_no IS NOT NULL) or into another workspace's presets.
    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, ws_no, created_by, updated_by, create_at, updated_at FROM HOSTS_URL WHERE url_id = #{urlId} AND ws_no = #{wsNo} AND user_no IS NULL")
    HostsUrl findByIdGlobal(@Param("urlId") String urlId, @Param("wsNo") Long wsNo);

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, ws_no, created_by, updated_by, create_at, updated_at FROM HOSTS_URL WHERE url_id = #{urlId} AND user_no = #{userNo}")
    HostsUrl findByIdAndUserNo(@Param("urlId") String urlId, @Param("userNo") Long userNo);

    @Insert("INSERT INTO HOSTS_URL (url_id, url_name, url_value, sort_order, user_no, ws_no, created_by, updated_by, create_at, updated_at) " +
            "VALUES (#{urlId}, #{urlName}, #{urlValue}, #{sortOrder}, #{userNo}, #{wsNo}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt})")
    void insert(HostsUrl hostsUrl);

    @Update("UPDATE HOSTS_URL SET url_name = #{urlName}, url_value = #{urlValue}, sort_order = #{sortOrder}, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE url_id = #{urlId}")
    int update(HostsUrl hostsUrl);

    @Delete("DELETE FROM HOSTS_URL WHERE url_id = #{urlId}")
    int deleteById(String urlId);

    // Scoped counterpart of deleteById — see findByIdGlobal.
    @Delete("DELETE FROM HOSTS_URL WHERE url_id = #{urlId} AND ws_no = #{wsNo} AND user_no IS NULL")
    int deleteByIdGlobal(@Param("urlId") String urlId, @Param("wsNo") Long wsNo);

    @Delete("DELETE FROM HOSTS_URL WHERE url_id = #{urlId} AND user_no = #{userNo}")
    int deleteByIdAndUserNo(@Param("urlId") String urlId, @Param("userNo") Long userNo);

    @Delete("DELETE FROM HOSTS_URL WHERE user_no = #{userNo}")
    void deleteAllByUserNo(Long userNo);

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM HOSTS_URL WHERE ws_no = #{wsNo} AND user_no IS NULL")
    int nextSortOrder(@Param("wsNo") Long wsNo);

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM HOSTS_URL WHERE user_no = #{userNo}")
    int nextSortOrderForUser(@Param("userNo") Long userNo);
}
