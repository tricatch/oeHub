package tricatch.oe.hosts.service;

import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hub.config.Role;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 'managed' share scope: a workspace admin publishes a profile to every member. Each member gets
// their own unselected reference row (own order/folder/selection), can neither edit nor delete it,
// and only an admin's delete removes it - from everyone.
class ManagedProfileTest extends MapperTestBase {

    @Test
    void convert_givesEveryMemberAnUnselectedReadOnlyReference() {
        insertWsSystem(TEST_WS_NO);
        var admin = insertAdmin("managedAdmin");
        var member = insertUser("managedMember");
        var service = new HostsProfService(FACTORY);
        var own = service.create(member.getUserNo());

        var source = service.create(admin.getUserNo());
        service.updateContent(source.getHostsId(), admin.getUserNo(), "10.0.0.1 shared.oe\n", null);
        var converted = service.convertToManaged(source.getHostsId(), admin.getUserNo(), null);
        assertThat(converted.getShareScope()).isEqualTo(HostsProfService.MANAGED);
        assertThat(converted.getParentId()).isNotNull();
        // Shown as made by the admin, not by the wss account that owns the parent row.
        assertThat(converted.getUserId()).isEqualTo(admin.getUserId());

        var ref = managedOf(service.list(member.getUserNo()));
        assertThat(ref.getParentId()).isEqualTo(converted.getParentId());
        assertThat(ref.getHostsContent()).isEqualTo("10.0.0.1 shared.oe\n");
        assertThat(ref.isSelected()).isFalse();
        // Appended after what the member already had.
        assertThat(ref.getSortOrder()).isGreaterThan(own.getSortOrder());
        // A second list() adds nothing more.
        assertThat(service.list(member.getUserNo()).stream().filter(HostsProfService::isManaged)).hasSize(1);

        // Members can't edit, delete or change its scope; their own selection still works.
        assertThat(service.updateContent(ref.getHostsId(), member.getUserNo(), "x", null)).isNull();
        assertThat(service.delete(ref.getHostsId(), member.getUserNo())).isFalse();
        assertThat(service.updateShareScope(ref.getHostsId(), member.getUserNo(), "private", null)).isNull();
        assertThat(service.toggleSelected(ref.getHostsId(), member.getUserNo()).isSelected()).isTrue();

        // The admin's edit reaches everyone.
        service.updateContent(converted.getHostsId(), admin.getUserNo(), "10.0.0.2 shared.oe\n", null);
        assertThat(service.get(ref.getHostsId()).getHostsContent()).isEqualTo("10.0.0.2 shared.oe\n");
        assertThat(service.selectedReferenceUserNos(converted.getParentId(), admin.getUserNo()))
            .containsExactly(member.getUserNo());
    }

    @Test
    void memberWhoJoinsLater_getsTheReferenceOnFirstList() {
        insertWsSystem(TEST_WS_NO);
        var admin = insertAdmin("managedLateAdmin");
        var service = new HostsProfService(FACTORY);
        var source = service.create(admin.getUserNo());
        var converted = service.convertToManaged(source.getHostsId(), admin.getUserNo(), null);

        var late = insertUser("managedLateMember");
        assertThat(managedOf(service.list(late.getUserNo())).getParentId()).isEqualTo(converted.getParentId());
    }

    @Test
    void deleteAll_andReplaceImport_bringTheReferenceBack() {
        insertWsSystem(TEST_WS_NO);
        var admin = insertAdmin("managedResyncAdmin");
        var member = insertUser("managedResyncMember");
        var service = new HostsProfService(FACTORY);
        var converted = service.convertToManaged(service.create(admin.getUserNo()).getHostsId(), admin.getUserNo(), null);
        service.list(member.getUserNo());

        service.deleteAll(member.getUserNo());
        // The parent outlives the member's references.
        assertThat(service.get(converted.getParentId())).isNotNull();
        assertThat(managedOf(service.list(member.getUserNo()))).isNotNull();

        assertThat(managedOf(service.importProfiles(member.getUserNo(), List.of(), false))).isNotNull();
    }

    @Test
    void adminDelete_removesItFromEveryMember() {
        insertWsSystem(TEST_WS_NO);
        var admin = insertAdmin("managedDeleteAdmin");
        var member = insertUser("managedDeleteMember");
        var service = new HostsProfService(FACTORY);
        var converted = service.convertToManaged(service.create(admin.getUserNo()).getHostsId(), admin.getUserNo(), null);
        var ref = managedOf(service.list(member.getUserNo()));
        service.toggleSelected(ref.getHostsId(), member.getUserNo());

        var selected = service.deleteManaged(converted.getHostsId(), admin.getUserNo());

        assertThat(selected).containsExactly(member.getUserNo());
        assertThat(service.get(converted.getParentId())).isNull();
        assertThat(service.get(ref.getHostsId())).isNull();
        assertThat(service.list(member.getUserNo()).stream().filter(HostsProfService::isManaged)).isEmpty();
    }

    @Test
    void adminAccountRemoval_keepsTheManagedProfileForEveryoneElse() {
        insertWsSystem(TEST_WS_NO);
        var admin = insertAdmin("managedLeavingAdmin");
        var member = insertUser("managedStayingMember");
        var service = new HostsProfService(FACTORY);
        var converted = service.convertToManaged(service.create(admin.getUserNo()).getHostsId(), admin.getUserNo(), null);
        service.list(member.getUserNo());

        service.deleteAllForAccountRemoval(admin.getUserNo());

        assertThat(service.get(converted.getParentId())).isNotNull();
        assertThat(managedOf(service.list(member.getUserNo())).getParentId()).isEqualTo(converted.getParentId());
    }

    private static HostsProf managedOf(List<HostsProf> rows) {
        return rows.stream().filter(HostsProfService::isManaged).findFirst().orElse(null);
    }

    private HubUser insertAdmin(String userId) {
        var user = insertUser(userId);
        try (var session = FACTORY.openSession(true)) {
            session.getConnection().createStatement()
                .execute("UPDATE HUB_USR SET role = '" + Role.WSA + "' WHERE user_no = " + user.getUserNo());
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
        user.setRole(Role.WSA);
        return user;
    }
}
