package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.HubApiToken;

import java.time.LocalDateTime;
import java.util.List;

public interface HubApiTokenMapper {

    @Select("SELECT token_id, user_no, token_name, token_hash, created_by, updated_by, create_at, updated_at, last_used_at, expires_at "
        + "FROM HUB_API_TOKEN WHERE token_hash = #{tokenHash} AND (expires_at IS NULL OR expires_at > #{now})")
    HubApiToken findValidByHash(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Select("SELECT token_id, user_no, token_name, token_hash, created_by, updated_by, create_at, updated_at, last_used_at, expires_at "
        + "FROM HUB_API_TOKEN WHERE user_no = #{userNo} ORDER BY create_at DESC")
    List<HubApiToken> findByUserNo(Long userNo);

    @Insert("INSERT INTO HUB_API_TOKEN (token_id, user_no, token_name, token_hash, created_by, updated_by, create_at, updated_at, expires_at) "
        + "VALUES (#{tokenId}, #{userNo}, #{tokenName}, #{tokenHash}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt}, #{expiresAt})")
    void insert(HubApiToken token);

    @Update("UPDATE HUB_API_TOKEN SET last_used_at = #{now} WHERE token_id = #{tokenId}")
    void touchLastUsed(@Param("tokenId") String tokenId, @Param("now") LocalDateTime now);

    // Ownership-scoped: userNo must match the caller, so a user can only ever revoke their own tokens.
    @Delete("DELETE FROM HUB_API_TOKEN WHERE token_id = #{tokenId} AND user_no = #{userNo}")
    int deleteByIdAndUserNo(@Param("tokenId") String tokenId, @Param("userNo") Long userNo);

    @Delete("DELETE FROM HUB_API_TOKEN WHERE user_no = #{userNo}")
    void deleteAllByUserNo(Long userNo);
}
