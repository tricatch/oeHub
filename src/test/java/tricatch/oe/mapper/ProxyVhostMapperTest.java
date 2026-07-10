package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.mapper.ProxyVhostMapper;
import tricatch.oe.proxy.model.ProxyVhost;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyVhostMapperTest extends MapperTestBase {

    private ProxyVhost newVhost(Long userNo, String profile, String content) {
        var v = new ProxyVhost();
        v.setVhostId(newId());
        v.setUserNo(userNo);
        v.setVhostProfile(profile);
        v.setVhostContent(content);
        v.setSelected(false);
        v.setSortOrder(0);
        v.setVisibility("public");
        v.setUpdatedAt(LocalDateTime.now());
        return v;
    }

    @Test
    void insertAndFindByVhostId() {
        var user = insertUser("alice");
        var vhost = newVhost(user.getUserNo(), "my vhost", "server: {}");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            mapper.insert(vhost);
            var found = mapper.findByVhostId(vhost.getVhostId());
            assertThat(found).isNotNull();
            assertThat(found.getVhostProfile()).isEqualTo("my vhost");
            assertThat(found.getVhostContent()).isEqualTo("server: {}");
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
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var v1 = newVhost(user.getUserNo(), "v1", "c1");
            v1.setSortOrder(0);
            var v2 = newVhost(user.getUserNo(), "v2", "c2");
            v2.setSortOrder(1);
            mapper.insert(v1);
            mapper.insert(v2);
            var list = mapper.findByUserNo(user.getUserNo());
            assertThat(list).hasSize(2);
            assertThat(list.get(0).getVhostProfile()).isEqualTo("v1");
            assertThat(list.get(1).getVhostProfile()).isEqualTo("v2");
        }
    }

    @Test
    void updateContent() {
        var user = insertUser("carol");
        var insertTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user.getUserNo(), "profile", "old");
            vhost.setUpdatedAt(insertTime);
            mapper.insert(vhost);

            var updateTime = insertTime.plusSeconds(1);
            mapper.updateContent(vhost.getVhostId(), user.getUserNo(), "new content", updateTime);

            var found = mapper.findByVhostId(vhost.getVhostId());
            assertThat(found.getVhostContent()).isEqualTo("new content");
            assertThat(found.getUpdatedAt()).isEqualTo(updateTime);
        }
    }

    @Test
    void updateProfile() {
        var user = insertUser("dave");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user.getUserNo(), "old", "content");
            mapper.insert(vhost);
            mapper.updateProfile(vhost.getVhostId(), user.getUserNo(), "new", LocalDateTime.now());
            assertThat(mapper.findByVhostId(vhost.getVhostId()).getVhostProfile()).isEqualTo("new");
        }
    }

    @Test
    void updateSelected() {
        var user = insertUser("eve");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user.getUserNo(), "profile", "content");
            mapper.insert(vhost);
            mapper.updateSelected(vhost.getVhostId(), user.getUserNo(), true, LocalDateTime.now());
            assertThat(mapper.findByVhostId(vhost.getVhostId()).isSelected()).isTrue();
        }
    }

    @Test
    void findSelectedByUserNo() {
        var user = insertUser("frank");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var v1 = newVhost(user.getUserNo(), "v1", "c1");
            var v2 = newVhost(user.getUserNo(), "v2", "c2");
            mapper.insert(v1);
            mapper.insert(v2);
            mapper.updateSelected(v2.getVhostId(), user.getUserNo(), true, LocalDateTime.now());
            var selected = mapper.findSelectedByUserNo(user.getUserNo());
            assertThat(selected).isNotNull();
            assertThat(selected.getVhostProfile()).isEqualTo("v2");
        }
    }

    @Test
    void findAllSelectedByUserNo() {
        var user = insertUser("grace");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var v1 = newVhost(user.getUserNo(), "v1", "c1");
            var v2 = newVhost(user.getUserNo(), "v2", "c2");
            var v3 = newVhost(user.getUserNo(), "v3", "c3");
            mapper.insert(v1);
            mapper.insert(v2);
            mapper.insert(v3);
            mapper.updateSelected(v1.getVhostId(), user.getUserNo(), true, LocalDateTime.now());
            mapper.updateSelected(v3.getVhostId(), user.getUserNo(), true, LocalDateTime.now());
            assertThat(mapper.findAllSelectedByUserNo(user.getUserNo())).hasSize(2);
        }
    }

    @Test
    void updateSortOrder() {
        var user = insertUser("heidi");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user.getUserNo(), "profile", "content");
            mapper.insert(vhost);
            mapper.updateSortOrder(vhost.getVhostId(), user.getUserNo(), 99);
            assertThat(mapper.findByVhostId(vhost.getVhostId()).getSortOrder()).isEqualTo(99);
        }
    }

    @Test
    void updateVisibility() {
        var user = insertUser("ivan");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user.getUserNo(), "profile", "content");
            mapper.insert(vhost);
            mapper.updateVisibility(vhost.getVhostId(), user.getUserNo(), "private", LocalDateTime.now());
            assertThat(mapper.findByVhostId(vhost.getVhostId()).getVisibility()).isEqualTo("private");
        }
    }

    @Test
    void delete() {
        var user = insertUser("judy");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user.getUserNo(), "profile", "content");
            mapper.insert(vhost);
            mapper.delete(vhost.getVhostId(), user.getUserNo());
            assertThat(mapper.findByVhostId(vhost.getVhostId())).isNull();
        }
    }

    @Test
    void delete_ignoresWrongUser() {
        var user1 = insertUser("kate");
        var user2 = insertUser("leo");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user1.getUserNo(), "profile", "content");
            mapper.insert(vhost);
            mapper.delete(vhost.getVhostId(), user2.getUserNo()); // 다른 사용자로 삭제 시도
            assertThat(mapper.findByVhostId(vhost.getVhostId())).isNotNull();
        }
    }

    @Test
    void deleteByVhostId() {
        var user = insertUser("mia");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var vhost = newVhost(user.getUserNo(), "profile", "content");
            mapper.insert(vhost);
            mapper.deleteByVhostId(vhost.getVhostId());
            assertThat(mapper.findByVhostId(vhost.getVhostId())).isNull();
        }
    }

    @Test
    void deleteByUserNo() {
        var user = insertUser("nina");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            mapper.insert(newVhost(user.getUserNo(), "v1", "c1"));
            mapper.insert(newVhost(user.getUserNo(), "v2", "c2"));
            mapper.deleteByUserNo(user.getUserNo());
            assertThat(mapper.findByUserNo(user.getUserNo())).isEmpty();
        }
    }

    @Test
    void countByUserNoAndProfile() {
        var user = insertUser("oscar");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            mapper.insert(newVhost(user.getUserNo(), "My Vhost", "content"));
            assertThat(mapper.countByUserNoAndProfile(user.getUserNo(), "My Vhost")).isEqualTo(1);
            assertThat(mapper.countByUserNoAndProfile(user.getUserNo(), "my vhost")).isEqualTo(1);
            assertThat(mapper.countByUserNoAndProfile(user.getUserNo(), "other")).isEqualTo(0);
        }
    }

    @Test
    void collaboFlow() {
        var owner = insertUser("petra");
        var collab = insertUser("quinn");

        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);

            // owner의 원본 엔트리
            var original = newVhost(owner.getUserNo(), "shared vhost", "original: true");
            mapper.insert(original);

            // collabo 부모 (user_no < 0)
            var parent = newVhost(-owner.getUserNo(), "shared vhost", "original: true");
            parent.setVisibility("collabo");
            mapper.insert(parent);

            // 원본을 collabo 참조로 전환
            mapper.setAsCollaboRef(original.getVhostId(), owner.getUserNo(), parent.getVhostId(), LocalDateTime.now());

            // collab 사용자가 참조 등록
            var ref = newVhost(collab.getUserNo(), "shared vhost", "");
            ref.setVisibility("collabo");
            ref.setParentId(parent.getVhostId());
            mapper.insert(ref);

            // 부모 컨텐츠 수정 → COALESCE로 두 참조 모두 새 컨텐츠 반영
            mapper.updateContentByParentId(parent.getVhostId(), collab.getUserNo(), "updated: true", LocalDateTime.now());

            assertThat(mapper.findByVhostId(original.getVhostId()).getVhostContent()).isEqualTo("updated: true");
            assertThat(mapper.findByVhostId(ref.getVhostId()).getVhostContent()).isEqualTo("updated: true");

            // owner의 참조 목록
            var ownerRefs = mapper.findReferencesByUserNo(owner.getUserNo());
            assertThat(ownerRefs).hasSize(1);
            assertThat(ownerRefs.get(0).getParentId()).isEqualTo(parent.getVhostId());

            // collab 사용자의 참조 목록
            var collabRefs = mapper.findReferencesByUserNo(collab.getUserNo());
            assertThat(collabRefs).hasSize(1);
            assertThat(collabRefs.get(0).getParentId()).isEqualTo(parent.getVhostId());

            // 전체 참조 수: owner + collab = 2
            assertThat(mapper.countReferencesByParentId(parent.getVhostId())).isEqualTo(2);

            // collab 참조 삭제 → 참조 수 1로 감소
            mapper.delete(ref.getVhostId(), collab.getUserNo());
            assertThat(mapper.countReferencesByParentId(parent.getVhostId())).isEqualTo(1);
        }
    }

    @Test
    void deleteOrphanedParentsByCreator() {
        var owner = insertUser("rose");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);

            // 고아 부모: 참조 없음 → 삭제 대상
            var orphan = newVhost(-owner.getUserNo(), "orphan", "content");
            orphan.setVisibility("collabo");
            mapper.insert(orphan);

            // 활성 부모: 참조 있음 → 보존 대상
            var active = newVhost(-owner.getUserNo(), "active", "content");
            active.setVisibility("collabo");
            mapper.insert(active);

            var ref = newVhost(owner.getUserNo(), "active ref", "");
            ref.setVisibility("collabo");
            ref.setParentId(active.getVhostId());
            mapper.insert(ref);

            mapper.deleteOrphanedParentsByCreator(owner.getUserNo());

            assertThat(mapper.findByVhostId(orphan.getVhostId())).isNull();
            assertThat(mapper.findByVhostId(active.getVhostId())).isNotNull();
        }
    }

    @Test
    void searchOthers() {
        var searcher = insertUser("sam");
        var other = insertUser("tara");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);
            var pub = newVhost(other.getUserNo(), "public vhost", "content");
            pub.setVisibility("public");
            mapper.insert(pub);

            var results = mapper.searchOthers(searcher.getUserNo(), "public");
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getVhostProfile()).isEqualTo("public vhost");
        }
    }

    @Test
    void searchOthers_collaboBranch() {
        var searcher = insertUser("uma");
        var owner = insertUser("vera"); // collabo 부모의 실소유자
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyVhostMapper.class);

            // collabo 부모: user_no < 0, visibility='collabo'
            var parent = newVhost(-owner.getUserNo(), "team vhost", "content");
            parent.setVisibility("collabo");
            mapper.insert(parent);

            // searcher가 아직 참여하지 않은 상태 → 검색에 노출
            var before = mapper.searchOthers(searcher.getUserNo(), "team");
            assertThat(before).hasSize(1);
            assertThat(before.get(0).getVhostProfile()).isEqualTo("team vhost");

            // searcher가 참여(ref 생성) → NOT EXISTS 조건으로 제외
            var ref = newVhost(searcher.getUserNo(), "team vhost", "");
            ref.setVisibility("collabo");
            ref.setParentId(parent.getVhostId());
            mapper.insert(ref);

            var after = mapper.searchOthers(searcher.getUserNo(), "team");
            assertThat(after).isEmpty();
        }
    }
}
