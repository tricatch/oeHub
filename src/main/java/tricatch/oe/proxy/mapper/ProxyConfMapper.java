package tricatch.oe.proxy.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.proxy.model.ProxyConf;

public interface ProxyConfMapper {

    @Select("SELECT user_no, conf_key, conf_val, updated_at FROM PROXY_CONF WHERE user_no IS NULL AND conf_key = #{confKey}")
    ProxyConf findGlobal(String confKey);

    @Select("SELECT user_no, conf_key, conf_val, updated_at FROM PROXY_CONF WHERE user_no = #{userNo} AND conf_key = #{confKey}")
    ProxyConf findByUserNoAndConfKey(@Param("userNo") Long userNo, @Param("confKey") String confKey);

    @Insert("INSERT INTO PROXY_CONF (user_no, conf_key, conf_val, updated_at) VALUES (#{userNo}, #{confKey}, #{confVal}, #{updatedAt})")
    void insert(ProxyConf conf);

    @Update("UPDATE PROXY_CONF SET conf_val = #{confVal}, updated_at = #{updatedAt} WHERE conf_key = #{confKey} AND user_no IS NOT DISTINCT FROM #{userNo}")
    void update(ProxyConf conf);

    @Delete("DELETE FROM PROXY_CONF WHERE user_no = #{userNo}")
    void deleteAllByUserNo(Long userNo);
}
