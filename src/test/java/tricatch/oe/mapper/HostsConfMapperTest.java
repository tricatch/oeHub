package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.model.HostsConf;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HostsConfMapperTest extends MapperTestBase {

    private HostsConf conf(Long userNo, String key, String val) {
        var c = new HostsConf();
        c.setUserNo(userNo);
        c.setConfKey(key);
        c.setConfVal(val);
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    @Test
    void upsertAndFind() {
        var user = insertUser("alice");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsConfMapper.class);
            mapper.upsert(conf(user.getUserNo(), "theme", "dark"));
            var found = mapper.findByUserNoAndConfKey(user.getUserNo(), "theme");
            assertThat(found).isNotNull();
            assertThat(found.getConfVal()).isEqualTo("dark");
            assertThat(found.getUpdatedAt()).isNotNull();
        }
    }

    @Test
    void upsertUpdate() {
        var user = insertUser("bob");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsConfMapper.class);
            mapper.upsert(conf(user.getUserNo(), "theme", "dark"));
            mapper.upsert(conf(user.getUserNo(), "theme", "light"));
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "theme").getConfVal()).isEqualTo("light");
        }
    }

    @Test
    void findByUserNoAndConfKey_notFound() {
        var user = insertUser("carol");
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(HostsConfMapper.class).findByUserNoAndConfKey(user.getUserNo(), "missing")).isNull();
        }
    }

    @Test
    void deleteAllByUserNo() {
        var user = insertUser("dave");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsConfMapper.class);
            mapper.upsert(conf(user.getUserNo(), "k1", "v1"));
            mapper.upsert(conf(user.getUserNo(), "k2", "v2"));
            mapper.deleteAllByUserNo(user.getUserNo());
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "k1")).isNull();
            assertThat(mapper.findByUserNoAndConfKey(user.getUserNo(), "k2")).isNull();
        }
    }
}
