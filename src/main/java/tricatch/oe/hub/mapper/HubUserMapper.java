package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.HubUser;

import java.util.List;

public interface HubUserMapper {

    @Select("SELECT user_no, user_id, password, role, token_version, create_at, updated_at, last_login_at FROM HUB_USR WHERE user_no = #{userNo}")
    HubUser findByUserNo(Long userNo);

    @Select("SELECT user_no, user_id, password, role, token_version, create_at, updated_at, last_login_at FROM HUB_USR WHERE user_id = #{userId}")
    HubUser findByUserId(String userId);

    @Select("SELECT user_no, user_id, password, role, token_version, create_at, updated_at, last_login_at FROM HUB_USR ORDER BY user_no")
    List<HubUser> findAll();

    @Select("SELECT user_no, user_id, password, role, token_version, create_at, updated_at, last_login_at FROM HUB_USR WHERE LOWER(user_id) LIKE LOWER(CONCAT('%', #{keyword}, '%')) ORDER BY user_no")
    List<HubUser> searchByUserId(String keyword);

    @Insert("INSERT INTO HUB_USR (user_id, password, role, create_at, updated_at) VALUES (#{userId}, #{password}, #{role}, #{createAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "userNo")
    void insert(HubUser hubUser);

    @Update("UPDATE HUB_USR SET role = #{role}, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void updateRole(HubUser hubUser);

    // Bumping token_version on every password change/reset invalidates any JWT issued before
    // this point (JwtService embeds the version at issue time and AuthController.resolveUser
    // rejects a mismatch) - otherwise a previously stolen "remember me" cookie (valid up to 365
    // days) would keep working even after the password is changed.
    @Update("UPDATE HUB_USR SET password = #{password}, token_version = token_version + 1, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void updatePassword(HubUser hubUser);

    @Update("UPDATE HUB_USR SET last_login_at = #{lastLoginAt} WHERE user_no = #{userNo}")
    void updateLastLoginAt(HubUser hubUser);

    @Delete("DELETE FROM HUB_USR WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);
}
