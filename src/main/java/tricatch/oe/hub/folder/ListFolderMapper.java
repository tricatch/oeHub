package tricatch.oe.hub.folder;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

// One mapper for both HOSTS_FOLDER and PROXY_FOLDER: the table/column names come from the
// FolderKind enum (${k.*} - code constants only, never user input), every value is still bound.
public interface ListFolderMapper {

    String COLUMNS = "folder_id, user_no, folder_name, sort_order, collapsed, created_by, updated_by, create_at, updated_at";

    // Folders and the items outside them share one sort_order sequence (the sidebar interleaves
    // them), so a new folder goes after everything the user has.
    String NEXT_SORT_ORDER = """
        SELECT GREATEST(COALESCE((SELECT MAX(sort_order) FROM ${k.folderTable} WHERE user_no = #{f.userNo}), -1),
                        COALESCE((SELECT MAX(sort_order) FROM ${k.itemTable} WHERE user_no = #{f.userNo}), -1)) + 1
        """;

    @Select("SELECT " + COLUMNS + " FROM ${k.folderTable} WHERE user_no = #{userNo} ORDER BY sort_order ASC, create_at ASC")
    List<ListFolder> findByUserNo(@Param("k") FolderKind k, @Param("userNo") Long userNo);

    @Select("SELECT " + COLUMNS + " FROM ${k.folderTable} WHERE folder_id = #{folderId} AND user_no = #{userNo}")
    ListFolder findById(@Param("k") FolderKind k, @Param("folderId") String folderId, @Param("userNo") Long userNo);

    @Insert("""
        INSERT INTO ${k.folderTable} (folder_id, user_no, folder_name, sort_order, collapsed,
                                      created_by, updated_by, create_at, updated_at)
        VALUES (#{f.folderId}, #{f.userNo}, #{f.folderName},
        """ + "(" + NEXT_SORT_ORDER + ")," + """
                #{f.collapsed}, #{f.createdBy}, #{f.updatedBy}, #{f.createAt}, #{f.updatedAt})
        """)
    void insertWithAutoSortOrder(@Param("k") FolderKind k, @Param("f") ListFolder f);

    @Insert("""
        INSERT INTO ${k.folderTable} (folder_id, user_no, folder_name, sort_order, collapsed,
                                      created_by, updated_by, create_at, updated_at)
        VALUES (#{f.folderId}, #{f.userNo}, #{f.folderName}, #{f.sortOrder}, #{f.collapsed},
                #{f.createdBy}, #{f.updatedBy}, #{f.createAt}, #{f.updatedAt})
        """)
    void insert(@Param("k") FolderKind k, @Param("f") ListFolder f);

    @Update("UPDATE ${k.folderTable} SET folder_name = #{folderName}, updated_by = #{userNo}, updated_at = #{updatedAt} WHERE folder_id = #{folderId} AND user_no = #{userNo}")
    int updateName(@Param("k") FolderKind k, @Param("folderId") String folderId, @Param("userNo") Long userNo,
                   @Param("folderName") String folderName, @Param("updatedAt") LocalDateTime updatedAt);

    // View state, like an item's sort_order - not an edit, so updated_by/updated_at stay as they are.
    @Update("UPDATE ${k.folderTable} SET collapsed = #{collapsed} WHERE folder_id = #{folderId} AND user_no = #{userNo}")
    int updateCollapsed(@Param("k") FolderKind k, @Param("folderId") String folderId, @Param("userNo") Long userNo,
                        @Param("collapsed") boolean collapsed);

    @Update("UPDATE ${k.folderTable} SET sort_order = #{sortOrder} WHERE folder_id = #{folderId} AND user_no = #{userNo}")
    void updateSortOrder(@Param("k") FolderKind k, @Param("folderId") String folderId, @Param("userNo") Long userNo,
                         @Param("sortOrder") int sortOrder);

    // The items' FK (ON DELETE SET NULL) moves whatever was inside back to the root.
    @Delete("DELETE FROM ${k.folderTable} WHERE folder_id = #{folderId} AND user_no = #{userNo}")
    int delete(@Param("k") FolderKind k, @Param("folderId") String folderId, @Param("userNo") Long userNo);

    @Delete("DELETE FROM ${k.folderTable} WHERE user_no = #{userNo}")
    void deleteByUserNo(@Param("k") FolderKind k, @Param("userNo") Long userNo);

    // An item's place in the sidebar tree: its folder (null = root) and its position. Only the
    // caller's own rows (user_no), same as the items' plain sort_order update.
    @Update("UPDATE ${k.itemTable} SET folder_id = #{folderId}, sort_order = #{sortOrder} WHERE ${k.itemIdColumn} = #{itemId} AND user_no = #{userNo}")
    int updateItemPosition(@Param("k") FolderKind k, @Param("itemId") String itemId, @Param("userNo") Long userNo,
                           @Param("folderId") String folderId, @Param("sortOrder") int sortOrder);
}
