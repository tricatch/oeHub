package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.mapper.ProxyConfMapper;
import tricatch.oe.proxy.model.ProxyConf;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyConfMapperTest extends MapperTestBase {

    private ProxyConf conf(Long userNo, String key, String val) {
        var c = new ProxyConf();
        c.setUserNo(userNo);
        c.setConfKey(key);
        c.setConfVal(val);
        var actor = userNo != null ? userNo : 0L;
        c.setCreatedBy(actor);
        c.setUpdatedBy(actor);
        c.setCreateAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    @Test
    void insertGlobalAndFind() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            mapper.upsert(conf(null, "proxy.port", "8080"));
            var found = mapper.findGlobal("proxy.port");
            assertThat(found).isNotNull();
            assertThat(found.getConfVal()).isEqualTo("8080");
            assertThat(found.getUserNo()).isNull();
        }
    }

    @Test
    void insertUserConfAndFind() {
        var user = insertUser("alice");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            mapper.upsert(conf(user.getUserNo(), "proxy.mode", "transparent"));
            var found = mapper.findByUserNoAndConfKey(user.getUserNo(), "proxy.mode");
            assertThat(found).isNotNull();
            assertThat(found.getConfVal()).isEqualTo("transparent");
            assertThat(found.getUserNo()).isEqualTo(user.getUserNo());
        }
    }

    @Test
    void updateConf() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            var c = conf(null, "proxy.port", "8080");
            mapper.upsert(c);
            c.setConfVal("9090");
            c.setUpdatedAt(LocalDateTime.now());
            mapper.upsert(c);
            assertThat(mapper.findGlobal("proxy.port").getConfVal()).isEqualTo("9090");
        }
    }

    // Regression test for the NULL-key MERGE pitfall: H2's shorthand
    // "MERGE INTO t (cols) KEY(cols) VALUES(...)" treats NULL != NULL and always inserts,
    // which would silently duplicate the global row instead of updating it.
    @Test
    void upsertGlobal_repeatedCallsUpdateInPlace_doNotDuplicate() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            mapper.upsert(conf(null, "proxy.port", "8080"));
            mapper.upsert(conf(null, "proxy.port", "8443"));
            mapper.upsert(conf(null, "proxy.port", "9090"));
            assertThat(mapper.findGlobal("proxy.port").getConfVal()).isEqualTo("9090");
        }
    }

    // A per-user upsert for the same conf_key must not collide with the global (user_no IS NULL)
    // row for that key, and vice versa - the NULL-safe MERGE match must stay scoped by user_no.
    @Test
    void upsertUser_doesNotCollideWithGlobalRowForSameKey() {
        var user = insertUser("carol");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            mapper.upsert(conf(null, "proxy.port", "8080"));
            mapper.upsert(conf(user.getUserNo(), "proxy.port", "3000"));
            assertThat(mapper.findGlobal("proxy.port").getConfVal()).isEqualTo("8080");
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "proxy.port").getConfVal()).isEqualTo("3000");
        }
    }

    @Test
    void findGlobal_notFound() {
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(ProxyConfMapper.class).findGlobal("missing")).isNull();
        }
    }

    @Test
    void deleteAllByUserNo() {
        var user = insertUser("bob");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            mapper.upsert(conf(user.getUserNo(), "k1", "v1"));
            mapper.upsert(conf(user.getUserNo(), "k2", "v2"));
            mapper.deleteAllByUserNo(user.getUserNo());
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "k1")).isNull();
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "k2")).isNull();
        }
    }
}
