package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.model.HubConf;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HubConfMapperTest extends MapperTestBase {

    private HubConf conf(String key, String val) {
        var c = new HubConf();
        c.setConfKey(key);
        c.setConfVal(val);
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    @Test
    void upsertInsert() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubConfMapper.class);
            mapper.upsert(conf("admin", "alice"));
            var found = mapper.findByConfKey("admin");
            assertThat(found).isNotNull();
            assertThat(found.getConfVal()).isEqualTo("alice");
            assertThat(found.getUpdatedAt()).isNotNull();
        }
    }

    @Test
    void upsertUpdate() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubConfMapper.class);
            mapper.upsert(conf("admin", "alice"));
            mapper.upsert(conf("admin", "bob"));
            assertThat(mapper.findByConfKey("admin").getConfVal()).isEqualTo("bob");
        }
    }

    @Test
    void findByConfKey_notFound() {
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(HubConfMapper.class).findByConfKey("missing")).isNull();
        }
    }
}
