package tricatch.oe.hub.folder;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hub.util.NameTakenException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-level folders for the oeHosts / oeProxy sidebar lists. Folders are per user, like the list
 * rows themselves; an item points at its folder via its own folder_id column (null = root).
 */
public class ListFolderService {

    public static final int MAX_NAME_LENGTH = 128;
    private static final String DEFAULT_NAME = "new folder";

    private final SqlSessionFactory sqlSessionFactory;
    private final FolderKind kind;

    public ListFolderService(SqlSessionFactory sqlSessionFactory, FolderKind kind) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.kind = kind;
    }

    public List<ListFolder> list(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(ListFolderMapper.class).findByUserNo(kind, userNo);
        }
    }

    // name null means "pick the default name" (made unique the same way new list items are).
    public ListFolder create(Long userNo, String name) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ListFolderMapper.class);
            var taken = takenNamesLower(mapper, userNo);
            String folderName;
            if (name == null) {
                folderName = nextUniqueName(taken, DEFAULT_NAME);
            } else {
                folderName = validName(name);
                if (taken.containsKey(folderName.toLowerCase())) throw new NameTakenException();
            }
            var folder = insert(mapper, kind, userNo, folderName, null);
            session.commit();
            return mapper.findById(kind, folder.getFolderId(), userNo);
        }
    }

    public ListFolder rename(Long userNo, String folderId, String name) {
        var folderName = validName(name);
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ListFolderMapper.class);
            if (mapper.findById(kind, folderId, userNo) == null) return null;
            var owner = takenNamesLower(mapper, userNo).get(folderName.toLowerCase());
            if (owner != null && !owner.equals(folderId)) throw new NameTakenException();
            mapper.updateName(kind, folderId, userNo, folderName, LocalDateTime.now());
            session.commit();
            return mapper.findById(kind, folderId, userNo);
        }
    }

    public ListFolder setCollapsed(Long userNo, String folderId, boolean collapsed) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ListFolderMapper.class);
            if (mapper.updateCollapsed(kind, folderId, userNo, collapsed) == 0) return null;
            session.commit();
            return mapper.findById(kind, folderId, userNo);
        }
    }

    public boolean delete(Long userNo, String folderId) {
        try (var session = sqlSessionFactory.openSession()) {
            var deleted = session.getMapper(ListFolderMapper.class).delete(kind, folderId, userNo) > 0;
            session.commit();
            return deleted;
        }
    }

    /** One row of the sidebar tree, in display order: a folder, or an item and the folder it is in. */
    public record TreeEntry(boolean folder, String id, String folderId) {}

    /**
     * Saves the whole sidebar tree as displayed: folders and items outside any folder share one
     * sort_order sequence (so they can be interleaved), and each item's folder comes with it.
     * Rows that aren't the caller's own are skipped, like the plain item reorder.
     *
     * @throws IllegalArgumentException if an item names a folder that isn't the caller's
     */
    public void saveTreeOrder(Long userNo, List<TreeEntry> entries) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ListFolderMapper.class);
            var own = new java.util.HashSet<String>();
            for (var f : mapper.findByUserNo(kind, userNo)) own.add(f.getFolderId());
            for (var e : entries) {
                if (!e.folder() && e.folderId() != null && !own.contains(e.folderId())) {
                    throw new IllegalArgumentException("unknown folder");
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                if (e.folder()) mapper.updateSortOrder(kind, e.id(), userNo, i);
                else mapper.updateItemPosition(kind, e.id(), userNo, e.folderId(), i);
            }
            session.commit();
        }
    }

    /**
     * Folder id for an imported row's folder name, creating the folder the first time a name is
     * seen. Runs inside the import's own session; {@code idByLowerName} carries the user's current
     * folders across the whole import (start it with {@link #folderIdsByLowerName}). An export
     * file has no folder positions, so a new folder goes right before its first row
     * ({@code itemSortOrder - 1}) - in an exported tree a folder's rows directly follow it.
     */
    public static String folderIdForImport(ListFolderMapper mapper, FolderKind kind, Long userNo,
                                           String rawName, int itemSortOrder, Map<String, String> idByLowerName) {
        if (rawName == null || rawName.isBlank()) return null;
        var name = rawName.trim();
        if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
        var existing = idByLowerName.get(name.toLowerCase());
        if (existing != null) return existing;
        var folder = insert(mapper, kind, userNo, name, itemSortOrder - 1);
        idByLowerName.put(name.toLowerCase(), folder.getFolderId());
        return folder.getFolderId();
    }

    public static Map<String, String> folderIdsByLowerName(ListFolderMapper mapper, FolderKind kind, Long userNo) {
        return takenNamesLower(mapper, kind, userNo);
    }

    private Map<String, String> takenNamesLower(ListFolderMapper mapper, Long userNo) {
        return takenNamesLower(mapper, kind, userNo);
    }

    // lower-cased name -> folder id
    private static Map<String, String> takenNamesLower(ListFolderMapper mapper, FolderKind kind, Long userNo) {
        var map = new HashMap<String, String>();
        for (var f : mapper.findByUserNo(kind, userNo)) map.put(f.getFolderName().toLowerCase(), f.getFolderId());
        return map;
    }

    // sortOrder null puts the folder after everything the user has.
    private static ListFolder insert(ListFolderMapper mapper, FolderKind kind, Long userNo, String name, Integer sortOrder) {
        var now = LocalDateTime.now();
        var folder = new ListFolder();
        folder.setFolderId(UUID.randomUUID().toString().replace("-", ""));
        folder.setUserNo(userNo);
        folder.setFolderName(name);
        folder.setCollapsed(false);
        folder.setCreatedBy(userNo);
        folder.setUpdatedBy(userNo);
        folder.setCreateAt(now);
        folder.setUpdatedAt(now);
        if (sortOrder == null) {
            mapper.insertWithAutoSortOrder(kind, folder);
        } else {
            folder.setSortOrder(sortOrder);
            mapper.insert(kind, folder);
        }
        return folder;
    }

    private static String validName(String name) {
        var trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) throw new IllegalArgumentException("invalid folder name");
        return trimmed;
    }

    private static String nextUniqueName(Map<String, String> takenLower, String base) {
        if (!takenLower.containsKey(base.toLowerCase())) return base;
        for (int i = 2; i <= 999; i++) {
            var candidate = base + " (" + i + ")";
            if (!takenLower.containsKey(candidate.toLowerCase())) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }
}
