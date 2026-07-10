package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.mapper.HostsProfMapper;
import tricatch.oe.hosts.model.HostsProf;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HostsProfMapperTest extends MapperTestBase {

    private HostsProf newHosts(Long userNo, String profile, String content) {
        var h = new HostsProf();
        h.setHostsId(newId());
        h.setUserNo(userNo);
        h.setHostsProfile(profile);
        h.setHostsContent(content);
        h.setSelected(false);
        h.setSortOrder(0);
        h.setVisibility("public");
        h.setUpdatedAt(LocalDateTime.now());
        return h;
    }

    @Test
    void insertAndFindByHostsId() {
        var user = insertUser("alice");
        var hosts = newHosts(user.getUserNo(), "my profile", "127.0.0.1 foo.oe");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            mapper.insert(hosts);
            var found = mapper.findByHostsId(hosts.getHostsId());
            assertThat(found).isNotNull();
            assertThat(found.getHostsProfile()).isEqualTo("my profile");
            assertThat(found.getHostsContent()).isEqualTo("127.0.0.1 foo.oe");
            assertThat(found.isSelected()).isFalse();
            assertThat(found.getVisibility()).isEqualTo("public");
            assertThat(found.getParentId()).isNull();
            assertThat(found.getUpdatedAt()).isNotNull();
        }
    }

    @Test
    void findByUserNo() {
        var user = insertUser("bob");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var h1 = newHosts(user.getUserNo(), "p1", "c1");
            h1.setSortOrder(0);
            var h2 = newHosts(user.getUserNo(), "p2", "c2");
            h2.setSortOrder(1);
            mapper.insert(h1);
            mapper.insert(h2);
            var list = mapper.findByUserNo(user.getUserNo());
            assertThat(list).hasSize(2);
            assertThat(list.get(0).getHostsProfile()).isEqualTo("p1");
            assertThat(list.get(1).getHostsProfile()).isEqualTo("p2");
        }
    }

    @Test
    void updateContent() {
        var user = insertUser("carol");
        var insertTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "profile", "old");
            hosts.setUpdatedAt(insertTime);
            mapper.insert(hosts);

            var updateTime = insertTime.plusSeconds(1);
            mapper.updateContent(hosts.getHostsId(), user.getUserNo(), "new content", updateTime);

            var found = mapper.findByHostsId(hosts.getHostsId());
            assertThat(found.getHostsContent()).isEqualTo("new content");
            assertThat(found.getUpdatedAt()).isEqualTo(updateTime);
        }
    }

    @Test
    void updateProfile() {
        var user = insertUser("dave");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "old name", "content");
            mapper.insert(hosts);
            mapper.updateProfile(hosts.getHostsId(), user.getUserNo(), "new name", LocalDateTime.now());
            assertThat(mapper.findByHostsId(hosts.getHostsId()).getHostsProfile()).isEqualTo("new name");
        }
    }

    @Test
    void updateSelected() {
        var user = insertUser("eve");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "profile", "content");
            mapper.insert(hosts);
            mapper.updateSelected(hosts.getHostsId(), user.getUserNo(), true, LocalDateTime.now());
            assertThat(mapper.findByHostsId(hosts.getHostsId()).isSelected()).isTrue();
        }
    }

    @Test
    void updateSortOrder() {
        var user = insertUser("frank");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "profile", "content");
            mapper.insert(hosts);
            mapper.updateSortOrder(hosts.getHostsId(), user.getUserNo(), 42);
            assertThat(mapper.findByHostsId(hosts.getHostsId()).getSortOrder()).isEqualTo(42);
        }
    }

    @Test
    void updateVisibility() {
        var user = insertUser("grace");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "profile", "content");
            mapper.insert(hosts);
            mapper.updateVisibility(hosts.getHostsId(), user.getUserNo(), "private", LocalDateTime.now());
            assertThat(mapper.findByHostsId(hosts.getHostsId()).getVisibility()).isEqualTo("private");
        }
    }

    @Test
    void delete() {
        var user = insertUser("heidi");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "profile", "content");
            mapper.insert(hosts);
            mapper.delete(hosts.getHostsId(), user.getUserNo());
            assertThat(mapper.findByHostsId(hosts.getHostsId())).isNull();
        }
    }

    @Test
    void delete_ignoresWrongUser() {
        var user1 = insertUser("ivan");
        var user2 = insertUser("judy");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user1.getUserNo(), "profile", "content");
            mapper.insert(hosts);
            mapper.delete(hosts.getHostsId(), user2.getUserNo()); // 다른 사용자로 삭제 시도
            assertThat(mapper.findByHostsId(hosts.getHostsId())).isNotNull();
        }
    }

    @Test
    void deleteByHostsId() {
        var user = insertUser("kate");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "profile", "content");
            mapper.insert(hosts);
            mapper.deleteByHostsId(hosts.getHostsId());
            assertThat(mapper.findByHostsId(hosts.getHostsId())).isNull();
        }
    }

    @Test
    void deleteByUserNo() {
        var user = insertUser("leo");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            mapper.insert(newHosts(user.getUserNo(), "p1", "c1"));
            mapper.insert(newHosts(user.getUserNo(), "p2", "c2"));
            mapper.deleteByUserNo(user.getUserNo());
            assertThat(mapper.findByUserNo(user.getUserNo())).isEmpty();
        }
    }

    @Test
    void countByUserNoAndProfile() {
        var user = insertUser("mia");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            mapper.insert(newHosts(user.getUserNo(), "My Profile", "content"));
            assertThat(mapper.countByUserNoAndProfile(user.getUserNo(), "My Profile")).isEqualTo(1);
            assertThat(mapper.countByUserNoAndProfile(user.getUserNo(), "my profile")).isEqualTo(1);
            assertThat(mapper.countByUserNoAndProfile(user.getUserNo(), "Other")).isEqualTo(0);
        }
    }

    @Test
    void collaboFlow() {
        var owner = insertUser("nina");
        var collab = insertUser("oscar");

        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);

            // owner의 원본 엔트리
            var original = newHosts(owner.getUserNo(), "shared profile", "original content");
            mapper.insert(original);

            // collabo 부모 (user_no < 0)
            var parent = newHosts(-owner.getUserNo(), "shared profile", "original content");
            parent.setVisibility("collabo");
            mapper.insert(parent);

            // 원본을 collabo 참조로 전환
            mapper.setAsCollaboRef(original.getHostsId(), owner.getUserNo(), parent.getHostsId(), LocalDateTime.now());

            // collab 사용자가 참조 등록
            var ref = newHosts(collab.getUserNo(), "shared profile", "");
            ref.setVisibility("collabo");
            ref.setParentId(parent.getHostsId());
            mapper.insert(ref);

            // 부모 컨텐츠 수정 → COALESCE로 두 참조 모두 새 컨텐츠 반영
            mapper.updateContentByParentId(parent.getHostsId(), collab.getUserNo(), "updated content", LocalDateTime.now());

            assertThat(mapper.findByHostsId(original.getHostsId()).getHostsContent()).isEqualTo("updated content");
            assertThat(mapper.findByHostsId(ref.getHostsId()).getHostsContent()).isEqualTo("updated content");

            // owner의 참조 목록
            var ownerRefs = mapper.findReferencesByUserNo(owner.getUserNo());
            assertThat(ownerRefs).hasSize(1);
            assertThat(ownerRefs.get(0).getParentId()).isEqualTo(parent.getHostsId());

            // collab 사용자의 참조 목록
            var collabRefs = mapper.findReferencesByUserNo(collab.getUserNo());
            assertThat(collabRefs).hasSize(1);
            assertThat(collabRefs.get(0).getParentId()).isEqualTo(parent.getHostsId());

            // 전체 참조 수: owner + collab = 2
            assertThat(mapper.countReferencesByParentId(parent.getHostsId())).isEqualTo(2);

            // collab 참조 삭제 → 참조 수 1로 감소
            mapper.delete(ref.getHostsId(), collab.getUserNo());
            assertThat(mapper.countReferencesByParentId(parent.getHostsId())).isEqualTo(1);
        }
    }

    @Test
    void deleteOrphanedParentsByCreator() {
        var owner = insertUser("petra");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);

            // 고아 부모: 참조 없음 → 삭제 대상
            var orphan = newHosts(-owner.getUserNo(), "orphan profile", "content");
            orphan.setVisibility("collabo");
            mapper.insert(orphan);

            // 활성 부모: 참조 있음 → 보존 대상
            var active = newHosts(-owner.getUserNo(), "active profile", "content");
            active.setVisibility("collabo");
            mapper.insert(active);

            var ref = newHosts(owner.getUserNo(), "active profile ref", "");
            ref.setVisibility("collabo");
            ref.setParentId(active.getHostsId());
            mapper.insert(ref);

            mapper.deleteOrphanedParentsByCreator(owner.getUserNo());

            assertThat(mapper.findByHostsId(orphan.getHostsId())).isNull();
            assertThat(mapper.findByHostsId(active.getHostsId())).isNotNull();
        }
    }

    @Test
    void searchOthers() {
        var searcher = insertUser("quinn");
        var other = insertUser("rose");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var pub = newHosts(other.getUserNo(), "public hosts", "content");
            pub.setVisibility("public");
            mapper.insert(pub);

            var results = mapper.searchOthers(searcher.getUserNo(), "public");
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getHostsProfile()).isEqualTo("public hosts");
        }
    }

    @Test
    void searchOthers_collaboBranch() {
        var searcher = insertUser("sam");
        var owner = insertUser("tara"); // collabo 부모의 실소유자 (user_no = -owner.userNo 로 저장됨)
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);

            // collabo 부모: user_no < 0, HUB_USR JOIN 대상 = owner
            var parent = newHosts(-owner.getUserNo(), "team hosts", "content");
            parent.setVisibility("collabo");
            mapper.insert(parent);

            // searcher가 아직 참여하지 않은 상태 → 검색에 노출
            var before = mapper.searchOthers(searcher.getUserNo(), "team");
            assertThat(before).hasSize(1);
            assertThat(before.get(0).getHostsProfile()).isEqualTo("team hosts");

            // searcher가 참여(ref 생성) → NOT EXISTS 조건으로 제외
            var ref = newHosts(searcher.getUserNo(), "team hosts", "");
            ref.setVisibility("collabo");
            ref.setParentId(parent.getHostsId());
            mapper.insert(ref);

            var after = mapper.searchOthers(searcher.getUserNo(), "team");
            assertThat(after).isEmpty();
        }
    }
}
