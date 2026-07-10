package tricatch.oe.hosts.service;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.mapper.HostsProfMapper;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hub.i18n.LocaleContext;
import tricatch.oe.hub.mapper.HubUserMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class HostsProfService {

    private static final String EXAMPLE_EN = loadResource("/example/hosts_example_en.txt", "127.0.0.1 foo.oe\n");
    private static final String EXAMPLE_KO = loadResource("/example/hosts_example_ko.txt", "127.0.0.1 foo.oe\n");

    private static String loadResource(String path, String fallback) {
        try (var in = HostsProfService.class.getResourceAsStream(path)) {
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

    public HostsProfService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public List<HostsProf> list(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HostsProfMapper.class).findByUserNo(userNo);
        }
    }

    public HostsProf get(String hostId) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HostsProfMapper.class).findByHostsId(hostId);
        }
    }

    public HostsProf create(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = new HostsProf();
            hosts.setHostsId(newId());
            hosts.setUserNo(userNo);
            hosts.setHostsProfile(nextNewProfileName(mapper, userNo));
            hosts.setHostsContent(exampleContent());
            hosts.setSelected(false);
            hosts.setUpdatedAt(LocalDateTime.now());
            hosts.setVisibility("public");
            mapper.insertWithAutoSortOrder(hosts);
            session.commit();
            return mapper.findByHostsId(hosts.getHostsId());
        }
    }

    public HostsProf updateContent(String hostId, Long userNo, String content) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var record = mapper.findByHostsId(hostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0) return null;
            var now = LocalDateTime.now();
            if (record.getParentId() != null) {
                mapper.updateContentByParentId(record.getParentId(), userNo, content, now);
            } else {
                mapper.updateContent(hostId, userNo, content, now);
            }
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public HostsProf updateProfile(String hostId, Long userNo, String newName) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var record = mapper.findByHostsId(hostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0 || record.getParentId() != null) return null;
            mapper.updateProfile(hostId, userNo, newName, LocalDateTime.now());
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public HostsProf toggleSelected(String hostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = mapper.findByHostsId(hostId);
            if (hosts == null || !hosts.getUserNo().equals(userNo) || hosts.getUserNo() < 0) return null;
            mapper.updateSelected(hostId, userNo, !hosts.isSelected(), LocalDateTime.now());
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public void reorder(Long userNo, List<String> hostIds) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            for (int i = 0; i < hostIds.size(); i++) {
                mapper.updateSortOrder(hostIds.get(i), userNo, i);
            }
            session.commit();
        }
    }

    public void delete(String hostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var record = mapper.findByHostsId(hostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0) return;
            var parentId = record.getParentId();
            mapper.delete(hostId, userNo);
            if (parentId != null && mapper.countReferencesByParentId(parentId) == 0) {
                mapper.deleteByHostsId(parentId);
            }
            session.commit();
        }
    }

    public void deleteAll(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var refs = mapper.findReferencesByUserNo(userNo);
            mapper.deleteByUserNo(userNo);
            for (var ref : refs) {
                if (mapper.countReferencesByParentId(ref.getParentId()) == 0) {
                    mapper.deleteByHostsId(ref.getParentId());
                }
            }
            mapper.deleteOrphanedParentsByCreator(userNo);
            session.commit();
        }
    }

    public HostsProf copyProfile(Long userNo, String sourceHostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var source = mapper.findByHostsId(sourceHostId);
            if (source == null) return null;
            var copy = new HostsProf();
            copy.setHostsId(newId());
            copy.setUserNo(userNo);
            copy.setHostsProfile(nextUniqueName(mapper, userNo, source.getHostsProfile()));
            copy.setHostsContent(source.getHostsContent());
            copy.setSelected(false);
            copy.setUpdatedAt(LocalDateTime.now());
            copy.setVisibility("public");
            mapper.insertWithAutoSortOrder(copy);
            session.commit();
            return mapper.findByHostsId(copy.getHostsId());
        }
    }

    public HostsProf registerCollabo(Long userNo, String parentId) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var parent = mapper.findByHostsId(parentId);
            if (parent == null || parent.getUserNo() >= 0 || !"collabo".equals(parent.getVisibility())) return null;
            var existing = mapper.findReferencesByUserNo(userNo);
            if (existing.stream().anyMatch(r -> parentId.equals(r.getParentId()))) return null;
            var ref = new HostsProf();
            ref.setHostsId(newId());
            ref.setUserNo(userNo);
            ref.setHostsProfile(nextUniqueName(mapper, userNo, parent.getHostsProfile()));
            ref.setHostsContent("");
            ref.setSelected(false);
            ref.setUpdatedAt(LocalDateTime.now());
            ref.setVisibility("collabo");
            ref.setParentId(parentId);
            mapper.insertWithAutoSortOrder(ref);
            session.commit();
            return mapper.findByHostsId(ref.getHostsId());
        }
    }

    public HostsProf updateVisibility(String hostId, Long userNo, String visibility) {
        if ("collabo".equals(visibility)) {
            return convertToCollabo(hostId, userNo);
        }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = mapper.findByHostsId(hostId);
            if (hosts == null || !hosts.getUserNo().equals(userNo) || hosts.getUserNo() < 0) return null;
            if ("collabo".equals(hosts.getVisibility())) return null;
            mapper.updateVisibility(hostId, userNo, visibility, LocalDateTime.now());
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    private HostsProf convertToCollabo(String hostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = mapper.findByHostsId(hostId);
            if (hosts == null || !hosts.getUserNo().equals(userNo) || hosts.getUserNo() < 0) return null;
            if (hosts.getParentId() != null) return null;
            var now = LocalDateTime.now();
            var parent = new HostsProf();
            parent.setHostsId(newId());
            parent.setUserNo(-userNo);
            parent.setHostsProfile(hosts.getHostsProfile());
            parent.setHostsContent(hosts.getHostsContent());
            parent.setSelected(false);
            parent.setSortOrder(0);
            parent.setUpdatedAt(now);
            parent.setVisibility("collabo");
            mapper.insert(parent);
            mapper.setAsCollaboRef(hostId, userNo, parent.getHostsId(), now);
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public List<HostsProf> searchOthers(Long userNo, String keyword) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HostsProfMapper.class).searchOthers(userNo, keyword);
        }
    }

    public List<HostsProf> importProfiles(Long userNo, List<HostsProf> entries, boolean merge) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            if (!merge) {
                var refs = mapper.findReferencesByUserNo(userNo);
                mapper.deleteByUserNo(userNo);
                for (var ref : refs) {
                    if (mapper.countReferencesByParentId(ref.getParentId()) == 0) {
                        mapper.deleteByHostsId(ref.getParentId());
                    }
                }
                mapper.deleteOrphanedParentsByCreator(userNo);
            }
            for (var entry : entries) {
                entry.setHostsId(newId());
                entry.setUserNo(userNo);
                entry.setHostsProfile(nextUniqueName(mapper, userNo, entry.getHostsProfile()));
                entry.setUpdatedAt(LocalDateTime.now());
                if (entry.getVisibility() == null) entry.setVisibility("public");
                mapper.insert(entry);
            }
            session.commit();
            return mapper.findByUserNo(userNo);
        }
    }

    public String getOwnerUserId(String hostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var hostsProf = session.getMapper(HostsProfMapper.class).findByHostsId(hostId);
            if (hostsProf == null) return null;
            var hubUser = session.getMapper(HubUserMapper.class).findByUserNo(hostsProf.getUserNo());
            return hubUser != null ? hubUser.getUserId() : null;
        }
    }

    private String nextNewProfileName(HostsProfMapper mapper, Long userNo) {
        return nextUniqueName(mapper, userNo, "new hosts");
    }

    private String nextUniqueName(HostsProfMapper mapper, Long userNo, String base) {
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
