package tricatch.oe.hosts.service;

import org.junit.jupiter.api.Test;
import tricatch.oe.mapper.MapperTestBase;

import static org.assertj.core.api.Assertions.assertThat;

// Account deletion must not silently destroy content the rest of the workspace still depends on
// - cloudGroupService design doc §2.5's orphan-handling rule: 'workspace' scope survives
// (reassigned to wss), 'private'/'collabo' still go away with the account. A user-requested
// "delete all" is not account deletion: it must really delete everything, workspace-scoped
// profiles included.
class HostsProfServiceDeleteAllTest extends MapperTestBase {

    @Test
    void deleteAllForAccountRemoval_reassignsPublicProfilesToWsSystem_deletesPrivateAndCollabo() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("deleteAllOwner");
        var service = new HostsProfService(FACTORY);

        var publicProfile = service.create(owner.getUserNo()); // defaults to share_scope='workspace'
        var privateProfile = service.create(owner.getUserNo());
        service.updateShareScope(privateProfile.getHostsId(), owner.getUserNo(), "private", null);

        service.deleteAllForAccountRemoval(owner.getUserNo());

        // 'workspace' scope survives, now owned by wss.
        var survived = service.get(publicProfile.getHostsId());
        assertThat(survived).isNotNull();
        assertThat(survived.getUserNo()).isEqualTo(wsSystem.getUserNo());
        // The audit trail (who actually made it) is preserved even though ownership moved.
        assertThat(survived.getCreatedBy()).isEqualTo(owner.getUserNo());

        // 'private' is gone with the account.
        assertThat(service.get(privateProfile.getHostsId())).isNull();
    }

    @Test
    void deleteAllForAccountRemoval_reassignedProfile_dedupesNameAgainstWsSystemsExisting() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("deleteAllDupeOwner");
        var service = new HostsProfService(FACTORY);

        // wss already owns a profile named "Default" (e.g. from an earlier deletion).
        var existing = service.create(wsSystem.getUserNo());
        service.updateProfile(existing.getHostsId(), wsSystem.getUserNo(), "Default");

        var ownersProfile = service.create(owner.getUserNo());
        service.updateProfile(ownersProfile.getHostsId(), owner.getUserNo(), "Default");

        service.deleteAllForAccountRemoval(owner.getUserNo());

        var reassigned = service.get(ownersProfile.getHostsId());
        assertThat(reassigned.getUserNo()).isEqualTo(wsSystem.getUserNo());
        assertThat(reassigned.getHostsProfile()).isNotEqualTo("Default"); // renamed to dodge the collision
    }

    @Test
    void deleteAllForAccountRemoval_noWsSystem_stillDeletesPrivateWithoutError() {
        // A workspace with no wss account (shouldn't happen in practice, but deleteAll must
        // degrade gracefully rather than throw) - public profiles are simply not preserved.
        var owner = insertUser("noWsSystemOwner");
        var service = new HostsProfService(FACTORY);
        var publicProfile = service.create(owner.getUserNo());

        service.deleteAllForAccountRemoval(owner.getUserNo());

        assertThat(service.get(publicProfile.getHostsId())).isNull();
    }

    @Test
    void deleteAll_userRequested_deletesPublicProfilesToo_andHandsNothingToWsSystem() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("userRequestedDeleteAll");
        var service = new HostsProfService(FACTORY);

        var publicProfile = service.create(owner.getUserNo()); // defaults to share_scope='workspace'
        var privateProfile = service.create(owner.getUserNo());
        service.updateShareScope(privateProfile.getHostsId(), owner.getUserNo(), "private", null);

        service.deleteAll(owner.getUserNo());

        assertThat(service.get(publicProfile.getHostsId())).isNull();
        assertThat(service.get(privateProfile.getHostsId())).isNull();
        // Nothing was reassigned: the user asked for their profiles to be deleted, not handed over.
        assertThat(service.list(wsSystem.getUserNo())).isEmpty();
    }
}
