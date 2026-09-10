package tricatch.oe.hub.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.model.HubUser;

import java.util.List;

public interface HubUserMapper {

    String COLS = "user_no, user_id, password, role, ws_no, token_version, "
        + "public_key, wrapped_private_key, wrapped_private_key_recovery, recovery_verifier, "
        + "created_by, updated_by, create_at, updated_at, last_login_at";

    @Select("SELECT " + COLS + " FROM HUB_USR WHERE user_no = #{userNo}")
    HubUser findByUserNo(Long userNo);

    @Select("SELECT " + COLS + " FROM HUB_USR WHERE user_id = #{userId}")
    HubUser findByUserId(String userId);

    // The non-login, workspace-owned account that orphaned public resources get reassigned to on
    // account deletion (cloudGroupService design doc §2.2/§2.5) - exactly one per workspace,
    // created alongside it (SetupController.processSetup, AuthController.processRegister).
    @Select("SELECT " + COLS + " FROM HUB_USR WHERE ws_no = #{wsNo} AND role = 'ws_system'")
    HubUser findWsSystemByWsNo(Long wsNo);

    // Excludes 'pending' (not yet approved, shown separately - see findAllPendingByWsNo) and
    // 'ws_system' (non-login workspace-owned account, not a manageable member) - see
    // cloudGroupService design doc §2.2/§2.8. Scoped to one workspace so a ws_adm never sees
    // another workspace's members - a no-op filter in standalone (exactly one workspace) but a
    // real tenant boundary once a second workspace exists (design doc §2.5 isolation).
    @Select("SELECT " + COLS + " FROM HUB_USR WHERE role NOT IN ('pending', 'ws_system') AND ws_no = #{wsNo} ORDER BY user_no")
    List<HubUser> findAll(Long wsNo);

    @Select("SELECT " + COLS + " FROM HUB_USR WHERE role NOT IN ('pending', 'ws_system') AND ws_no = #{wsNo} AND LOWER(user_id) LIKE LOWER(CONCAT('%', #{keyword}, '%')) ORDER BY user_no")
    List<HubUser> searchByUserId(@Param("wsNo") Long wsNo, @Param("keyword") String keyword);

    // Approval-pending members of one workspace - the "가입 승인 대기" list (cloudGroupService
    // design doc §2.8 UI). Scoped by ws_no even though standalone has exactly one workspace, so
    // the query is already correct once a second workspace can exist.
    @Select("SELECT " + COLS + " FROM HUB_USR WHERE role = 'pending' AND ws_no = #{wsNo} ORDER BY user_no")
    List<HubUser> findAllPendingByWsNo(Long wsNo);

    // public_key/wrapped_private_key/wrapped_private_key_recovery/recovery_verifier are all
    // nullable and NULL by default here - set on the HubUser object before calling insert() once
    // the signup flow generates a keypair client-side (e2eEncryption design doc §3).
    @Insert("INSERT INTO HUB_USR (user_id, password, role, ws_no, public_key, wrapped_private_key, wrapped_private_key_recovery, recovery_verifier, create_at, updated_at) "
        + "VALUES (#{userId}, #{password}, #{role}, #{wsNo}, #{publicKey}, #{wrappedPrivateKey}, #{wrappedPrivateKeyRecovery}, #{recoveryVerifier}, #{createAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "userNo")
    void insert(HubUser hubUser);

    // created_by/updated_by start out NULL on insert() (userNo isn't known yet) and are
    // self-referenced right after, in the same transaction - see CLAUDE.md's "Database Schema"
    // rule on created_by/updated_by and how self-registration fills them.
    @Update("UPDATE HUB_USR SET created_by = user_no, updated_by = user_no WHERE user_no = #{userNo}")
    void selfReferenceAudit(Long userNo);

    @Update("UPDATE HUB_USR SET role = #{role}, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void updateRole(HubUser hubUser);

    // Bumping token_version on every password change/reset invalidates any JWT issued before
    // this point (JwtService embeds the version at issue time and AuthController.resolveUser
    // rejects a mismatch) - otherwise a previously stolen "remember me" cookie (valid up to 365
    // days) would keep working even after the password is changed.
    @Update("UPDATE HUB_USR SET password = #{password}, token_version = token_version + 1, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void updatePassword(HubUser hubUser);

    // A self-service password change must re-wrap wrapped_private_key with the new password's
    // KEK in the same operation (e2eEncryption design doc §3 "복구키" - "로그인된 상태에서의 일반
    // 비밀번호 변경") - unlike updatePassword() above, used by an admin-initiated reset, where
    // nobody client-side knows the new (admin-chosen) password to re-wrap with; that path leaves
    // wrapped_private_key as-is and the affected user must go through recovery-key reset instead
    // (not yet wired in - flagged in aidoc/e2eEncryption/00-design.md §9 follow-up list).
    @Update("UPDATE HUB_USR SET password = #{password}, wrapped_private_key = #{wrappedPrivateKey}, token_version = token_version + 1, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void updatePasswordAndRewrapPrivateKey(HubUser hubUser);

    // Recovery-code reissue (e2eEncryption design doc §3) - doesn't touch password/private-key
    // wrap, just replaces which recovery code can unwrap the (unchanged) private key.
    // wrapped_private_key_recovery and recovery_verifier are a pair (design doc §3 "복구 플로우
    // 프로토콜") and must always be updated together - one without the other silently breaks
    // recovery (either an unverifiable wrap, or a verifier for a code the client no longer has).
    @Update("UPDATE HUB_USR SET wrapped_private_key_recovery = #{wrappedPrivateKeyRecovery}, recovery_verifier = #{recoveryVerifier}, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void updateWrappedPrivateKeyRecovery(HubUser hubUser);

    // Recovery flow's self-service reset (e2eEncryption design doc §3 "복구 플로우 프로토콜"):
    // replaces password, both recovery-related columns (new recovery code issued as part of
    // reset), and bumps token_version to invalidate any existing session/remember-me cookie -
    // same reasoning as updatePassword().
    @Update("UPDATE HUB_USR SET password = #{password}, wrapped_private_key = #{wrappedPrivateKey}, wrapped_private_key_recovery = #{wrappedPrivateKeyRecovery}, recovery_verifier = #{recoveryVerifier}, token_version = token_version + 1, updated_by = #{updatedBy}, updated_at = #{updatedAt} WHERE user_no = #{userNo}")
    void resetPasswordViaRecovery(HubUser hubUser);

    @Update("UPDATE HUB_USR SET last_login_at = #{lastLoginAt} WHERE user_no = #{userNo}")
    void updateLastLoginAt(HubUser hubUser);

    @Delete("DELETE FROM HUB_USR WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);
}
