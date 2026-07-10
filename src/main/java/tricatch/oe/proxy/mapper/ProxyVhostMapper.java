package tricatch.oe.proxy.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.proxy.model.ProxyVhost;

import java.time.LocalDateTime;
import java.util.List;

public interface ProxyVhostMapper {

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        LEFT JOIN HUB_USR u ON u.user_no = CASE WHEN h.parent_id IS NULL THEN h.user_no ELSE -p.user_no END
        WHERE h.user_no = #{userNo}
        ORDER BY h.sort_order ASC, h.updated_at ASC
        """)
    List<ProxyVhost> findByUserNo(Long userNo);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        WHERE h.user_no = #{userNo} AND h.selected = TRUE LIMIT 1
        """)
    ProxyVhost findSelectedByUserNo(Long userNo);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        WHERE h.user_no = #{userNo} AND h.selected = TRUE
        ORDER BY h.sort_order ASC
        """)
    List<ProxyVhost> findAllSelectedByUserNo(Long userNo);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile,
               COALESCE(p.vhost_content, h.vhost_content) AS vhost_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id
        FROM PROXY_VHOST h
        LEFT JOIN PROXY_VHOST p ON p.vhost_id = h.parent_id
        LEFT JOIN HUB_USR u ON u.user_no = CASE WHEN h.parent_id IS NULL THEN h.user_no ELSE -p.user_no END
        WHERE h.vhost_id = #{vhostId}
        """)
    ProxyVhost findByVhostId(String vhostId);

    @Select("""
        SELECT h.vhost_id, h.user_no, h.vhost_profile, h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        WHERE h.visibility = 'public'
          AND h.parent_id IS NULL
          AND h.user_no != #{userNo}
          AND h.user_no > 0
          AND LOWER(h.vhost_profile) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
        UNION ALL
        SELECT h.vhost_id, h.user_no, h.vhost_profile, h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at
        FROM PROXY_VHOST h
        WHERE h.user_no < 0
          AND h.visibility = 'collabo'
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
                                 visibility, parent_id, updated_at)
        VALUES (#{vhostId}, #{userNo}, #{vhostProfile}, #{vhostContent}, #{selected}, #{sortOrder},
                #{visibility}, #{parentId}, #{updatedAt})
        """)
    void insert(ProxyVhost vhost);

    @Insert("""
        INSERT INTO PROXY_VHOST (vhost_id, user_no, vhost_profile, vhost_content, selected, sort_order,
                                 visibility, parent_id, updated_at)
        SELECT #{vhostId}, #{userNo}, #{vhostProfile}, #{vhostContent}, #{selected},
               COALESCE(MAX(sort_order), -1) + 1,
               #{visibility}, #{parentId}, #{updatedAt}
        FROM PROXY_VHOST
        WHERE user_no = #{userNo}
        """)
    void insertWithAutoSortOrder(ProxyVhost vhost);

    @Update("UPDATE PROXY_VHOST SET vhost_content = #{vhostContent}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateContent(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("vhostContent") String vhostContent, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET vhost_content = #{vhostContent}, user_no = -#{userNo}, updated_at = #{updatedAt} WHERE vhost_id = #{parentId} AND user_no < 0")
    void updateContentByParentId(@Param("parentId") String parentId, @Param("userNo") Long userNo, @Param("vhostContent") String vhostContent, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET vhost_profile = #{vhostProfile}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateProfile(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("vhostProfile") String vhostProfile, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET selected = #{selected}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateSelected(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("selected") boolean selected, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET sort_order = #{sortOrder} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateSortOrder(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("sortOrder") int sortOrder);

    @Update("UPDATE PROXY_VHOST SET visibility = #{visibility}, updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void updateVisibility(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("visibility") String visibility, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE PROXY_VHOST SET parent_id = #{parentId}, vhost_content = '', visibility = 'collabo', updated_at = #{updatedAt} WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void setAsCollaboRef(@Param("vhostId") String vhostId, @Param("userNo") Long userNo, @Param("parentId") String parentId, @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM PROXY_VHOST WHERE vhost_id = #{vhostId} AND user_no = #{userNo}")
    void delete(@Param("vhostId") String vhostId, @Param("userNo") Long userNo);

    @Delete("DELETE FROM PROXY_VHOST WHERE vhost_id = #{vhostId}")
    void deleteByVhostId(String vhostId);

    @Delete("DELETE FROM PROXY_VHOST WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);

    @Delete("DELETE FROM PROXY_VHOST WHERE user_no = -#{userNo} AND vhost_id NOT IN (SELECT DISTINCT parent_id FROM PROXY_VHOST WHERE parent_id IS NOT NULL)")
    void deleteOrphanedParentsByCreator(Long userNo);

    @Select("SELECT vhost_id, user_no, vhost_profile, vhost_content, selected, sort_order, visibility, parent_id, updated_at FROM PROXY_VHOST WHERE user_no = #{userNo} AND parent_id IS NOT NULL")
    List<ProxyVhost> findReferencesByUserNo(Long userNo);

    @Select("SELECT COUNT(*) FROM PROXY_VHOST WHERE parent_id = #{parentId}")
    int countReferencesByParentId(@Param("parentId") String parentId);

    @Select("SELECT COUNT(*) FROM PROXY_VHOST WHERE user_no = #{userNo} AND LOWER(vhost_profile) = LOWER(#{vhostProfile})")
    int countByUserNoAndProfile(@Param("userNo") Long userNo, @Param("vhostProfile") String vhostProfile);

}
