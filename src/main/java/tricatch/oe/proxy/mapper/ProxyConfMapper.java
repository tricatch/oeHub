package tricatch.oe.proxy.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.proxy.model.ProxyConf;

public interface ProxyConfMapper {

    @Select("SELECT user_no, conf_key, conf_val, created_by, updated_by, create_at, updated_at FROM PROXY_CONF WHERE user_no IS NULL AND conf_key = #{confKey}")
    ProxyConf findGlobal(String confKey);

    @Select("SELECT user_no, conf_key, conf_val, created_by, updated_by, create_at, updated_at FROM PROXY_CONF WHERE user_no = #{userNo} AND conf_key = #{confKey}")
    ProxyConf findByUserNoAndConfKey(@Param("userNo") Long userNo, @Param("confKey") String confKey);

    // user_no is nullable here (global vs. per-user config), and H2's shorthand
    // "MERGE INTO t (cols) KEY(cols) VALUES(...)" never matches an existing row when a key
    // column is NULL (verified: it always inserts, producing duplicate global rows on repeated
    // upserts) - the ANSI MERGE ... USING ... ON form below with IS NOT DISTINCT FROM is required
    // for a NULL-safe match, unlike HostsConfMapper/HubConfMapper's upsert where the key is
    // always non-null. WHEN MATCHED only touches updated_by/updated_at so created_by/create_at
    // (the original creator/creation time) survive repeat saves of the same key.
    @Update("""
        MERGE INTO PROXY_CONF AS t
        USING (VALUES (#{userNo}, #{confKey}, #{confVal}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt}))
              AS s(user_no, conf_key, conf_val, created_by, updated_by, create_at, updated_at)
        ON t.conf_key = s.conf_key AND t.user_no IS NOT DISTINCT FROM s.user_no
        WHEN MATCHED THEN UPDATE SET t.conf_val = s.conf_val, t.updated_by = s.updated_by, t.updated_at = s.updated_at
        WHEN NOT MATCHED THEN INSERT (user_no, conf_key, conf_val, created_by, updated_by, create_at, updated_at)
                            VALUES (s.user_no, s.conf_key, s.conf_val, s.created_by, s.updated_by, s.create_at, s.updated_at)
        """)
    void upsert(ProxyConf conf);

    @Delete("DELETE FROM PROXY_CONF WHERE user_no = #{userNo}")
    void deleteAllByUserNo(Long userNo);
}
