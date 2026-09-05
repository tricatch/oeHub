package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.model.HostsUa;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HostsUaMapperTest extends MapperTestBase {

    private HostsUa ua(String id, String name, Long userNo) {
        var now = LocalDateTime.now();
        var u = new HostsUa();
        u.setUaId(id);
        u.setUaName(name);
        u.setUaValue("Mozilla/5.0 (" + name + ")");
        u.setSortOrder(0);
        u.setUserNo(userNo);
        u.setCreateAt(now);
        u.setUpdatedAt(now);
        return u;
    }

    @Test
    void insertGlobalAndFindAll() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            mapper.insert(ua(newId(), "Chrome", null));
            assertThat(mapper.findAll()).hasSize(1);
        }
    }

    @Test
    void findAllForUser_returnsGlobalAndOwnPresets_notOthers() {
        var user = insertUser("alice");
        var other = insertUser("bob");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            mapper.insert(ua(newId(), "Global", null));
            mapper.insert(ua(newId(), "Mine", user.getUserNo()));
            mapper.insert(ua(newId(), "Others", other.getUserNo()));

            var result = mapper.findAllForUser(user.getUserNo());
            assertThat(result).extracting(HostsUa::getUaName).containsExactlyInAnyOrder("Global", "Mine");
        }
    }

    @Test
    void findByIdGlobal_returnsNull_forPersonalPreset() {
        var user = insertUser("carol");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var personal = ua(newId(), "Personal", user.getUserNo());
            mapper.insert(personal);
            assertThat(mapper.findByIdGlobal(personal.getUaId())).isNull();
            assertThat(mapper.findByIdAndUserNo(personal.getUaId(), user.getUserNo())).isNotNull();
        }
    }

    @Test
    void deleteByIdGlobal_doesNotDeletePersonalPreset() {
        var user = insertUser("dave");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var personal = ua(newId(), "Personal", user.getUserNo());
            mapper.insert(personal);

            mapper.deleteByIdGlobal(personal.getUaId());

            assertThat(mapper.findByIdAndUserNo(personal.getUaId(), user.getUserNo())).isNotNull();
        }
    }

    @Test
    void update_changesNameValueAndSortOrder() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var preset = ua(newId(), "Chrome", null);
            mapper.insert(preset);

            preset.setUaName("Chrome Updated");
            preset.setUaValue("Mozilla/5.0 (Updated)");
            preset.setSortOrder(5);
            preset.setUpdatedAt(LocalDateTime.now());
            mapper.update(preset);

            var found = mapper.findById(preset.getUaId());
            assertThat(found.getUaName()).isEqualTo("Chrome Updated");
            assertThat(found.getSortOrder()).isEqualTo(5);
        }
    }

    @Test
    void nextSortOrder_incrementsPastExistingMax() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            assertThat(mapper.nextSortOrder()).isZero();

            var first = ua(newId(), "First", null);
            first.setSortOrder(3);
            mapper.insert(first);

            assertThat(mapper.nextSortOrder()).isEqualTo(4);
        }
    }

    @Test
    void nextSortOrderForUser_isScopedPerUser() {
        var user = insertUser("erin");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            var global = ua(newId(), "Global", null);
            global.setSortOrder(9);
            mapper.insert(global);

            assertThat(mapper.nextSortOrderForUser(user.getUserNo())).isZero();
        }
    }

    @Test
    void deleteAllByUserNo_onlyRemovesThatUsersPresets() {
        var user = insertUser("frank");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUaMapper.class);
            mapper.insert(ua(newId(), "Global", null));
            mapper.insert(ua(newId(), "Mine", user.getUserNo()));

            mapper.deleteAllByUserNo(user.getUserNo());

            assertThat(mapper.findAll()).hasSize(1);
        }
    }
}
