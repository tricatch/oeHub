package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.WsKeyMapper;
import tricatch.oe.hub.model.WsKey;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WsKeyMapperTest extends MapperTestBase {

    private WsKey wsKey(Long wsNo, Long userNo, Long actor) {
        var now = LocalDateTime.now();
        var k = new WsKey();
        k.setWsNo(wsNo);
        k.setUserNo(userNo);
        k.setWrappedWsKey("wrapped-" + newId());
        k.setCreatedBy(actor);
        k.setUpdatedBy(actor);
        k.setCreateAt(now);
        k.setUpdatedAt(now);
        return k;
    }

    @Test
    void insertAndFind() {
        var founder = insertUser("wskeyFounder");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsKeyMapper.class);
            var key = wsKey(TEST_WS_NO, founder.getUserNo(), founder.getUserNo());
            mapper.insert(key);

            var found = mapper.findByWsNoAndUserNo(TEST_WS_NO, founder.getUserNo());
            assertThat(found).isNotNull();
            assertThat(found.getWrappedWsKey()).isEqualTo(key.getWrappedWsKey());
        }
    }

    @Test
    void findByWsNoAndUserNo_notFound() {
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(WsKeyMapper.class).findByWsNoAndUserNo(TEST_WS_NO, -1L)).isNull();
        }
    }

    // Workspace-key rotation support (e2eEncryption design doc §7).
    @Test
    void updateWrappedWsKey_replacesTheWrapInPlace_withoutChangingIdentity() {
        var member = insertUser("wskeyRotate");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsKeyMapper.class);
            mapper.insert(wsKey(TEST_WS_NO, member.getUserNo(), member.getUserNo()));

            var before = mapper.findByWsNoAndUserNo(TEST_WS_NO, member.getUserNo());
            var rotatedAt = LocalDateTime.now();
            mapper.updateWrappedWsKey(TEST_WS_NO, member.getUserNo(), "wrapped-after-rotation", member.getUserNo(), rotatedAt);

            var after = mapper.findByWsNoAndUserNo(TEST_WS_NO, member.getUserNo());
            assertThat(after.getWrappedWsKey()).isEqualTo("wrapped-after-rotation");
            assertThat(after.getWrappedWsKey()).isNotEqualTo(before.getWrappedWsKey());
        }
    }

    @Test
    void deleteByUserNo_removesOnlyThatUsersWrap() {
        var member = insertUser("wskeyMember");
        var other = insertUser("wskeyOther");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsKeyMapper.class);
            mapper.insert(wsKey(TEST_WS_NO, member.getUserNo(), member.getUserNo()));
            mapper.insert(wsKey(TEST_WS_NO, other.getUserNo(), other.getUserNo()));

            mapper.deleteByUserNo(member.getUserNo());

            assertThat(mapper.findByWsNoAndUserNo(TEST_WS_NO, member.getUserNo())).isNull();
            assertThat(mapper.findByWsNoAndUserNo(TEST_WS_NO, other.getUserNo())).isNotNull();
        }
    }

    // Regression test for the AdminUserController.apiDeleteUser fix - HUB_WS_KEY has a real
    // (not soft) FK on user_no (e2eEncryption design doc §3/§9), so deleting a user who still
    // has a key-wrap row must fail unless that row is cleaned up first.
    @Test
    void deletingUserWithWsKeyRow_failsUnlessWsKeyDeletedFirst() {
        var user = insertUser("wskeyDeleteOrder");
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(WsKeyMapper.class).insert(wsKey(TEST_WS_NO, user.getUserNo(), user.getUserNo()));
        }

        try (var session = FACTORY.openSession(true)) {
            var userMapper = session.getMapper(HubUserMapper.class);
            assertThatThrownBy(() -> userMapper.deleteByUserNo(user.getUserNo()))
                    .as("HUB_WS_KEY's FK on user_no should block this until the wrap row is removed")
                    .isInstanceOf(RuntimeException.class);
        }

        try (var session = FACTORY.openSession(true)) {
            session.getMapper(WsKeyMapper.class).deleteByUserNo(user.getUserNo());
            session.getMapper(HubUserMapper.class).deleteByUserNo(user.getUserNo());
            assertThat(session.getMapper(HubUserMapper.class).findByUserNo(user.getUserNo())).isNull();
        }
    }
}
