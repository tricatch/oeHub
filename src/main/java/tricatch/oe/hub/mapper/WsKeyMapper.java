package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.WsKey;

public interface WsKeyMapper {

    @Select("SELECT ws_no, user_no, wrapped_ws_key, created_by, updated_by, create_at, updated_at FROM HUB_WS_KEY WHERE ws_no = #{wsNo} AND user_no = #{userNo}")
    WsKey findByWsNoAndUserNo(@Param("wsNo") Long wsNo, @Param("userNo") Long userNo);

    @Insert("INSERT INTO HUB_WS_KEY (ws_no, user_no, wrapped_ws_key, created_by, updated_by, create_at, updated_at) VALUES (#{wsNo}, #{userNo}, #{wrappedWsKey}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt})")
    void insert(WsKey wsKey);

    // Workspace-key rotation (e2eEncryption design doc §7): every remaining member's wrap is
    // replaced in place with a wrap of the freshly-rotated key - the row itself (and its PK) never
    // changes, only which key it wraps.
    @Update("UPDATE HUB_WS_KEY SET wrapped_ws_key = #{wrappedWsKey}, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE ws_no = #{wsNo} AND user_no = #{userNo}")
    void updateWrappedWsKey(@Param("wsNo") Long wsNo, @Param("userNo") Long userNo, @Param("wrappedWsKey") String wrappedWsKey, @Param("updatedBy") Long updatedBy, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    // Part of the account-deletion cleanup order (e2eEncryption design doc §3) - must run before
    // HUB_USR's own row delete, since this table FK-references user_no.
    @Delete("DELETE FROM HUB_WS_KEY WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);
}
