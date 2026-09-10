package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HubUserMapperTest extends MapperTestBase {

    @Test
    void insertAndFindByUserNo() {
        var now = LocalDateTime.now();
        var user = new HubUser();
        user.setUserId("alice");
        user.setPassword("hash");
        user.setRole("usr");
        user.setWsNo(TEST_WS_NO);
        user.setCreateAt(now);
        user.setUpdatedAt(now);

        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.insert(user);

            assertThat(user.getUserNo()).isNotNull();
            var found = mapper.findByUserNo(user.getUserNo());
            assertThat(found.getUserId()).isEqualTo("alice");
            assertThat(found.getRole()).isEqualTo("usr");
            assertThat(found.getCreateAt()).isNotNull();
            assertThat(found.getUpdatedAt()).isNotNull();
            assertThat(found.getLastLoginAt()).isNull();
        }
    }

    @Test
    void findByUserId() {
        var user = insertUser("bob");
        try (var session = FACTORY.openSession()) {
            var found = session.getMapper(HubUserMapper.class).findByUserId("bob");
            assertThat(found).isNotNull();
            assertThat(found.getUserNo()).isEqualTo(user.getUserNo());
        }
    }

    @Test
    void findByUserId_notFound() {
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(HubUserMapper.class).findByUserId("nobody")).isNull();
        }
    }

    @Test
    void findAll() {
        insertUser("u1");
        insertUser("u2");
        insertUser("u3");
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(HubUserMapper.class).findAll(TEST_WS_NO)).hasSize(3);
        }
    }

    @Test
    void searchByUserId() {
        insertUser("alice");
        insertUser("alicia");
        insertUser("bob");
        try (var session = FACTORY.openSession()) {
            var results = session.getMapper(HubUserMapper.class).searchByUserId(TEST_WS_NO, "ali");
            assertThat(results).extracting(HubUser::getUserId)
                .containsExactlyInAnyOrder("alice", "alicia");
        }
    }

    @Test
    void updateRole() {
        var user = insertUser("carol");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            user.setRole("adm");
            user.setUpdatedAt(LocalDateTime.now());
            mapper.updateRole(user);
            assertThat(mapper.findByUserNo(user.getUserNo()).getRole()).isEqualTo("adm");
        }
    }

    @Test
    void insert_withCryptoColumns_roundTrips() {
        var now = LocalDateTime.now();
        var user = new HubUser();
        user.setUserId("cryptouser");
        user.setPassword("hash");
        user.setRole("usr");
        user.setWsNo(TEST_WS_NO);
        user.setPublicKey("pub-key-b64");
        user.setWrappedPrivateKey("wrapped-priv-b64");
        user.setWrappedPrivateKeyRecovery("wrapped-priv-recovery-b64");
        user.setCreateAt(now);
        user.setUpdatedAt(now);

        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.insert(user);

            var found = mapper.findByUserNo(user.getUserNo());
            assertThat(found.getPublicKey()).isEqualTo("pub-key-b64");
            assertThat(found.getWrappedPrivateKey()).isEqualTo("wrapped-priv-b64");
            assertThat(found.getWrappedPrivateKeyRecovery()).isEqualTo("wrapped-priv-recovery-b64");
        }
    }

    @Test
    void insert_withoutCryptoColumns_leavesThemNull() {
        // The common case today - nothing wires key generation into signup yet.
        var user = insertUser("nocrypto");
        try (var session = FACTORY.openSession()) {
            var found = session.getMapper(HubUserMapper.class).findByUserNo(user.getUserNo());
            assertThat(found.getPublicKey()).isNull();
            assertThat(found.getWrappedPrivateKey()).isNull();
            assertThat(found.getWrappedPrivateKeyRecovery()).isNull();
        }
    }

    @Test
    void updatePasswordAndRewrapPrivateKey_updatesBothAndBumpsTokenVersion() {
        var user = insertUser("rewrapuser");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            user.setPassword("new-hash");
            user.setWrappedPrivateKey("new-wrapped-priv-b64");
            user.setUpdatedBy(user.getUserNo());
            user.setUpdatedAt(LocalDateTime.now());
            mapper.updatePasswordAndRewrapPrivateKey(user);

            var found = mapper.findByUserNo(user.getUserNo());
            assertThat(found.getPassword()).isEqualTo("new-hash");
            assertThat(found.getWrappedPrivateKey()).isEqualTo("new-wrapped-priv-b64");
            assertThat(found.getTokenVersion()).isEqualTo(1);
        }
    }

    @Test
    void updateWrappedPrivateKeyRecovery_updatesOnlyThatColumn() {
        var user = insertUser("reissueuser");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            var originalPassword = user.getPassword();
            user.setWrappedPrivateKeyRecovery("reissued-recovery-wrap-b64");
            user.setUpdatedBy(user.getUserNo());
            user.setUpdatedAt(LocalDateTime.now());
            mapper.updateWrappedPrivateKeyRecovery(user);

            var found = mapper.findByUserNo(user.getUserNo());
            assertThat(found.getWrappedPrivateKeyRecovery()).isEqualTo("reissued-recovery-wrap-b64");
            assertThat(found.getPassword()).isEqualTo(originalPassword);
        }
    }

    @Test
    void updateLastLoginAt() {
        var user = insertUser("dave");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            user.setLastLoginAt(LocalDateTime.now());
            mapper.updateLastLoginAt(user);
            assertThat(mapper.findByUserNo(user.getUserNo()).getLastLoginAt()).isNotNull();
        }
    }

    @Test
    void findAll_excludesPendingAndWsSystem() {
        insertUser("u1");
        var pending = insertUser("u2");
        pending.setRole("pending");
        pending.setUpdatedAt(LocalDateTime.now());
        var wsSystem = insertUser("u3");
        wsSystem.setRole("ws_system");
        wsSystem.setUpdatedAt(LocalDateTime.now());
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.updateRole(pending);
            mapper.updateRole(wsSystem);

            var all = mapper.findAll(TEST_WS_NO);
            assertThat(all).extracting(HubUser::getUserId).containsExactly("u1");
        }
    }

    @Test
    void searchByUserId_excludesPendingAndWsSystem() {
        insertUser("alice");
        var pending = insertUser("alicia");
        pending.setRole("pending");
        pending.setUpdatedAt(LocalDateTime.now());
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.updateRole(pending);

            var results = mapper.searchByUserId(TEST_WS_NO, "ali");
            assertThat(results).extracting(HubUser::getUserId).containsExactly("alice");
        }
    }

    @Test
    void findAll_excludesUsersFromOtherWorkspaces() {
        insertUser("sameWorkspace");
        try (var session = FACTORY.openSession(true)) {
            var wsMapper = session.getMapper(tricatch.oe.hub.mapper.WorkspaceMapper.class);
            var otherWs = new tricatch.oe.hub.model.Workspace();
            otherWs.setWsName("Other Workspace " + newId());
            otherWs.setStatus("active");
            var now = LocalDateTime.now();
            otherWs.setCreateAt(now);
            otherWs.setUpdatedAt(now);
            wsMapper.insert(otherWs);

            var otherUser = new HubUser();
            otherUser.setUserId("otherWorkspace");
            otherUser.setPassword("hashed");
            otherUser.setRole("usr");
            otherUser.setWsNo(otherWs.getWsNo());
            otherUser.setCreateAt(now);
            otherUser.setUpdatedAt(now);
            session.getMapper(HubUserMapper.class).insert(otherUser);

            var mapper = session.getMapper(HubUserMapper.class);
            assertThat(mapper.findAll(TEST_WS_NO)).extracting(HubUser::getUserId).containsExactly("sameWorkspace");
            assertThat(mapper.searchByUserId(TEST_WS_NO, "other")).isEmpty();
        }
    }

    @Test
    void findAllPendingByWsNo_returnsOnlyPendingInThatWorkspace() {
        var pending = insertUser("waiting");
        pending.setRole("pending");
        pending.setUpdatedAt(LocalDateTime.now());
        insertUser("active");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.updateRole(pending);

            var result = mapper.findAllPendingByWsNo(TEST_WS_NO);
            assertThat(result).extracting(HubUser::getUserId).containsExactly("waiting");
        }
    }

    @Test
    void deleteByUserNo() {
        var user = insertUser("eve");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.deleteByUserNo(user.getUserNo());
            assertThat(mapper.findByUserNo(user.getUserNo())).isNull();
        }
    }
}
