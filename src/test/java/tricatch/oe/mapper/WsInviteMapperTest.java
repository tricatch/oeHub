package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.mapper.WsInviteMapper;
import tricatch.oe.hub.model.WsInvite;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WsInviteMapperTest extends MapperTestBase {

    private WsInvite invite(String code, Long wsNo, Long createdBy, LocalDateTime expiresAt) {
        var now = LocalDateTime.now();
        var i = new WsInvite();
        i.setInviteCode(code);
        i.setWsNo(wsNo);
        i.setCreatedBy(createdBy);
        i.setUpdatedBy(createdBy);
        i.setCreateAt(now);
        i.setUpdatedAt(now);
        i.setExpiresAt(expiresAt);
        return i;
    }

    @Test
    void insertAndFindByCode() {
        var founder = insertUser("founder1");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsInviteMapper.class);
            var code = newId().substring(0, 12);
            mapper.insert(invite(code, TEST_WS_NO, founder.getUserNo(), LocalDateTime.now().plusDays(7)));

            var found = mapper.findByCode(code);
            assertThat(found).isNotNull();
            assertThat(found.getWsNo()).isEqualTo(TEST_WS_NO);
            assertThat(found.isUsed()).isFalse();
            assertThat(found.getCreatedBy()).isEqualTo(founder.getUserNo());
        }
    }

    @Test
    void findByCode_notFound() {
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(WsInviteMapper.class).findByCode("no-such-code")).isNull();
        }
    }

    @Test
    void consume_marksUsedAndSetsUpdatedBy() {
        var founder = insertUser("founder2");
        var newUser = insertUser("newjoiner");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsInviteMapper.class);
            var code = newId().substring(0, 12);
            mapper.insert(invite(code, TEST_WS_NO, founder.getUserNo(), LocalDateTime.now().plusDays(7)));

            var now = LocalDateTime.now();
            var affected = mapper.consume(code, newUser.getUserNo(), now);
            assertThat(affected).isEqualTo(1);

            var found = mapper.findByCode(code);
            assertThat(found.isUsed()).isTrue();
            assertThat(found.getUpdatedBy()).isEqualTo(newUser.getUserNo());
        }
    }

    @Test
    void consume_alreadyUsed_affectsZeroRows() {
        var founder = insertUser("founder3");
        var user1 = insertUser("joiner1");
        var user2 = insertUser("joiner2");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsInviteMapper.class);
            var code = newId().substring(0, 12);
            mapper.insert(invite(code, TEST_WS_NO, founder.getUserNo(), LocalDateTime.now().plusDays(7)));

            var now = LocalDateTime.now();
            assertThat(mapper.consume(code, user1.getUserNo(), now)).isEqualTo(1);
            // Second consume attempt (simulating a race or reuse) must not succeed.
            assertThat(mapper.consume(code, user2.getUserNo(), now)).isEqualTo(0);
        }
    }

    @Test
    void consume_expired_affectsZeroRows() {
        var founder = insertUser("founder4");
        var newUser = insertUser("joiner4");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsInviteMapper.class);
            var code = newId().substring(0, 12);
            mapper.insert(invite(code, TEST_WS_NO, founder.getUserNo(), LocalDateTime.now().minusDays(1)));

            assertThat(mapper.consume(code, newUser.getUserNo(), LocalDateTime.now())).isEqualTo(0);
        }
    }

    @Test
    void findOutstandingByWsNo_excludesUsedAndExpired() {
        var founder = insertUser("founder5");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WsInviteMapper.class);
            var now = LocalDateTime.now();

            var outstandingCode = newId().substring(0, 12);
            mapper.insert(invite(outstandingCode, TEST_WS_NO, founder.getUserNo(), now.plusDays(7)));

            var expiredCode = newId().substring(0, 12);
            mapper.insert(invite(expiredCode, TEST_WS_NO, founder.getUserNo(), now.minusDays(1)));

            var usedCode = newId().substring(0, 12);
            mapper.insert(invite(usedCode, TEST_WS_NO, founder.getUserNo(), now.plusDays(7)));
            var consumer = insertUser("consumer5");
            mapper.consume(usedCode, consumer.getUserNo(), now);

            var result = mapper.findOutstandingByWsNo(TEST_WS_NO, now);
            assertThat(result).extracting(WsInvite::getInviteCode).contains(outstandingCode)
                    .doesNotContain(expiredCode, usedCode);
        }
    }
}
