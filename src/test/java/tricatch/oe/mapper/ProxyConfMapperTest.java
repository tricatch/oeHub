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
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    @Test
    void insertGlobalAndFind() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(ProxyConfMapper.class);
            mapper.insert(conf(null, "proxy.port", "8080"));
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
            mapper.insert(conf(user.getUserNo(), "proxy.mode", "transparent"));
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
            mapper.insert(c);
            c.setConfVal("9090");
            c.setUpdatedAt(LocalDateTime.now());
            mapper.update(c);
            assertThat(mapper.findGlobal("proxy.port").getConfVal()).isEqualTo("9090");
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
            mapper.insert(conf(user.getUserNo(), "k1", "v1"));
            mapper.insert(conf(user.getUserNo(), "k2", "v2"));
            mapper.deleteAllByUserNo(user.getUserNo());
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "k1")).isNull();
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "k2")).isNull();
        }
    }
}
