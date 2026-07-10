package tricatch.oe.hosts.mapper;

import org.apache.ibatis.annotations.*;
import tricatch.oe.hosts.model.HostsProf;

import java.time.LocalDateTime;
import java.util.List;

public interface HostsProfMapper {

    @Select("""
        SELECT h.hosts_id, h.user_no, h.hosts_profile,
               COALESCE(p.hosts_content, h.hosts_content) AS hosts_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id
        FROM HOSTS_PFILE h
        LEFT JOIN HOSTS_PFILE p ON p.hosts_id = h.parent_id
        LEFT JOIN HUB_USR u ON u.user_no = CASE WHEN h.parent_id IS NULL THEN h.user_no ELSE -p.user_no END
        WHERE h.user_no = #{userNo}
        ORDER BY h.sort_order ASC, h.updated_at ASC
        """)
    List<HostsProf> findByUserNo(Long userNo);

    @Select("""
        SELECT h.hosts_id, h.user_no, h.hosts_profile,
               COALESCE(p.hosts_content, h.hosts_content) AS hosts_content,
               h.selected, h.sort_order, h.visibility,
               h.parent_id, COALESCE(p.updated_at, h.updated_at) AS updated_at,
               u.user_id
        FROM HOSTS_PFILE h
        LEFT JOIN HOSTS_PFILE p ON p.hosts_id = h.parent_id
        LEFT JOIN HUB_USR u ON u.user_no = CASE WHEN h.parent_id IS NULL THEN h.user_no ELSE -p.user_no END
        WHERE h.hosts_id = #{hostsId}
        """)
    HostsProf findByHostsId(String hostsId);

    @Select("""
        SELECT h.hosts_id, h.user_no, h.hosts_profile, h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at, u.user_id
        FROM HOSTS_PFILE h
        JOIN HUB_USR u ON u.user_no = h.user_no
        WHERE h.visibility = 'public'
          AND h.parent_id IS NULL
          AND h.user_no != #{userNo}
          AND LOWER(h.hosts_profile) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
        UNION ALL
        SELECT h.hosts_id, h.user_no, h.hosts_profile, h.selected, h.sort_order, h.visibility,
               h.parent_id, h.updated_at, u.user_id
        FROM HOSTS_PFILE h
        JOIN HUB_USR u ON u.user_no = (-h.user_no)
        WHERE h.user_no < 0
          AND h.visibility = 'collabo'
          AND NOT EXISTS (
            SELECT 1 FROM HOSTS_PFILE r WHERE r.parent_id = h.hosts_id AND r.user_no = #{userNo}
          )
          AND LOWER(h.hosts_profile) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
        ORDER BY updated_at DESC
        LIMIT 100
        """)
    List<HostsProf> searchOthers(@Param("userNo") Long userNo, @Param("keyword") String keyword);

    @Insert("""
        INSERT INTO HOSTS_PFILE (hosts_id, user_no, hosts_profile, hosts_content, selected, sort_order,
                                 visibility, parent_id, updated_at)
        VALUES (#{hostsId}, #{userNo}, #{hostsProfile}, #{hostsContent}, #{selected}, #{sortOrder},
                #{visibility}, #{parentId}, #{updatedAt})
        """)
    void insert(HostsProf hostsProf);

    @Insert("""
        INSERT INTO HOSTS_PFILE (hosts_id, user_no, hosts_profile, hosts_content, selected, sort_order,
                                 visibility, parent_id, updated_at)
        SELECT #{hostsId}, #{userNo}, #{hostsProfile}, #{hostsContent}, #{selected},
               COALESCE(MAX(sort_order), -1) + 1,
               #{visibility}, #{parentId}, #{updatedAt}
        FROM HOSTS_PFILE
        WHERE user_no = #{userNo}
        """)
    void insertWithAutoSortOrder(HostsProf hostsProf);

    @Update("UPDATE HOSTS_PFILE SET hosts_content = #{hostsContent}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateContent(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("hostsContent") String hostsContent, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET hosts_content = #{hostsContent}, user_no = -#{userNo}, updated_at = #{updatedAt} WHERE hosts_id = #{parentId} AND user_no < 0")
    void updateContentByParentId(@Param("parentId") String parentId, @Param("userNo") Long userNo, @Param("hostsContent") String hostsContent, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET hosts_profile = #{hostsProfile}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateProfile(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("hostsProfile") String hostsProfile, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET selected = #{selected}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateSelected(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("selected") boolean selected, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET sort_order = #{sortOrder} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateSortOrder(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("sortOrder") int sortOrder);

    @Update("UPDATE HOSTS_PFILE SET visibility = #{visibility}, updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void updateVisibility(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("visibility") String visibility, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE HOSTS_PFILE SET parent_id = #{parentId}, hosts_content = '', visibility = 'collabo', updated_at = #{updatedAt} WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void setAsCollaboRef(@Param("hostsId") String hostsId, @Param("userNo") Long userNo, @Param("parentId") String parentId, @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM HOSTS_PFILE WHERE hosts_id = #{hostsId} AND user_no = #{userNo}")
    void delete(@Param("hostsId") String hostsId, @Param("userNo") Long userNo);

    @Delete("DELETE FROM HOSTS_PFILE WHERE hosts_id = #{hostsId}")
    void deleteByHostsId(String hostsId);

    @Delete("DELETE FROM HOSTS_PFILE WHERE user_no = #{userNo}")
    void deleteByUserNo(Long userNo);

    @Delete("DELETE FROM HOSTS_PFILE WHERE user_no = -#{userNo} AND hosts_id NOT IN (SELECT DISTINCT parent_id FROM HOSTS_PFILE WHERE parent_id IS NOT NULL)")
    void deleteOrphanedParentsByCreator(Long userNo);

    @Select("SELECT hosts_id, user_no, hosts_profile, hosts_content, selected, sort_order, visibility, parent_id, updated_at FROM HOSTS_PFILE WHERE user_no = #{userNo} AND parent_id IS NOT NULL")
    List<HostsProf> findReferencesByUserNo(Long userNo);

    @Select("SELECT COUNT(*) FROM HOSTS_PFILE WHERE parent_id = #{parentId}")
    int countReferencesByParentId(@Param("parentId") String parentId);

    @Select("SELECT COUNT(*) FROM HOSTS_PFILE WHERE user_no = #{userNo} AND LOWER(hosts_profile) = LOWER(#{hostsProfile})")
    int countByUserNoAndProfile(@Param("userNo") Long userNo, @Param("hostsProfile") String hostsProfile);

}
