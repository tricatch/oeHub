package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.mapper.WorkspaceMapper;
import tricatch.oe.hub.model.Workspace;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceMapperTest extends MapperTestBase {

    private Workspace ws(String name) {
        var now = LocalDateTime.now();
        var w = new Workspace();
        w.setWsName(name);
        w.setStatus("active");
        w.setCreateAt(now);
        w.setUpdatedAt(now);
        return w;
    }

    @Test
    void insertAndFindByWsNo() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WorkspaceMapper.class);
            var workspace = ws("Acme");
            mapper.insert(workspace);

            assertThat(workspace.getWsNo()).isNotNull();
            var found = mapper.findByWsNo(workspace.getWsNo());
            assertThat(found.getWsName()).isEqualTo("Acme");
            assertThat(found.getStatus()).isEqualTo("active");
            assertThat(found.getCreatedBy()).isNull();
            assertThat(found.getUpdatedBy()).isNull();
        }
    }

    @Test
    void findByWsNo_notFound() {
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(WorkspaceMapper.class).findByWsNo(-1L)).isNull();
        }
    }

    @Test
    void findByWsName_findsAndMisses() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WorkspaceMapper.class);
            var workspace = ws("Name Lookup Co");
            mapper.insert(workspace);

            assertThat(mapper.findByWsName("Name Lookup Co").getWsNo()).isEqualTo(workspace.getWsNo());
            assertThat(mapper.findByWsName("No Such Workspace")).isNull();
        }
    }

    @Test
    void findFirst_returnsLowestWsNo() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WorkspaceMapper.class);
            var first = mapper.findFirst();
            // The shared test workspace created in MapperTestBase's static init is always present
            // and has the lowest ws_no, since nothing in this test class inserts one earlier.
            assertThat(first).isNotNull();
            assertThat(first.getWsNo()).isEqualTo(TEST_WS_NO);
        }
    }

    @Test
    void backfillAudit_setsCreatedAndUpdatedBy() {
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(WorkspaceMapper.class);
            var workspace = ws("Backfill Co");
            mapper.insert(workspace);

            var actor = insertUser("founder");
            var now = LocalDateTime.now();
            mapper.backfillAudit(workspace.getWsNo(), actor.getUserNo(), now);

            var found = mapper.findByWsNo(workspace.getWsNo());
            assertThat(found.getCreatedBy()).isEqualTo(actor.getUserNo());
            assertThat(found.getUpdatedBy()).isEqualTo(actor.getUserNo());
        }
    }
}
