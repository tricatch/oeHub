package tricatch.oe.hosts.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hosts.model.HostsUa;

import java.util.List;

public interface HostsUaMapper {

    @Select("SELECT ua_id, ua_name, ua_value, sort_order, user_no, create_at, updated_at FROM HOSTS_UA WHERE user_no IS NULL ORDER BY sort_order, create_at")
    List<HostsUa> findAll();

    @Select("SELECT ua_id, ua_name, ua_value, sort_order, user_no, create_at, updated_at FROM HOSTS_UA WHERE user_no IS NULL OR user_no = #{userNo} ORDER BY CASE WHEN user_no IS NOT NULL THEN 0 ELSE 1 END, sort_order, create_at")
    List<HostsUa> findAllForUser(@Param("userNo") Long userNo);

    @Select("SELECT ua_id, ua_name, ua_value, sort_order, user_no, create_at, updated_at FROM HOSTS_UA WHERE ua_id = #{uaId}")
    HostsUa findById(String uaId);

    @Select("SELECT ua_id, ua_name, ua_value, sort_order, user_no, create_at, updated_at FROM HOSTS_UA WHERE ua_id = #{uaId} AND user_no = #{userNo}")
    HostsUa findByIdAndUserNo(@Param("uaId") String uaId, @Param("userNo") Long userNo);

    @Insert("INSERT INTO HOSTS_UA (ua_id, ua_name, ua_value, sort_order, user_no, create_at, updated_at) " +
            "VALUES (#{uaId}, #{uaName}, #{uaValue}, #{sortOrder}, #{userNo}, #{createAt}, #{updatedAt})")
    void insert(HostsUa hostsUa);

    @Update("UPDATE HOSTS_UA SET ua_name = #{uaName}, ua_value = #{uaValue}, sort_order = #{sortOrder}, updated_at = #{updatedAt} WHERE ua_id = #{uaId}")
    int update(HostsUa hostsUa);

    @Delete("DELETE FROM HOSTS_UA WHERE ua_id = #{uaId}")
    int deleteById(String uaId);

    @Delete("DELETE FROM HOSTS_UA WHERE ua_id = #{uaId} AND user_no = #{userNo}")
    int deleteByIdAndUserNo(@Param("uaId") String uaId, @Param("userNo") Long userNo);

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM HOSTS_UA WHERE user_no IS NULL")
    int nextSortOrder();

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM HOSTS_UA WHERE user_no = #{userNo}")
    int nextSortOrderForUser(@Param("userNo") Long userNo);
}
