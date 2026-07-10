package tricatch.oe.hosts.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hosts.model.HostsConf;

public interface HostsConfMapper {

    @Select("SELECT user_no, conf_key, conf_val, updated_at FROM HOSTS_CONF WHERE user_no = #{userNo} AND conf_key = #{confKey}")
    HostsConf findByUserNoAndConfKey(@Param("userNo") Long userNo, @Param("confKey") String confKey);

    @Update("MERGE INTO HOSTS_CONF (user_no, conf_key, conf_val, updated_at) " +
            "KEY(user_no, conf_key) " +
            "VALUES (#{userNo}, #{confKey}, #{confVal}, #{updatedAt})")
    void upsert(HostsConf hostsConf);

    @Delete("DELETE FROM HOSTS_CONF WHERE user_no = #{userNo}")
    void deleteAllByUserNo(Long userNo);
}
