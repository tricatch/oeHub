package tricatch.oe.hosts.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hosts.model.HostsUrl;

import java.util.List;

public interface HostsUrlMapper {

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, create_at, updated_at FROM HOSTS_URL WHERE user_no IS NULL ORDER BY sort_order, create_at")
    List<HostsUrl> findAll();

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, create_at, updated_at FROM HOSTS_URL WHERE user_no IS NULL OR user_no = #{userNo} ORDER BY CASE WHEN user_no IS NOT NULL THEN 0 ELSE 1 END, sort_order, create_at")
    List<HostsUrl> findAllForUser(@Param("userNo") Long userNo);

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, create_at, updated_at FROM HOSTS_URL WHERE url_id = #{urlId}")
    HostsUrl findById(String urlId);

    @Select("SELECT url_id, url_name, url_value, sort_order, user_no, create_at, updated_at FROM HOSTS_URL WHERE url_id = #{urlId} AND user_no = #{userNo}")
    HostsUrl findByIdAndUserNo(@Param("urlId") String urlId, @Param("userNo") Long userNo);

    @Insert("INSERT INTO HOSTS_URL (url_id, url_name, url_value, sort_order, user_no, create_at, updated_at) " +
            "VALUES (#{urlId}, #{urlName}, #{urlValue}, #{sortOrder}, #{userNo}, #{createAt}, #{updatedAt})")
    void insert(HostsUrl hostsUrl);

    @Update("UPDATE HOSTS_URL SET url_name = #{urlName}, url_value = #{urlValue}, sort_order = #{sortOrder}, updated_at = #{updatedAt} WHERE url_id = #{urlId}")
    int update(HostsUrl hostsUrl);

    @Delete("DELETE FROM HOSTS_URL WHERE url_id = #{urlId}")
    int deleteById(String urlId);

    @Delete("DELETE FROM HOSTS_URL WHERE url_id = #{urlId} AND user_no = #{userNo}")
    int deleteByIdAndUserNo(@Param("urlId") String urlId, @Param("userNo") Long userNo);

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM HOSTS_URL WHERE user_no IS NULL")
    int nextSortOrder();

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM HOSTS_URL WHERE user_no = #{userNo}")
    int nextSortOrderForUser(@Param("userNo") Long userNo);
}
