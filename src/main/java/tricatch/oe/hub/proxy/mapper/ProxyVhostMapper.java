package tricatch.oe.hub.proxy.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hub.proxy.model.ProxyVhost;

import java.time.LocalDateTime;
import java.util.List;

public interface ProxyVhostMapper {

    // The sidebar's order, which is also the merge order of the selected vhosts: folders and the
    // items outside any folder share one sort_order sequence (interleaved), and a folder's items
    // follow it in their own sort_order. Needs
    // "LEFT JOIN PROXY_FOLDER f ON f.folder_id = h.folder_id" - f is the row's own folder, never
    // the parent's (folders are per user).
    String LIST_ORDER = """
        ORDER BY COALESCE(f.sort_order, h.sort_order) ASC, CASE WHEN f.folder_id IS NULL THEN 0 ELSE 1 END,
                 f.create_at ASC, f.folder_id ASC, h.sort_order ASC, h.updated_at ASC
        """;

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.share_scope,
               h.parent_id, h.folder_id, f.folder_name,
               h.created_by, COALESCE(p.updated_by, h.updated_by) AS updated_by,
               COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id, e.user_id AS updated_by_user_id
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        LEFT JOIN PROXY_FOLDER f ON f.folder_id = h.folder_id
        LEFT JOIN HUB_USR u ON u.user_no = CASE WHEN h.parent_id IS NULL THEN h.user_no ELSE -p.user_no END
        LEFT JOIN HUB_USR e ON e.user_no = COALESCE(p.updated_by, h.updated_by)
        WHERE h.user_no = #{userNo}
        """ + LIST_ORDER)
    List<ProxyVhost> findByUserNo(Long userNo);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.share_scope,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        LEFT JOIN PROXY_FOLDER f ON f.folder_id = h.folder_id
        WHERE h.user_no = #{userNo} AND h.selected = TRUE
        """ + LIST_ORDER + """
        LIMIT 1
        """)
    ProxyVhost findSelectedByUserNo(Long userNo);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.share_scope,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        LEFT JOIN PROXY_FOLDER f ON f.folder_id = h.folder_id
        WHERE h.user_no = #{userNo} AND h.selected = TRUE
        """ + LIST_ORDER)
    List<ProxyVhost> findAllSelectedByUserNo(Long userNo);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.share_scope,
               h.parent_id, h.folder_id, f.folder_name,
               h.created_by, COALESCE(p.updated_by, h.updated_by) AS updated_by,
               COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id, e.user_id AS updated_by_user_id
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        LEFT JOIN PROXY_FOLDER f ON f.folder_id = h.folder_id
        LEFT JOIN HUB_USR u ON u.user_no = CASE WHEN h.parent_id IS NULL THEN h.user_no ELSE -p.user_no END
        LEFT JOIN HUB_USR e ON e.user_no = COALESCE(p.updated_by, h.updated_by)
        WHERE h.vhost_id = #{vhostId}
        """)
    ProxyVhost findByVhostId(String vhostId);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile, h.selected, h.sort_order, h.share_scope,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        WHERE h.share_scope = 'workspace'
          AND h.parent_id IS NULL
          AND h.user_no != #{userNo}
          AND h.user_no > 0
          AND LOWER(h.vhost_profile) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
        UNION ALL
        SELECT h.vhost_id, h.user_no, h.vhost_profile, h.selected, h.sort_order, h.share_scope,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        WHERE h.user_no < 0
          AND h.share_scope = 'collabo'
          AND NOT EXISTS (
            SELECT 1 FROM PROXY_VHOST r WHERE r.parent_id = h.vhost_id AND r.user_no = #{userNo}
          )
          AND LOWER(h.vhost_profile) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
        ORDER BY updated_at DESC
        LIMIT 100
        """)
    List<ProxyVhost> searchOthers(@Param("userNo") Long userNo, @Param("keyword") String keyword);

    @Insert("""
        INSERT INTO PROXY_VHOST (vhost_id, user_no, vhost_profile, vhost_content, selected, sort_order,
                                 share_scope, parent_id, folder_id, created_by, updated_by, create_at, updated_at)
        VALUES (#{vhostId}, #{userNo}, #{vhostProfile}, #{vhostContent}, #{selected}, #{sortOrder},
                #{shareScope}, #{parentId}, #{folderId}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt})
        """)
    void insert(ProxyVhost vhost);

    // A new row goes after everything the user has - the sort_order sequence is shared with the
    // folders (PROXY_FOLDER), which the sidebar interleaves with the rows outside them.
    @Insert("""
        INSERT INTO PROXY_VHOST (vhost_id, user_no, vhost_profile, vhost_content, selected, sort_order,
                                 share_scope, parent_id, folder_id, created_by, updated_by, create_at, updated_at)
        SELECT #{vhostId}, #{userNo}, #{vhostProfile}, #{vhostContent}, #{selected},
               GREATEST(COALESCE(MAX(sort_order), -1),
                        COALESCE((SELECT MAX(sort_order) FROM PROXY_FOLDER WHERE user_no = #{userNo}), -1)) + 1,
               #{shareScope}, #{parentId}, #{folderId}, #{createdBy}, #{updatedBy}, #{createAt}, #{updatedAt}
        FROM PROXY_VHOST
        WHERE user_no = #{userNo}
        """)
    void insertWithAutoSortOrder(ProxyVhost vhost);

    @Update("UPDATE PROXY_VHOST SET vhost_content = #{vhostContent}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateContent(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("vhostContent") String vhostContent, @Param("updatedAt") LocalDateTime updatedAt);

    // user_no stays the original creator's — this row's owner is looked up as -user_no elsewhere
    // (findByUserNo/findByVhostId), and reassigning it on every collaborator edit both
    // mis-attributes ownership and can collide with the uq_proxy_vhost_user_profile unique
    // constraint. updated_by is the dedicated column for who last touched shared content.
    @Update("UPDATE PROXY_VHOST SET vhost_content = #{vhostContent}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE vhost_id = #{parentId} AND user_no < 0")
    void updateContentByParentId(@Param("parentId") String parentId, @Param("userNo") Long userNo, @Param("vhostContent") String vhostContent, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET vhost_profile = #{vhostProfile}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateProfile(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("vhostProfile") String vhostProfile, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET selected = #{selected}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateSelected(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("selected") boolean selected, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET sort_order = #{sortOrder} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateSortOrder(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("sortOrder") int sortOrder);

    @Update("UPDATE PROXY_VHOST SET share_scope = #{shareScope}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateShareScope(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("shareScope") String shareScope, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET parent_id = #{parentId}, vhost_content = '', share_scope = 'collabo', updated_by = #{userNo}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void setAsCollaboRef(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("parentId") String parentId, @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM PROXY_VHOST WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void delete(@Param("vhostId") String vhostId, @Param("userNo") Long userNo);

    @Delete("DELETE FROM PROXY_VHOST WHERE vhost_id = #{vhostId}")
    void deleteByVhostId(String vhostId);

    @Delete("DELETE FROM PROXY_VHOST WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);

    // 'workspace' rows survive account deletion (cloudGroupService design doc §2.5 orphan handling)
    // - fetched before the delete above so the caller can reassign them to ws_system first.
    @Select("SELECT vhost_id, user_no, vhost_profile, vhost_content, selected, sort_order, share_scope, parent_id, created_by, updated_by, create_at, updated_at FROM PROXY_VHOST WHERE user_no = #{userNo} AND share_scope = 'workspace'")
    List<ProxyVhost> findPublicByUserNo(Long userNo);

    // created_by is left untouched - it's the immutable "who actually made this" audit trail
    // (CLAUDE.md's created_by/updated_by rule); only current ownership (user_no) and the
    // vhost_profile name (to dodge a uq_proxy_vhost_user_profile collision under the new owner)
    // move to ws_system.
    @Update("UPDATE PROXY_VHOST SET user_no = #{newOwnerUserNo}, vhost_profile = #{newProfileName}, updated_by = #{newOwnerUserNo}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId}")
    void reassignOwner(@Param("vhostId") String vhostId, @Param("newOwnerUserNo") Long newOwnerUserNo, @Param("newProfileName") String newProfileName, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Delete("DELETE FROM PROXY_VHOST WHERE user_no = -#{userNo} AND vhost_id NOT IN (SELECT DISTINCT parent_id FROM PROXY_VHOST WHERE parent_id IS NOT NULL)")
    void deleteOrphanedParentsByCreator(Long userNo);

    @Select("SELECT vhost_id, user_no, vhost_profile, vhost_content, selected, sort_order, share_scope, parent_id, updated_at FROM PROXY_VHOST WHERE user_no = #{userNo} AND parent_id IS NOT NULL")
    List<ProxyVhost> findReferencesByUserNo(Long userNo);

    @Select("SELECT COUNT(*) FROM PROXY_VHOST WHERE parent_id = #{parentId}")
    int countReferencesByParentId(@Param("parentId") String parentId);

    @Select("SELECT user_no FROM PROXY_VHOST WHERE parent_id = #{parentId} AND selected = TRUE AND user_no <> #{excludeUserNo}")
    List<Long> findSelectedUserNosByParentId(@Param("parentId") String parentId, @Param("excludeUserNo") Long excludeUserNo);

    // Lightweight name-only projection (skips the CLOB content column) for bulk uniqueness
    // checks in ProxyVhostService.nextUniqueName - avoids one COUNT round trip per candidate
    // name when resolving collisions.
    @Select("SELECT vhost_profile FROM PROXY_VHOST WHERE user_no = #{userNo}")
    List<String> findProfileNamesByUserNo(Long userNo);

}
