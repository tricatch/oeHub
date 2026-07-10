package tricatch.oe.proxy.service;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hub.i18n.LocaleContext;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.proxy.mapper.ProxyVhostMapper;
import tricatch.oe.proxy.model.ProxyVhost;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
            vhost.setVhostProfile(nextNewProfileName(mapper, userNo));
            vhost.setVhostContent(exampleContent());
            vhost.setSelected(false);
            vhost.setUpdatedAt(LocalDateTime.now());
            vhost.setVisibility("public");
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

    public void deleteAll(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var refs = mapper.findReferencesByUserNo(userNo);
            mapper.deleteByUserNo(userNo);
            for (var ref : refs) {
                if (mapper.countReferencesByParentId(ref.getParentId()) == 0) {
                    mapper.deleteByVhostId(ref.getParentId());
                }
            }
            mapper.deleteOrphanedParentsByCreator(userNo);
            session.commit();
        }
    }

    public ProxyVhost copyVhost(Long userNo, String sourceVhostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var source = mapper.findByVhostId(sourceVhostId);
            if (source == null) return null;
            var copy = new ProxyVhost();
            copy.setVhostId(newId());
            copy.setUserNo(userNo);
            copy.setVhostProfile(nextUniqueName(mapper, userNo, source.getVhostProfile()));
            copy.setVhostContent(source.getVhostContent());
            copy.setSelected(false);
            copy.setUpdatedAt(LocalDateTime.now());
            copy.setVisibility("public");
            mapper.insertWithAutoSortOrder(copy);
            session.commit();
            return mapper.findByVhostId(copy.getVhostId());
        }
    }

    public ProxyVhost registerCollabo(Long userNo, String parentId) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var parent = mapper.findByVhostId(parentId);
            if (parent == null || parent.getUserNo() >= 0 || !"collabo".equals(parent.getVisibility())) return null;
            var existing = mapper.findReferencesByUserNo(userNo);
            if (existing.stream().anyMatch(r -> parentId.equals(r.getParentId()))) return null;
            var ref = new ProxyVhost();
            ref.setVhostId(newId());
            ref.setUserNo(userNo);
            ref.setVhostProfile(nextUniqueName(mapper, userNo, parent.getVhostProfile()));
            ref.setVhostContent("");
            ref.setSelected(false);
            ref.setUpdatedAt(LocalDateTime.now());
            ref.setVisibility("collabo");
            ref.setParentId(parentId);
            mapper.insertWithAutoSortOrder(ref);
            session.commit();
            return mapper.findByVhostId(ref.getVhostId());
        }
    }

    public ProxyVhost updateVisibility(String vhostId, Long userNo, String visibility) {
        if ("collabo".equals(visibility)) {
            return convertToCollabo(vhostId, userNo);
        }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = mapper.findByVhostId(vhostId);
            if (vhost == null || !vhost.getUserNo().equals(userNo) || vhost.getUserNo() < 0) return null;
            if ("collabo".equals(vhost.getVisibility())) return null;
            mapper.updateVisibility(vhostId, userNo, visibility, LocalDateTime.now());
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
            parent.setUpdatedAt(now);
            parent.setVisibility("collabo");
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
            if (!merge) {
                var refs = mapper.findReferencesByUserNo(userNo);
                mapper.deleteByUserNo(userNo);
                for (var ref : refs) {
                    if (mapper.countReferencesByParentId(ref.getParentId()) == 0) {
                        mapper.deleteByVhostId(ref.getParentId());
                    }
                }
                mapper.deleteOrphanedParentsByCreator(userNo);
            }
            for (var entry : entries) {
                entry.setVhostId(newId());
                entry.setUserNo(userNo);
                entry.setVhostProfile(nextUniqueName(mapper, userNo, entry.getVhostProfile()));
                entry.setUpdatedAt(LocalDateTime.now());
                if (entry.getVisibility() == null) entry.setVisibility("public");
                mapper.insert(entry);
            }
            session.commit();
            return mapper.findByUserNo(userNo);
        }
    }

    public String getOwnerUsername(String vhostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var vhost = session.getMapper(ProxyVhostMapper.class).findByVhostId(vhostId);
            if (vhost == null) return null;
            var user = session.getMapper(HubUserMapper.class).findByUserNo(vhost.getUserNo());
            return user != null ? user.getUserId() : null;
        }
    }

    private String nextNewProfileName(ProxyVhostMapper mapper, Long userNo) {
        return nextUniqueName(mapper, userNo, "new vhost");
    }

    private String nextUniqueName(ProxyVhostMapper mapper, Long userNo, String base) {
        if (mapper.countByUserNoAndProfile(userNo, base) == 0) return base;
        for (int i = 2; i <= 999; i++) {
            var candidate = base + " (" + i + ")";
            if (mapper.countByUserNoAndProfile(userNo, candidate) == 0) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
