package tricatch.oe.hosts.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hosts.model.HostsProf;

import java.time.LocalDateTime;
import java.util.List;

public interface HostsProfMapper {

    // Shared by findByUserNo/findByHostsId: resolves a collabo/public reference row's real owner
    // (CASE WHEN ... ELSE -p.user_no, since a reference row's own user_no is stored negative) and
    // whichever of the row itself or its parent was updated more recently, for display.
    String JOIN_FOR_DISPLAY = """
        FROM HOSTS_PFILE h
        LEFT JOIN HOSTS_PFILE p ON p.hosts_id = h.parent_id
        LEFT JOIN HUB_USR u ON u.user_no = CASE WHEN h.parent_id IS NULL THEN h.user_no ELSE -p.user_no END
        LEFT JOIN HUB_USR e ON e.user_no = COALESCE(p.updated_by, h.updated_by)
        """;

    @Select("""
        SELECT h.hosts_id, h.user_no, h.hosts_profile,
               COALESCE(p.hosts_content, h.hosts_content) AS hosts_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, COALESCE(p.wrapped_content_key, h.wrapped_content_key) AS wrapped_content_key,
               h.wrapped_link_key,
               h.created_by, COALESCE(p.updated_by, h.updated_by) AS updated_by,
               COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id, e.user_id AS updated_by_user_id
        """ + JOIN_FOR_DISPLAY + """
        WHERE h.user_no = #{userNo}
        ORDER BY h.sort_order ASC, h.updated_at ASC
        """)
    List<HostsProf> findByUserNo(Long userNo);

    @Select("""
        SELECT h.hosts_id, h.user_no, h.hosts_profile,
               COALESCE(p.hosts_content, h.hosts_content) AS hosts_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, COALESCE(p.wrapped_content_key, h.wrapped_content_key) AS wrapped_content_key,
               h.link_content,
               h.wrapped_link_key,
               h.created_by, COALESCE(p.updated_by, h.updated_by) AS updated_by,
               COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id, e.user_id AS updated_by_user_id
        """ + JOIN_FOR_DISPLAY + """
        WHERE h.hosts_id = #{hostsId}
        """)
    HostsProf findByHostsId(String hostsId);

    // AND u.ws_no = :wsNo on both branches - a no-op filter in self-hosted (exactly one
    // workspace) but the actual search/discovery boundary in workspace mode (cloudGroupService
    // design doc §2.4). u already joins to the owning user either way, so this adds no new join.
    @Select("""
        SELECT h.hosts_id, h.user_no, h.hosts_profile, h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at, u.user_id
        FROM HOSTS_PFILE h
        JOIN HUB_USR u ON u.user_no = h.user_no
        WHERE h.visibility = 'public'
          AND h.parent_id IS NULL
          AND h.user_no != #{userNo}
          AND u.ws_no = #{wsNo}
          AND LOWER(h.hosts_profile) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
        UNION ALL
        SELECT h.hosts_id, h.user_no, h.hosts_profile, h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at, u.user_id
        FROM HOSTS_PFILE h
        JOIN HUB_USR u ON u.user_no = (-h.user_no)
        WHERE h.user_no < 0
          AND h.visibility = 'collabo'
          AND u.ws_no = #{wsNo}
          AND NOT EXISTS (
            SELECT 1 FROM HOSTS_PFILE r WHERE r.parent_id = h.hosts_id AND r.user_no = #{userNo}
          )
          AND LOWER(h.hosts_profile) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
        ORDER BY updated_at DESC
        LIMIT 100
        """)
    List<HostsProf> searchOthers(@Param("userNo") Long userNo, @Param("wsNo") Long wsNo, @Param("keyword") String keyword);

    @Insert("""
        INSERT INTO HOSTS_PFILE (hosts_id, user_no, hosts_profile, hosts_content, selected, sort_order,
                                 visibility, parent_id, wrapped_content_key, created_by, updated_by, create_at, updated_at)
        VALUES (#{hostsId}, #{userNo}, #{hostsProfile}, #{hostsContent}, #{selected}, #{sortOrder},
                #{visibility}, #{parentId}, #{wrappedContentKey}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt})
        """)
    void insert(HostsProf hostsProf);

