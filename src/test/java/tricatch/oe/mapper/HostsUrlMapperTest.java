package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.hosts.model.HostsUrl;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HostsUrlMapperTest extends MapperTestBase {

    private HostsUrl url(String id, String name, Long userNo) {
        var now = LocalDateTime.now();
        var u = new HostsUrl();
        u.setUrlId(id);
        u.setUrlName(name);
        u.setUrlValue("https://example.com/" + name);
        u.setSortOrder(0);
        u.setUserNo(userNo);
        u.setWsNo(TEST_WS_NO);
        var actor = userNo != null ? userNo : 0L;
        u.setCreatedBy(actor);
        u.setUpdatedBy(actor);
        u.setCreateAt(now);
        u.setUpdatedAt(now);
        return u;
    }

    @Test
    void insertGlobalAndFindAll() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            mapper.insert(url(newId(), "Health", null));
            assertThat(mapper.findAllByWs(TEST_WS_NO)).hasSize(1);
        }
    }

    @Test
    void findAllForUser_returnsGlobalAndOwnPresets_notOthers() {
        var user = insertUser("alice");
        var other = insertUser("bob");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            mapper.insert(url(newId(), "Global", null));
            mapper.insert(url(newId(), "Mine", user.getUserNo()));
            mapper.insert(url(newId(), "Others", other.getUserNo()));

            var result = mapper.findAllForUser(TEST_WS_NO, user.getUserNo());
            assertThat(result).extracting(HostsUrl::getUrlName).containsExactlyInAnyOrder("Global", "Mine");
        }
    }

    @Test
    void findByIdGlobal_returnsNull_forPersonalPreset() {
        var user = insertUser("carol");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var personal = url(newId(), "Personal", user.getUserNo());
            mapper.insert(personal);
            assertThat(mapper.findByIdGlobal(personal.getUrlId(), TEST_WS_NO)).isNull();
            assertThat(mapper.findByIdAndUserNo(personal.getUrlId(), user.getUserNo())).isNotNull();
        }
    }

    @Test
    void deleteByIdGlobal_doesNotDeletePersonalPreset() {
        var user = insertUser("dave");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var personal = url(newId(), "Personal", user.getUserNo());
            mapper.insert(personal);

            mapper.deleteByIdGlobal(personal.getUrlId(), TEST_WS_NO);

            assertThat(mapper.findByIdAndUserNo(personal.getUrlId(), user.getUserNo())).isNotNull();
        }
    }

    @Test
    void update_changesNameValueAndSortOrder() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var preset = url(newId(), "Health", null);
            mapper.insert(preset);

            preset.setUrlName("Health Updated");
            preset.setUrlValue("https://example.com/updated");
            preset.setSortOrder(5);
            preset.setUpdatedAt(LocalDateTime.now());
            mapper.update(preset);

            var found = mapper.findById(preset.getUrlId());
            assertThat(found.getUrlName()).isEqualTo("Health Updated");
            assertThat(found.getSortOrder()).isEqualTo(5);
        }
    }

    @Test
    void nextSortOrder_incrementsPastExistingMax() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            assertThat(mapper.nextSortOrder(TEST_WS_NO)).isZero();

            var first = url(newId(), "First", null);
            first.setSortOrder(3);
            mapper.insert(first);

            assertThat(mapper.nextSortOrder(TEST_WS_NO)).isEqualTo(4);
        }
    }

    @Test
    void nextSortOrderForUser_isScopedPerUser() {
        var user = insertUser("erin");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var global = url(newId(), "Global", null);
            global.setSortOrder(9);
            mapper.insert(global);

            assertThat(mapper.nextSortOrderForUser(user.getUserNo())).isZero();
        }
    }

    @Test
    void deleteAllByUserNo_onlyRemovesThatUsersPresets() {
        var user = insertUser("frank");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            mapper.insert(url(newId(), "Global", null));
            mapper.insert(url(newId(), "Mine", user.getUserNo()));

            mapper.deleteAllByUserNo(user.getUserNo());

            assertThat(mapper.findAllByWs(TEST_WS_NO)).hasSize(1);
        }
    }

    @Test
    void presets_areIsolatedPerWorkspace() {
        var otherWs = insertWorkspace("other");
        var user = insertUser("iris");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            mapper.insert(url(newId(), "Mine-ws", null));
            var foreign = url(newId(), "Other-ws", null);
            foreign.setWsNo(otherWs);
            mapper.insert(foreign);

            assertThat(mapper.findAllByWs(TEST_WS_NO)).extracting(p -> p.getUrlName()).containsExactly("Mine-ws");
            assertThat(mapper.findAllByWs(otherWs)).extracting(p -> p.getUrlName()).containsExactly("Other-ws");
            assertThat(mapper.findAllForUser(TEST_WS_NO, user.getUserNo()))
                .extracting(p -> p.getUrlName()).containsExactly("Mine-ws");
        }
    }

    @Test
    void globalLookupsAndDeletes_cannotReachAnotherWorkspacesPreset() {
        var otherWs = insertWorkspace("other2");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var foreign = url(newId(), "Foreign", null);
            foreign.setWsNo(otherWs);
            mapper.insert(foreign);

            assertThat(mapper.findByIdGlobal(foreign.getUrlId(), TEST_WS_NO)).isNull();
            assertThat(mapper.deleteByIdGlobal(foreign.getUrlId(), TEST_WS_NO)).isZero();
            assertThat(mapper.findByIdGlobal(foreign.getUrlId(), otherWs)).isNotNull();
        }
    }

    @Test
    void nextSortOrder_isScopedPerWorkspace() {
        var otherWs = insertWorkspace("other3");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HostsUrlMapper.class);
            var foreign = url(newId(), "Foreign", null);
            foreign.setWsNo(otherWs);
            foreign.setSortOrder(7);
            mapper.insert(foreign);

            assertThat(mapper.nextSortOrder(TEST_WS_NO)).isZero();
            assertThat(mapper.nextSortOrder(otherWs)).isEqualTo(8);
        }
    }
}
