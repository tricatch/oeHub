package tricatch.oe.proxy.service;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hub.i18n.LocaleContext;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.proxy.mapper.ProxyVhostMapper;
import tricatch.oe.proxy.model.ProxyVhost;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ProxyVhostService {

    private static final String EXAMPLE_EN = loadResource("/example/vhost_example_en.yaml", "");
    private static final String EXAMPLE_KO = loadResource("/example/vhost_example_ko.yaml", "");

    private static String loadResource(String path, String fallback) {
        try (var in = ProxyVhostService.class.getResourceAsStream(path)) {
            if (in == null) return fallback;
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String exampleContent() {
        return "ko".equals(LocaleContext.get()) ? EXAMPLE_KO : EXAMPLE_EN;
    }

    private final SqlSessionFactory sqlSessionFactory;

    public ProxyVhostService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public List<ProxyVhost> list(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(ProxyVhostMapper.class).findByUserNo(userNo);
        }
    }

    public List<ProxyVhost> listSelected(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(ProxyVhostMapper.class).findAllSelectedByUserNo(userNo);
        }
    }

    public ProxyVhost get(String vhostId) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(ProxyVhostMapper.class).findByVhostId(vhostId);
        }
    }

    public ProxyVhost create(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = new ProxyVhost();
            vhost.setVhostId(newId());
            vhost.setUserNo(userNo);
            vhost.setVhostProfile(nextUniqueName(existingNames(mapper, userNo), "new vhost"));
            vhost.setVhostContent(exampleContent());
            vhost.setSelected(false);
            var now = LocalDateTime.now();
            vhost.setCreatedBy(userNo);
            vhost.setUpdatedBy(userNo);
            vhost.setCreateAt(now);
            vhost.setUpdatedAt(now);
            vhost.setShareScope("workspace");
            mapper.insertWithAutoSortOrder(vhost);
            session.commit();
            return mapper.findByVhostId(vhost.getVhostId());
        }
    }

    public ProxyVhost updateContent(String vhostId, Long userNo, String content) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var record = mapper.findByVhostId(vhostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0) return null;
            var now = LocalDateTime.now();
            if (record.getParentId() != null) {
                mapper.updateContentByParentId(record.getParentId(), userNo, content, now);
            } else {
                mapper.updateContent(vhostId, userNo, content, now);
            }
            session.commit();
            return mapper.findByVhostId(vhostId);
        }
    }

    // Other users who currently have this shared (collabo) vhost selected, so their cached live
    // routing can be invalidated after one collaborator edits the shared content.
    public List<Long> selectedCollaboratorUserNos(String parentId, Long excludeUserNo) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(ProxyVhostMapper.class).findSelectedUserNosByParentId(parentId, excludeUserNo);
        }
    }

    public ProxyVhost updateProfile(String vhostId, Long userNo, String newName) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var record = mapper.findByVhostId(vhostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0 || record.getParentId() != null) return null;
            mapper.updateProfile(vhostId, userNo, newName, LocalDateTime.now());
            session.commit();
            return mapper.findByVhostId(vhostId);
        }
    }

    public ProxyVhost toggleSelected(String vhostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = mapper.findByVhostId(vhostId);
            if (vhost == null || !vhost.getUserNo().equals(userNo) || vhost.getUserNo() < 0) return null;
            mapper.updateSelected(vhostId, userNo, !vhost.isSelected(), LocalDateTime.now());
            session.commit();
            return mapper.findByVhostId(vhostId);
        }
    }

    public void reorder(Long userNo, List<String> vhostIds) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            for (int i = 0; i < vhostIds.size(); i++) {
                mapper.updateSortOrder(vhostIds.get(i), userNo, i);
            }
            session.commit();
        }
    }

    public void delete(String vhostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var record = mapper.findByVhostId(vhostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0) return;
            var parentId = record.getParentId();
            mapper.delete(vhostId, userNo);
            if (parentId != null && mapper.countReferencesByParentId(parentId) == 0) {
                mapper.deleteByVhostId(parentId);
            }
            session.commit();
        }
    }

    /**
     * Removes everything the user owns because they asked for it ("delete all my vhosts"): every
     * vhost goes, 'workspace' ones included - the same as deleting each one by hand, or a
     * replace-import. Account removal is different, see {@link #deleteAllForAccountRemoval}.
     */
    public void deleteAll(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            removeAllRows(session.getMapper(ProxyVhostMapper.class), userNo);
            session.commit();
        }
    }

    /**
     * Removes a user's vhosts because their ACCOUNT is being deleted. 'workspace' vhosts are
     * reassigned to the workspace's wss account rather than deleted with the account -
     * 'private'/'collabo' still go away (cloudGroupService design doc §2.5 orphan handling). Must
     * not be used for a user-requested "delete all": that must really delete.
     */
    public void deleteAllForAccountRemoval(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);

            // Must run before removeAllRows, which would otherwise delete these too.
            var deletedUser = session.getMapper(HubUserMapper.class).findByUserNo(userNo);
            if (deletedUser != null) {
                var wsSystem = session.getMapper(HubUserMapper.class).findWsSystemByWsNo(deletedUser.getWsNo());
                if (wsSystem != null) {
                    var publicVhosts = mapper.findPublicByUserNo(userNo);
                    var takenNames = existingNames(mapper, wsSystem.getUserNo());
                    var now = LocalDateTime.now();
                    for (var v : publicVhosts) {
                        var name = nextUniqueName(takenNames, v.getVhostProfile());
                        takenNames.add(name.toLowerCase());
                        mapper.reassignOwner(v.getVhostId(), wsSystem.getUserNo(), name, now);
                    }
                }
            }

            removeAllRows(mapper, userNo);
            session.commit();
        }
    }

    // Deletes every row the user owns, then any shared (collabo) parent left with no references.
    private void removeAllRows(ProxyVhostMapper mapper, Long userNo) {
        var refs = mapper.findReferencesByUserNo(userNo);
        mapper.deleteByUserNo(userNo);
        for (var ref : refs) {
            if (mapper.countReferencesByParentId(ref.getParentId()) == 0) {
                mapper.deleteByVhostId(ref.getParentId());
            }
        }
        mapper.deleteOrphanedParentsByCreator(userNo);
    }

    public ProxyVhost copyVhost(Long userNo, String sourceVhostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var source = mapper.findByVhostId(sourceVhostId);
            if (source == null) return null;
            if (!userNo.equals(source.getUserNo()) && "private".equals(source.getShareScope())) return null;
            var copy = new ProxyVhost();
            copy.setVhostId(newId());
            copy.setUserNo(userNo);
            copy.setVhostProfile(nextUniqueName(existingNames(mapper, userNo), source.getVhostProfile()));
            copy.setVhostContent(source.getVhostContent());
            copy.setSelected(false);
            var now = LocalDateTime.now();
            copy.setCreatedBy(userNo);
            copy.setUpdatedBy(userNo);
            copy.setCreateAt(now);
            copy.setUpdatedAt(now);
            copy.setShareScope("workspace");
            mapper.insertWithAutoSortOrder(copy);
            session.commit();
            return mapper.findByVhostId(copy.getVhostId());
        }
    }

    public ProxyVhost registerCollabo(Long userNo, String parentId) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var parent = mapper.findByVhostId(parentId);
            if (parent == null || parent.getUserNo() >= 0 || !"collabo".equals(parent.getShareScope())) return null;
            var existing = mapper.findReferencesByUserNo(userNo);
            if (existing.stream().anyMatch(r -> parentId.equals(r.getParentId()))) return null;
            var ref = new ProxyVhost();
            ref.setVhostId(newId());
            ref.setUserNo(userNo);
            ref.setVhostProfile(nextUniqueName(existingNames(mapper, userNo), parent.getVhostProfile()));
            ref.setVhostContent("");
            ref.setSelected(false);
            var now = LocalDateTime.now();
            ref.setCreatedBy(userNo);
            ref.setUpdatedBy(userNo);
            ref.setCreateAt(now);
            ref.setUpdatedAt(now);
            ref.setShareScope("collabo");
            ref.setParentId(parentId);
            mapper.insertWithAutoSortOrder(ref);
            session.commit();
            return mapper.findByVhostId(ref.getVhostId());
        }
    }

    public ProxyVhost updateShareScope(String vhostId, Long userNo, String shareScope) {
        if ("collabo".equals(shareScope)) {
            return convertToCollabo(vhostId, userNo);
        }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = mapper.findByVhostId(vhostId);
            if (vhost == null || !vhost.getUserNo().equals(userNo) || vhost.getUserNo() < 0) return null;
            if ("collabo".equals(vhost.getShareScope())) return null;
            mapper.updateShareScope(vhostId, userNo, shareScope, LocalDateTime.now());
            session.commit();
            return mapper.findByVhostId(vhostId);
        }
    }

    private ProxyVhost convertToCollabo(String vhostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = mapper.findByVhostId(vhostId);
            if (vhost == null || !vhost.getUserNo().equals(userNo) || vhost.getUserNo() < 0) return null;
            if (vhost.getParentId() != null) return null;
            var now = LocalDateTime.now();
            var parent = new ProxyVhost();
            parent.setVhostId(newId());
            parent.setUserNo(-userNo);
            parent.setVhostProfile(vhost.getVhostProfile());
            parent.setVhostContent(vhost.getVhostContent());
            parent.setSelected(false);
            parent.setSortOrder(0);
            // The real actor is always the positive userNo, even though ownership (user_no) is
            // stored negative for this row.
            parent.setCreatedBy(userNo);
            parent.setUpdatedBy(userNo);
            parent.setCreateAt(now);
            parent.setUpdatedAt(now);
            parent.setShareScope("collabo");
            mapper.insert(parent);
            mapper.setAsCollaboRef(vhostId, userNo, parent.getVhostId(), now);
            session.commit();
            return mapper.findByVhostId(vhostId);
        }
    }

    public List<ProxyVhost> searchOthers(Long userNo, String keyword) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(ProxyVhostMapper.class).searchOthers(userNo, keyword);
        }
    }

    public List<ProxyVhost> importVhosts(Long userNo, List<ProxyVhost> entries, boolean merge) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            if (!merge) removeAllRows(mapper, userNo);
            var existingNames = existingNames(mapper, userNo);
            for (var entry : entries) {
                entry.setVhostId(newId());
                entry.setUserNo(userNo);
                var name = nextUniqueName(existingNames, entry.getVhostProfile());
                entry.setVhostProfile(name);
                existingNames.add(name.toLowerCase());
                var now = LocalDateTime.now();
                entry.setCreatedBy(userNo);
                entry.setUpdatedBy(userNo);
                entry.setCreateAt(now);
                entry.setUpdatedAt(now);
                if (entry.getShareScope() == null) entry.setShareScope("private");
                mapper.insert(entry);
            }
            session.commit();
            return mapper.findByUserNo(userNo);
        }
    }

    /**
     * The rows described by an import/restore file's list of vhosts. The whole list is validated
     * before anything is written, so a bad entry can't leave a replace-import half applied.
     *
     * @throws IllegalArgumentException (message safe to show) if the list or an entry is malformed
     */
    public static List<ProxyVhost> entriesFromImport(Object raw) {
        var entries = new java.util.ArrayList<ProxyVhost>();
        for (var m : tricatch.oe.hub.util.ImportFields.maps(raw, "vhosts")) {
            var v = new ProxyVhost();
            v.setVhostProfile(tricatch.oe.hub.util.ImportFields.requiredText(m, "vhostProfile"));
            v.setVhostContent(tricatch.oe.hub.util.ImportFields.text(m, "vhostContent", ""));
            v.setSelected(Boolean.TRUE.equals(m.get("selected")));
            v.setSortOrder(tricatch.oe.hub.util.ImportFields.intValue(m, "sortOrder", 0));
            // Import creates standalone entries, never collabo refs, and a file that doesn't say
            // "workspace" must not make the row workspace-scoped - see ShareScopeUtil.forImport.
            v.setShareScope(tricatch.oe.hub.util.ShareScopeUtil.forImport(m.get("shareScope")));
            entries.add(v);
        }
        return entries;
    }

    public String getOwnerUsername(String vhostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var vhost = session.getMapper(ProxyVhostMapper.class).findByVhostId(vhostId);
            if (vhost == null) return null;
            var user = session.getMapper(HubUserMapper.class).findByUserNo(vhost.getUserNo());
            return user != null ? user.getUserId() : null;
        }
    }

    // Case-insensitive, matching countByUserNoAndProfile's LOWER() comparison this replaces.
    private Set<String> existingNames(ProxyVhostMapper mapper, Long userNo) {
        return mapper.findProfileNamesByUserNo(userNo).stream()
            .map(String::toLowerCase)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private String nextUniqueName(Set<String> existingNamesLower, String base) {
        if (!existingNamesLower.contains(base.toLowerCase())) return base;
        for (int i = 2; i <= 999; i++) {
            var candidate = base + " (" + i + ")";
            if (!existingNamesLower.contains(candidate.toLowerCase())) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
