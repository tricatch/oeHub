package tricatch.oe.mapper;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.TeamMapper;
import tricatch.oe.hub.mapper.WsInviteMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.hub.model.Team;
import tricatch.oe.hub.model.WsInvite;
import tricatch.oe.hub.model.Workspace;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TeamMapperTest extends MapperTestBase {

    private Team insertTeam(String name) {
        var now = LocalDateTime.now();
        var team = new Team();
        team.setWsNo(TEST_WS_NO);
        team.setTeamName(name);
        team.setCreatedBy(0L);
        team.setUpdatedBy(0L);
        team.setCreateAt(now);
        team.setUpdatedAt(now);
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(TeamMapper.class).insert(team);
        }
        return team;
    }

    @Test
    void insertAndFindByTeamNo() {
        var team = insertTeam("Engineering");
        try (var session = FACTORY.openSession()) {
            var found = session.getMapper(TeamMapper.class).findByTeamNo(team.getTeamNo());
            assertThat(found).isNotNull();
            assertThat(found.getTeamName()).isEqualTo("Engineering");
            assertThat(found.getWsNo()).isEqualTo(TEST_WS_NO);
            assertThat(found.getCreateAt()).isNotNull();
            assertThat(found.getUpdatedAt()).isNotNull();
        }
    }

    @Test
    void findByTeamNo_notFound() {
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(TeamMapper.class).findByTeamNo(999999999L)).isNull();
        }
    }

    @Test
    void findByWsNo_ordersAlphabeticallyAndScopesToWorkspace() {
        insertTeam("QA");
        insertTeam("Design");
        insertTeam("Backend");
        try (var session = FACTORY.openSession()) {
            var teams = session.getMapper(TeamMapper.class).findByWsNo(TEST_WS_NO);
            assertThat(teams).extracting(Team::getTeamName).containsExactly("Backend", "Design", "QA");
        }
    }

    @Test
    void findByWsNo_excludesOtherWorkspaces() {
        insertTeam("MineOnly");
        try (var session = FACTORY.openSession(true)) {
            var wsMapper = session.getMapper(tricatch.oe.hub.mapper.WorkspaceMapper.class);
            var otherWs = new Workspace();
            otherWs.setWsName("Other Team Workspace " + newId());
            otherWs.setStatus("active");
            var now = LocalDateTime.now();
            otherWs.setCreateAt(now);
            otherWs.setUpdatedAt(now);
            wsMapper.insert(otherWs);

            var otherTeam = new Team();
            otherTeam.setWsNo(otherWs.getWsNo());
            otherTeam.setTeamName("OtherWsTeam");
            otherTeam.setCreatedBy(0L);
            otherTeam.setUpdatedBy(0L);
            otherTeam.setCreateAt(now);
            otherTeam.setUpdatedAt(now);
            session.getMapper(TeamMapper.class).insert(otherTeam);

            var teams = session.getMapper(TeamMapper.class).findByWsNo(TEST_WS_NO);
            assertThat(teams).extracting(Team::getTeamName).containsExactly("MineOnly");
        }
    }

    @Test
    void updateName() {
        var team = insertTeam("OldName");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(TeamMapper.class);
            mapper.updateName(team.getTeamNo(), "NewName", 0L, LocalDateTime.now());
            var found = mapper.findByTeamNo(team.getTeamNo());
            assertThat(found.getTeamName()).isEqualTo("NewName");
        }
    }

    @Test
    void delete_succeedsWhenNoMembersReference() {
        var team = insertTeam("Disposable");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(TeamMapper.class);
            int deleted = mapper.delete(team.getTeamNo(), TEST_WS_NO);
            assertThat(deleted).isEqualTo(1);
            assertThat(mapper.findByTeamNo(team.getTeamNo())).isNull();
        }
    }

    @Test
    void delete_refusedWhileMembersReferenceTeam() {
        var team = insertTeam("InUse");
        var user = insertUser("teammember");
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(HubUserMapper.class).updateTeam(user.getUserNo(), team.getTeamNo(), 0L, LocalDateTime.now());
        }
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(TeamMapper.class);
            int deleted = mapper.delete(team.getTeamNo(), TEST_WS_NO);
            assertThat(deleted).isEqualTo(0);
            assertThat(mapper.findByTeamNo(team.getTeamNo())).isNotNull();
        }
    }

    @Test
    void delete_succeedsAfterMemberReassigned() {
        var team = insertTeam("Reassignable");
        var user = insertUser("movingmember");
        try (var session = FACTORY.openSession(true)) {
            var hubUserMapper = session.getMapper(HubUserMapper.class);
            hubUserMapper.updateTeam(user.getUserNo(), team.getTeamNo(), 0L, LocalDateTime.now());
            hubUserMapper.updateTeam(user.getUserNo(), null, 0L, LocalDateTime.now());
        }
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(TeamMapper.class);
            int deleted = mapper.delete(team.getTeamNo(), TEST_WS_NO);
            assertThat(deleted).isEqualTo(1);
        }
    }

    @Test
    void delete_scopedToOwnWorkspace_cannotDeleteForeignTeam() {
        Team otherTeam;
        try (var session = FACTORY.openSession(true)) {
            var wsMapper = session.getMapper(tricatch.oe.hub.mapper.WorkspaceMapper.class);
            var otherWs = new Workspace();
            otherWs.setWsName("Foreign Team Workspace " + newId());
            otherWs.setStatus("active");
            var now = LocalDateTime.now();
            otherWs.setCreateAt(now);
            otherWs.setUpdatedAt(now);
            wsMapper.insert(otherWs);

            otherTeam = new Team();
            otherTeam.setWsNo(otherWs.getWsNo());
            otherTeam.setTeamName("ForeignTeam");
            otherTeam.setCreatedBy(0L);
            otherTeam.setUpdatedBy(0L);
            otherTeam.setCreateAt(now);
            otherTeam.setUpdatedAt(now);
            session.getMapper(TeamMapper.class).insert(otherTeam);
        }
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(TeamMapper.class);
            // Forged/mismatched wsNo - the wsNo-scoped WHERE clause must refuse this even though
            // the team itself has no members (mirrors HostsProfMapper's rotation-update pattern).
            int deleted = mapper.delete(otherTeam.getTeamNo(), TEST_WS_NO);
            assertThat(deleted).isEqualTo(0);
            assertThat(mapper.findByTeamNo(otherTeam.getTeamNo())).isNotNull();
        }
    }

    @Test
    void countMembers_reflectsCurrentAssignments() {
        var team = insertTeam("Counted");
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(TeamMapper.class).countMembers(team.getTeamNo())).isZero();
        }
        var user = insertUser("countedmember");
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(HubUserMapper.class).updateTeam(user.getUserNo(), team.getTeamNo(), 0L, LocalDateTime.now());
        }
        try (var session = FACTORY.openSession()) {
            assertThat(session.getMapper(TeamMapper.class).countMembers(team.getTeamNo())).isEqualTo(1);
        }
    }

    // A used/expired HUB_WS_INVITE row that once pre-assigned this team carries a real FK to
    // HUB_TEAM (unlike the soft created_by/updated_by references elsewhere) - deleting the team
    // without first nulling that reference would fail with a constraint violation even though the
    // invite has no bearing on any current member.
    @Test
    void clearFromInvites_unblocksDeleteOfATeamOnlyReferencedByAPastInvite() {
        var team = insertTeam("InvitedTeam");
        var now = LocalDateTime.now();
        var invite = new WsInvite();
        invite.setInviteCode(newId().substring(0, 20));
        invite.setWsNo(TEST_WS_NO);
        invite.setTeamNo(team.getTeamNo());
        invite.setCreatedBy(0L);
        invite.setUpdatedBy(0L);
        invite.setCreateAt(now);
        invite.setUpdatedAt(now);
        invite.setExpiresAt(now.plusDays(7));
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(WsInviteMapper.class).insert(invite);
        }

        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(TeamMapper.class);
            assertThat(mapper.countMembers(team.getTeamNo())).isZero();
            mapper.clearFromInvites(team.getTeamNo());
            assertThat(mapper.delete(team.getTeamNo(), TEST_WS_NO)).isEqualTo(1);
        }
        try (var session = FACTORY.openSession()) {
            var reloadedInvite = session.getMapper(WsInviteMapper.class).findByCode(invite.getInviteCode());
            assertThat(reloadedInvite.getTeamNo()).isNull();
        }
    }

    @Test
    void hubUserMapper_updateTeam_assignsAndClears() {
        var team = insertTeam("Assignable");
        var user = insertUser("assignee");
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.updateTeam(user.getUserNo(), team.getTeamNo(), 0L, LocalDateTime.now());
            assertThat(mapper.findByUserNo(user.getUserNo()).getTeamNo()).isEqualTo(team.getTeamNo());

            mapper.updateTeam(user.getUserNo(), null, 0L, LocalDateTime.now());
            assertThat(mapper.findByUserNo(user.getUserNo()).getTeamNo()).isNull();
        }
    }

    @Test
    void hubUserMapper_findAll_joinsTeamName() {
        var team = insertTeam("JoinedTeam");
        var user = insertUser("joineduser");
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(HubUserMapper.class).updateTeam(user.getUserNo(), team.getTeamNo(), 0L, LocalDateTime.now());
        }
        try (var session = FACTORY.openSession()) {
            var found = session.getMapper(HubUserMapper.class).findAll(TEST_WS_NO).stream()
                .filter(u -> u.getUserId().equals("joineduser"))
                .findFirst().orElseThrow();
            assertThat(found.getTeamNo()).isEqualTo(team.getTeamNo());
            assertThat(found.getTeamName()).isEqualTo("JoinedTeam");
        }
    }

    @Test
    void hubUserMapper_findAll_teamNameNullWhenNoTeam() {
        insertUser("noteamuser");
        try (var session = FACTORY.openSession()) {
            var found = session.getMapper(HubUserMapper.class).findAll(TEST_WS_NO).stream()
                .filter(u -> u.getUserId().equals("noteamuser"))
                .findFirst().orElseThrow();
            assertThat(found.getTeamNo()).isNull();
            assertThat(found.getTeamName()).isNull();
        }
    }
}