    @Insert("""
        INSERT INTO HOSTS_PFILE (hosts_id, user_no, hosts_profile, hosts_content, selected, sort_order,
                                 visibility, parent_id, wrapped_content_key, created_by, updated_by, create_at, updated_at)
        SELECT #{hostsId}, #{userNo}, #{hostsProfile}, #{hostsContent}, #{selected},
               COALESCE(MAX(sort_order), -1) + 1,
               #{visibility}, #{parentId}, #{wrappedContentKey}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt}
        FROM HOSTS_PFILE
        WHERE user_no = #{userNo}
        """)
    void insertWithAutoSortOrder(HostsProf hostsProf);

    @Update("UPDATE HOSTS_PFILE SET hosts_content = #{hostsContent}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateContent(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("hostsContent") String hostsContent, @Param("updatedAt") LocalDateTime updatedAt);

    // Only needed right after create() (encrypting the server-generated plaintext example
    // content client-side, workspace mode - e2eEncryption design doc §1) - an ordinary content edit
    // never changes the key, so it goes through plain updateContent above instead.
    @Update("UPDATE HOSTS_PFILE SET hosts_content = #{hostsContent}, wrapped_content_key = #{wrappedContentKey}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateContentAndKey(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("hostsContent") String hostsContent, @Param("wrappedContentKey") String wrappedContentKey, @Param("updatedAt") LocalDateTime updatedAt);

    // user_no stays the original creator's — this row's owner is looked up as -user_no elsewhere
    // (findByHostsId/findByUserNo), and reassigning it on every collaborator edit both
    // mis-attributes ownership and can collide with the uq_hosts_pfile_user_profile unique
    // constraint. updated_by is the dedicated column for who last touched shared content.
    @Update("UPDATE HOSTS_PFILE SET hosts_content = #{hostsContent}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{parentId} AND user_no < 0")
    void updateContentByParentId(@Param("parentId") String parentId, @Param("userNo") Long userNo, @Param("hostsContent") String hostsContent, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET hosts_profile = #{hostsProfile}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateProfile(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("hostsProfile") String hostsProfile, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET selected = #{selected}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateSelected(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("selected") boolean selected, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET sort_order = #{sortOrder} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateSortOrder(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("sortOrder") int sortOrder);

    // wrappedContentKey is always sent (even in self-hosted/null) - the DEK itself never changes
    // on a private<->public flip (e2eEncryption design doc §7), only which KEK wraps it, so the
    // client always recomputes the new wrap and this just stores whatever it sends (null is a
    // no-op in self-hosted, where content/keys are never encrypted at all - design doc §1).
    @Update("UPDATE HOSTS_PFILE SET visibility = #{visibility}, wrapped_content_key = #{wrappedContentKey}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateVisibility(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("visibility") String visibility, @Param("wrappedContentKey") String wrappedContentKey, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET parent_id = #{parentId}, hosts_content = '', wrapped_content_key = NULL, visibility = 'collabo', updated_by = #{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void setAsCollaboRef(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("parentId") String parentId, @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM HOSTS_PFILE WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void delete(@Param("hostsId") String hostsId, @Param("userNo") Long userNo);

    @Delete("DELETE FROM HOSTS_PFILE WHERE hosts_id = #{hostsId}")
    void deleteByHostsId(String hostsId);

    @Delete("DELETE FROM HOSTS_PFILE WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);

    // 'public' rows survive account deletion (cloudGroupService design doc §2.5 orphan handling)
    // - fetched before the delete below so the caller can reassign them to wss first.
    @Select("SELECT hosts_id, user_no, hosts_profile, hosts_content, selected, sort_order, visibility, parent_id, created_by, updated_by, create_at, updated_at FROM HOSTS_PFILE WHERE user_no = #{userNo} AND visibility = 'public'")
    List<HostsProf> findPublicByUserNo(Long userNo);

    // created_by is left untouched - it's the immutable "who actually made this" audit trail
    // (CLAUDE.md's created_by/updated_by rule); only current ownership (user_no) and the
    // hosts_profile name (to dodge a uq_hosts_pfile_user_profile collision under the new owner)
    // move to wss.
    @Update("UPDATE HOSTS_PFILE SET user_no = #{newOwnerUserNo}, hosts_profile = #{newProfileName}, updated_by = #{newOwnerUserNo}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId}")
    void reassignOwner(@Param("hostsId") String hostsId, @Param("newOwnerUserNo") Long newOwnerUserNo, @Param("newProfileName") String newProfileName, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Delete("DELETE FROM HOSTS_PFILE WHERE user_no = -#{userNo} AND hosts_id NOT IN (SELECT DISTINCT parent_id FROM HOSTS_PFILE WHERE parent_id IS NOT NULL)")
    void deleteOrphanedParentsByCreator(Long userNo);

    @Select("SELECT hosts_id, user_no, hosts_profile, hosts_content, selected, sort_order, visibility, parent_id, updated_at FROM HOSTS_PFILE WHERE user_no = #{userNo} AND parent_id IS NOT NULL")
    List<HostsProf> findReferencesByUserNo(Long userNo);

    @Select("SELECT COUNT(*) FROM HOSTS_PFILE WHERE parent_id = #{parentId}")
    int countReferencesByParentId(@Param("parentId") String parentId);

    // Workspace-key rotation (e2eEncryption design doc §7, member-departure auto-rotation): every
    // row across the WHOLE workspace (not just the caller's own) whose DEK is wrapped by the
    // (about to be superseded) workspace key, so the rotating wsa's browser can unwrap each
    // with the old key and re-wrap with the new one. Reference rows (parent_id set) never carry
    // their own wrapped_content_key (setAsCollaboRef nulls it), so the NOT NULL filter alone
    // already excludes them - only parses/hosts_content are relevant, hostsId+wrappedContentKey
    // is otherwise enough for the rotation loop.
    @Select("""
        SELECT h.hosts_id, h.wrapped_content_key, h.wrapped_link_key
        FROM HOSTS_PFILE h
        JOIN HUB_USR u ON u.user_no = CASE WHEN h.user_no < 0 THEN -h.user_no ELSE h.user_no END
        WHERE u.ws_no = #{wsNo}
          AND h.wrapped_content_key IS NOT NULL
          AND h.visibility IN ('public', 'collabo')
        """)
    List<HostsProf> findEncryptedRowsByWsNo(Long wsNo);

    // Rotation-only re-wrap: the DEK itself never changes (design doc §7), so this deliberately
    // leaves updated_by/updated_at untouched - a key rotation isn't a content edit and shouldn't
    // look like one in the UI. The wsNo-scoped EXISTS subquery keeps a rotating wsa from being
    // able to touch another workspace's rows even if hostsId were guessed/forged.
    @Update("""
        UPDATE HOSTS_PFILE
        SET wrapped_content_key = #{wrappedContentKey}
        WHERE hosts_id = #{hostsId}
          AND wrapped_content_key IS NOT NULL
          AND hosts_id IN (
            SELECT h.hosts_id FROM HOSTS_PFILE h
            JOIN HUB_USR u ON u.user_no = CASE WHEN h.user_no < 0 THEN -h.user_no ELSE h.user_no END
            WHERE u.ws_no = #{wsNo}
          )
        """)
    void updateWrappedContentKeyForRotation(@Param("hostsId") String hostsId, @Param("wsNo") Long wsNo, @Param("wrappedContentKey") String wrappedContentKey);

    // Rotation-only re-wrap for the link's own DEK (e2eEncryption design doc §6 "living link"
    // redesign, §7 rotation) - same shape/scoping as updateWrappedContentKeyForRotation above, and
    // likewise never touches updated_by/updated_at. Guarded by wrapped_link_key IS NOT NULL so a
    // row with no live link is simply skipped (matches syncLinkContent/updateLinkContent's own
    // never-create-a-link-here guard).
    @Update("""
        UPDATE HOSTS_PFILE
        SET wrapped_link_key = #{wrappedLinkKey}
        WHERE hosts_id = #{hostsId}
          AND wrapped_link_key IS NOT NULL
          AND hosts_id IN (
            SELECT h.hosts_id FROM HOSTS_PFILE h
            JOIN HUB_USR u ON u.user_no = CASE WHEN h.user_no < 0 THEN -h.user_no ELSE h.user_no END
            WHERE u.ws_no = #{wsNo}
          )
        """)
    void updateWrappedLinkKeyForRotation(@Param("hostsId") String hostsId, @Param("wsNo") Long wsNo, @Param("wrappedLinkKey") String wrappedLinkKey);

    // Issues (both non-null) or revokes (both null) the fully-public, no-login link (e2eEncryption
    // design doc §6 "living link" redesign). Deliberately does NOT touch hosts_content/
    // wrapped_content_key - the link uses its own separate DEK, so the normal (workspace-key)
    // access path is completely unaffected by issuing or revoking a link. Scoped to 'public' rows
    // in the caller's own workspace - any member may issue/revoke, matching §8's "public is
    // jointly owned" model, the same reasoning already applied to updateContentByParentId for
    // collabo/public shared edits.
    @Update("""
        UPDATE HOSTS_PFILE
        SET link_content = #{linkContent}, wrapped_link_key = #{wrappedLinkKey}
        WHERE hosts_id = #{hostsId}
          AND visibility = 'public'
          AND hosts_id IN (
            SELECT h.hosts_id FROM HOSTS_PFILE h
            JOIN HUB_USR u ON u.user_no = h.user_no
            WHERE u.ws_no = #{wsNo}
          )
        """)
    void updateLinkContent(@Param("hostsId") String hostsId, @Param("wsNo") Long wsNo, @Param("linkContent") String linkContent, @Param("wrappedLinkKey") String wrappedLinkKey);

    // Refreshes an issued link's ciphertext on every content save (the "living link" fix for the
    // former snapshot trade-off, e2eEncryption design doc §6) - wrapped_link_key IS NOT NULL means
    // this can only ever refresh an EXISTING link, never mint a new one (that stays
    // updateLinkContent's job, called explicitly from the "Generate" button). Same wsNo scoping as
    // updateLinkContent.
    @Update("""
        UPDATE HOSTS_PFILE
        SET link_content = #{linkContent}
        WHERE hosts_id = #{hostsId}
          AND wrapped_link_key IS NOT NULL
          AND hosts_id IN (
            SELECT h.hosts_id FROM HOSTS_PFILE h
            JOIN HUB_USR u ON u.user_no = h.user_no
            WHERE u.ws_no = #{wsNo}
          )
        """)
    void syncLinkContent(@Param("hostsId") String hostsId, @Param("wsNo") Long wsNo, @Param("linkContent") String linkContent);

    // Unconditional auto-revoke used when a row's visibility leaves 'public' (e2eEncryption design
    // doc §6) - no visibility/workspace guard needed since the caller (HostsProfService) has
    // already verified ownership/scope for the visibility change itself.
    @Update("UPDATE HOSTS_PFILE SET link_content = NULL, wrapped_link_key = NULL WHERE hosts_id = #{hostsId}")
    void clearLink(@Param("hostsId") String hostsId);

    // Lightweight name-only projection (skips the CLOB content column) for bulk uniqueness
    // checks in HostsProfService.nextUniqueName - avoids one COUNT round trip per candidate
    // name when resolving collisions.
    @Select("SELECT hosts_profile FROM HOSTS_PFILE WHERE user_no = #{userNo}")
    List<String> findProfileNamesByUserNo(Long userNo);

}
