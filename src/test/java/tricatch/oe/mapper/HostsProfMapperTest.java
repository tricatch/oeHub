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
        h.setShareScope("workspace");
        // The real actor is always positive, even for a collabo parent row whose user_no is
        // stored negative (see HostsProfService.convertToCollabo) - mirror that here too.
        h.setCreatedBy(Math.abs(userNo));
        h.setUpdatedBy(Math.abs(userNo));
        h.setCreateAt(LocalDateTime.now());
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
            assertThat(found.getShareScope()).isEqualTo("workspace");
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
    void updateShareScope() {
        var user = insertUser("grace");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "profile", "content");
            mapper.insert(hosts);
            mapper.updateShareScope(hosts.getHostsId(), user.getUserNo(), "private", null, LocalDateTime.now());
            assertThat(mapper.findByHostsId(hosts.getHostsId()).getShareScope()).isEqualTo("private");
        }
    }

    @Test
    void wrappedContentKey_roundTripsThroughInsertAndUpdateShareScope() {
        // e2eEncryption design doc §3/§7 - the DEK wrap travels with the row and is re-wrapped
        // (not re-derived) on a share-scope flip.
        var user = insertUser("wrapKeyUser");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "wrapped profile", "content");
            hosts.setWrappedContentKey("wrapped-with-workspace-key");
            mapper.insert(hosts);
            assertThat(mapper.findByHostsId(hosts.getHostsId()).getWrappedContentKey()).isEqualTo("wrapped-with-workspace-key");

            mapper.updateShareScope(hosts.getHostsId(), user.getUserNo(), "private", "wrapped-with-personal-key", LocalDateTime.now());
            assertThat(mapper.findByHostsId(hosts.getHostsId()).getWrappedContentKey()).isEqualTo("wrapped-with-personal-key");
        }
    }

    @Test
    void wrappedContentKey_resolvesThroughCollaboParentLikeContentDoes() {
        // findByHostsId/findByUserNo already COALESCE hosts_content from the parent for a collabo
        // reference row - wrapped_content_key must follow the identical pattern, since the
        // reference row itself never holds its own key (HostsProfMapper.setAsCollaboRef nulls it).
        var owner = insertUser("collaboKeyOwner");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var parent = newHosts(-owner.getUserNo(), "shared profile", "content");
            parent.setShareScope("collabo");
            parent.setWrappedContentKey("wrapped-with-workspace-key");
            mapper.insert(parent);

            var ref = newHosts(owner.getUserNo(), "shared profile ref", "");
            ref.setShareScope("collabo");
            ref.setParentId(parent.getHostsId());
            ref.setWrappedContentKey(null);
            mapper.insert(ref);

            assertThat(mapper.findByHostsId(ref.getHostsId()).getWrappedContentKey()).isEqualTo("wrapped-with-workspace-key");
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
    void findProfileNamesByUserNo() {
        var user = insertUser("mia");
        var other = insertUser("noah");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            mapper.insert(newHosts(user.getUserNo(), "My Profile", "content"));
            mapper.insert(newHosts(user.getUserNo(), "Second Profile", "content"));
            mapper.insert(newHosts(other.getUserNo(), "Others Profile", "content"));
            assertThat(mapper.findProfileNamesByUserNo(user.getUserNo()))
                .containsExactlyInAnyOrder("My Profile", "Second Profile");
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
            parent.setShareScope("collabo");
            mapper.insert(parent);

            // 원본을 collabo 참조로 전환
            mapper.setAsCollaboRef(original.getHostsId(), owner.getUserNo(), parent.getHostsId(), LocalDateTime.now());

            // collab 사용자가 참조 등록
            var ref = newHosts(collab.getUserNo(), "shared profile", "");
            ref.setShareScope("collabo");
            ref.setParentId(parent.getHostsId());
            mapper.insert(ref);

            // 부모 컨텐츠 수정 → COALESCE로 두 참조 모두 새 컨텐츠 반영
            mapper.updateContentByParentId(parent.getHostsId(), collab.getUserNo(), "updated content", LocalDateTime.now());

            assertThat(mapper.findByHostsId(original.getHostsId()).getHostsContent()).isEqualTo("updated content");
            assertThat(mapper.findByHostsId(ref.getHostsId()).getHostsContent()).isEqualTo("updated content");

            // 소유자 표시는 collab이 수정해도 여전히 원 소유자(owner) — updated_by만 collab으로 반영
            assertThat(mapper.findByHostsId(original.getHostsId()).getUserId()).isEqualTo(owner.getUserId());
            assertThat(mapper.findByHostsId(ref.getHostsId()).getUpdatedByUserId()).isEqualTo(collab.getUserId());

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
            orphan.setShareScope("collabo");
            mapper.insert(orphan);

            // 활성 부모: 참조 있음 → 보존 대상
            var active = newHosts(-owner.getUserNo(), "active profile", "content");
            active.setShareScope("collabo");
            mapper.insert(active);

            var ref = newHosts(owner.getUserNo(), "active profile ref", "");
            ref.setShareScope("collabo");
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
            pub.setShareScope("workspace");
            mapper.insert(pub);

            var results = mapper.searchOthers(searcher.getUserNo(), TEST_WS_NO, "public");
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
            parent.setShareScope("collabo");
            mapper.insert(parent);

            // searcher가 아직 참여하지 않은 상태 → 검색에 노출
            var before = mapper.searchOthers(searcher.getUserNo(), TEST_WS_NO, "team");
            assertThat(before).hasSize(1);
            assertThat(before.get(0).getHostsProfile()).isEqualTo("team hosts");

            // searcher가 참여(ref 생성) → NOT EXISTS 조건으로 제외
            var ref = newHosts(searcher.getUserNo(), "team hosts", "");
            ref.setShareScope("collabo");
            ref.setParentId(parent.getHostsId());
            mapper.insert(ref);

            var after = mapper.searchOthers(searcher.getUserNo(), TEST_WS_NO, "team");
            assertThat(after).isEmpty();
        }
    }

    @Test
    void searchOthers_excludesOtherWorkspaces() {
        var searcher = insertUser("uma");
        try (var session = FACTORY.openSession(true)) {
            var wsMapper = session.getMapper(tricatch.oe.hub.mapper.WorkspaceMapper.class);
            var otherWs = new tricatch.oe.hub.model.Workspace();
            otherWs.setWsName("Other Workspace " + newId());
            otherWs.setStatus("active");
            var now = LocalDateTime.now();
            otherWs.setCreateAt(now);
            otherWs.setUpdatedAt(now);
            wsMapper.insert(otherWs);

            var otherUser = new tricatch.oe.hub.model.HubUser();
            otherUser.setUserId("outsider" + newId().substring(0, 8));
            otherUser.setPassword("hashed");
            otherUser.setRole("usr");
            otherUser.setWsNo(otherWs.getWsNo());
            otherUser.setCreateAt(now);
            otherUser.setUpdatedAt(now);
            session.getMapper(tricatch.oe.hub.mapper.HubUserMapper.class).insert(otherUser);

            var mapper = session.getMapper(HostsProfMapper.class);
            var pub = newHosts(otherUser.getUserNo(), "outsider public hosts", "content");
            pub.setShareScope("workspace");
            mapper.insert(pub);

            // Same keyword search from a user in TEST_WS_NO must not surface another workspace's
            // workspace-scoped profile, even though share_scope='workspace' (cloudGroupService
            // design doc §2.4).
            var results = mapper.searchOthers(searcher.getUserNo(), TEST_WS_NO, "outsider");
            assertThat(results).isEmpty();
        }
    }

    // Workspace-key rotation support (e2eEncryption design doc §7).
    @Test
    void findEncryptedRowsByWsNo_returnsOnlyEncryptedWorkspaceOrCollaboRowsInThatWorkspace() {
        var alice = insertUser("rotAlice");
        var bob = insertUser("rotBob");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);

            var alicePublic = newHosts(alice.getUserNo(), "alice public", "content");
            alicePublic.setShareScope("workspace");
            alicePublic.setWrappedContentKey("wrapped-alice");
            mapper.insert(alicePublic);

            // 'private' must never be included - rotation only touches workspace-key-wrapped rows.
            var alicePrivate = newHosts(alice.getUserNo(), "alice private", "content");
            alicePrivate.setShareScope("private");
            alicePrivate.setWrappedContentKey("wrapped-alice-private");
            mapper.insert(alicePrivate);

            // Unencrypted (self-hosted-style) row - wrappedContentKey left null.
            var bobPlain = newHosts(bob.getUserNo(), "bob plain", "content");
            bobPlain.setShareScope("workspace");
            mapper.insert(bobPlain);

            var bobCollaboParent = newHosts(-bob.getUserNo(), "bob collabo parent", "content");
            bobCollaboParent.setShareScope("collabo");
            bobCollaboParent.setWrappedContentKey("wrapped-bob-collabo");
            mapper.insert(bobCollaboParent);

            var ids = mapper.findEncryptedRowsByWsNo(TEST_WS_NO).stream()
                .map(HostsProf::getHostsId).collect(java.util.stream.Collectors.toSet());
            assertThat(ids).contains(alicePublic.getHostsId(), bobCollaboParent.getHostsId());
            assertThat(ids).doesNotContain(alicePrivate.getHostsId(), bobPlain.getHostsId());
        }
    }

    @Test
    void updateWrappedContentKeyForRotation_updatesInPlace_andIsScopedToWorkspace() {
        var alice = insertUser("rotAlice2");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var pub = newHosts(alice.getUserNo(), "alice public 2", "content");
            pub.setShareScope("workspace");
            pub.setWrappedContentKey("wrapped-old");
            mapper.insert(pub);

            mapper.updateWrappedContentKeyForRotation(pub.getHostsId(), TEST_WS_NO, "wrapped-new");
            assertThat(mapper.findByHostsId(pub.getHostsId()).getWrappedContentKey()).isEqualTo("wrapped-new");

            // A wsNo that doesn't own this row must be a no-op - proves the EXISTS/wsNo scope
            // actually guards the write, not just filters the read side.
            mapper.updateWrappedContentKeyForRotation(pub.getHostsId(), TEST_WS_NO + 999999, "wrapped-hacked");
            assertThat(mapper.findByHostsId(pub.getHostsId()).getWrappedContentKey()).isEqualTo("wrapped-new");
        }
    }

    // "Living link" redesign (e2eEncryption design doc §6): the link's own DEK is now wrapped by
    // the workspace key and stored alongside its ciphertext, so link_content/wrapped_link_key
    // travel together as a pair.
    @Test
    void updateLinkContent_issuesBothColumnsTogether() {
        var user = insertUser("linkIssueUser");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "linked profile", "content");
            mapper.insert(hosts);

            mapper.updateLinkContent(hosts.getHostsId(), TEST_WS_NO, "link-ciphertext", "wrapped-link-key");
            var found = mapper.findByHostsId(hosts.getHostsId());
            assertThat(found.getLinkContent()).isEqualTo("link-ciphertext");
            assertThat(found.getWrappedLinkKey()).isEqualTo("wrapped-link-key");

            // Revoke: both null together.
            mapper.updateLinkContent(hosts.getHostsId(), TEST_WS_NO, null, null);
            var revoked = mapper.findByHostsId(hosts.getHostsId());
            assertThat(revoked.getLinkContent()).isNull();
            assertThat(revoked.getWrappedLinkKey()).isNull();
        }
    }

    @Test
    void syncLinkContent_isNoOpWithoutALiveLink_andUpdatesWhenOneExists() {
        var user = insertUser("linkSyncUser");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "sync profile", "content");
            mapper.insert(hosts);

            // No link issued yet (wrapped_link_key IS NULL) - a content save must never mint one.
            mapper.syncLinkContent(hosts.getHostsId(), TEST_WS_NO, "should-not-be-stored");
            assertThat(mapper.findByHostsId(hosts.getHostsId()).getLinkContent()).isNull();

            mapper.updateLinkContent(hosts.getHostsId(), TEST_WS_NO, "initial-link", "wrapped-link-key");
            mapper.syncLinkContent(hosts.getHostsId(), TEST_WS_NO, "refreshed-link");
            var found = mapper.findByHostsId(hosts.getHostsId());
            assertThat(found.getLinkContent()).isEqualTo("refreshed-link");
            assertThat(found.getWrappedLinkKey()).isEqualTo("wrapped-link-key");
        }
    }

    @Test
    void clearLink_nullsBothColumns() {
        var user = insertUser("linkClearUser");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = newHosts(user.getUserNo(), "clear profile", "content");
            mapper.insert(hosts);
            mapper.updateLinkContent(hosts.getHostsId(), TEST_WS_NO, "link-ciphertext", "wrapped-link-key");

            mapper.clearLink(hosts.getHostsId());
            var found = mapper.findByHostsId(hosts.getHostsId());
            assertThat(found.getLinkContent()).isNull();
            assertThat(found.getWrappedLinkKey()).isNull();
        }
    }

    @Test
    void updateWrappedLinkKeyForRotation_onlyChangesRowsThatHaveALiveLink() {
        var user = insertUser("linkRotationUser");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var linked = newHosts(user.getUserNo(), "linked rotation profile", "content");
            mapper.insert(linked);
            mapper.updateLinkContent(linked.getHostsId(), TEST_WS_NO, "link-ciphertext", "wrapped-old");

            var unlinked = newHosts(user.getUserNo(), "unlinked rotation profile", "content");
            mapper.insert(unlinked);

            mapper.updateWrappedLinkKeyForRotation(linked.getHostsId(), TEST_WS_NO, "wrapped-new");
            assertThat(mapper.findByHostsId(linked.getHostsId()).getWrappedLinkKey()).isEqualTo("wrapped-new");

            // No live link on this row - rotation must not create one.
            mapper.updateWrappedLinkKeyForRotation(unlinked.getHostsId(), TEST_WS_NO, "wrapped-hacked");
            assertThat(mapper.findByHostsId(unlinked.getHostsId()).getWrappedLinkKey()).isNull();
        }
    }
}
