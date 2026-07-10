package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.HubUser;

import java.util.List;

public interface HubUserMapper {

    @Select("SELECT user_no, user_id, password, role, create_at, updated_at, last_login_at FROM HUB_USR WHERE user_no = #{userNo}")
    HubUser findByUserNo(Long userNo);

    @Select("SELECT user_no, user_id, password, role, create_at, updated_at, last_login_at FROM HUB_USR WHERE user_id = #{userId}")
    HubUser findByUserId(String userId);

    @Select("SELECT user_no, user_id, password, role, create_at, updated_at, last_login_at FROM HUB_USR ORDER BY user_no")
    List<HubUser> findAll();

    @Select("SELECT user_no, user_id, password, role, create_at, updated_at, last_login_at FROM HUB_USR WHERE LOWER(user_id) LIKE LOWER(CONCAT('%', #{keyword}, '%')) ORDER BY user_no")
    List<HubUser> searchByUserId(String keyword);

    @Insert("INSERT INTO HUB_USR (user_id, password, role, create_at, updated_at) VALUES (#{userId}, #{password}, #{role}, #{createAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "userNo")
    void insert(HubUser hubUser);

    @Update("UPDATE HUB_USR SET role = #{role}, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void updateRole(HubUser hubUser);

    @Update("UPDATE HUB_USR SET last_login_at = #{lastLoginAt} WHERE user_no = #{userNo}")
    void updateLastLoginAt(HubUser hubUser);

    @Delete("DELETE FROM HUB_USR WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);
}
