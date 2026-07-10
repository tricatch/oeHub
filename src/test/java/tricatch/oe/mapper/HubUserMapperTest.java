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
            assertThat(session.getMapper(HubUserMapper.class).findAll()).hasSize(3);
        }
    }

    @Test
    void searchByUserId() {
        insertUser("alice");
        insertUser("alicia");
        insertUser("bob");
        try (var session = FACTORY.openSession()) {
            var results = session.getMapper(HubUserMapper.class).searchByUserId("ali");
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
    void deleteByUserNo() {
        var user = insertUser("eve");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.deleteByUserNo(user.getUserNo());
            assertThat(mapper.findByUserNo(user.getUserNo())).isNull();
        }
    }
}
