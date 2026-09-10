package tricatch.oe.hosts.service;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// Account deletion must not silently destroy content the rest of the workspace still depends on
// - cloudGroupService design doc §2.5's orphan-handling rule: 'public' survives (reassigned to
// ws_system), 'private'/'collabo' still go away with the account.
class HostsProfServiceDeleteAllTest extends MapperTestBase {

    private HubUser insertWsSystem(Long wsNo) {
        var now = LocalDateTime.now();
        var wsSystem = new HubUser();
        wsSystem.setUserId("__ws_system_" + wsNo + "_" + newId().substring(0, 6));
        wsSystem.setPassword(PasswordUtil.hash(newId()));
        wsSystem.setRole("ws_system");
        wsSystem.setWsNo(wsNo);
        wsSystem.setCreateAt(now);
        wsSystem.setUpdatedAt(now);
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.insert(wsSystem);
            mapper.selfReferenceAudit(wsSystem.getUserNo());
        }
        return wsSystem;
    }

    @Test
    void deleteAll_reassignsPublicProfilesToWsSystem_deletesPrivateAndCollabo() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("deleteAllOwner");
        var service = new HostsProfService(FACTORY);

        var publicProfile = service.create(owner.getUserNo()); // defaults to visibility='public'
        var privateProfile = service.create(owner.getUserNo());
        service.updateVisibility(privateProfile.getHostsId(), owner.getUserNo(), "private", null);

        service.deleteAll(owner.getUserNo());

        // 'public' survives, now owned by ws_system.
        var survived = service.get(publicProfile.getHostsId());
        assertThat(survived).isNotNull();
        assertThat(survived.getUserNo()).isEqualTo(wsSystem.getUserNo());
        // The audit trail (who actually made it) is preserved even though ownership moved.
        assertThat(survived.getCreatedBy()).isEqualTo(owner.getUserNo());

        // 'private' is gone with the account.
        assertThat(service.get(privateProfile.getHostsId())).isNull();
    }

    @Test
    void deleteAll_reassignedProfile_dedupesNameAgainstWsSystemsExisting() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("deleteAllDupeOwner");
        var service = new HostsProfService(FACTORY);

        // ws_system already owns a profile named "Default" (e.g. from an earlier deletion).
        var existing = service.create(wsSystem.getUserNo());
        service.updateProfile(existing.getHostsId(), wsSystem.getUserNo(), "Default");

        var ownersProfile = service.create(owner.getUserNo());
        service.updateProfile(ownersProfile.getHostsId(), owner.getUserNo(), "Default");

        service.deleteAll(owner.getUserNo());

        var reassigned = service.get(ownersProfile.getHostsId());
        assertThat(reassigned.getUserNo()).isEqualTo(wsSystem.getUserNo());
        assertThat(reassigned.getHostsProfile()).isNotEqualTo("Default"); // renamed to dodge the collision
    }

    @Test
    void deleteAll_noWsSystem_stillDeletesPrivateWithoutError() {
        // A workspace with no ws_system account (shouldn't happen in practice, but deleteAll must
        // degrade gracefully rather than throw) - public profiles are simply not preserved.
        var owner = insertUser("noWsSystemOwner");
        var service = new HostsProfService(FACTORY);
        var publicProfile = service.create(owner.getUserNo());

        service.deleteAll(owner.getUserNo());

        assertThat(service.get(publicProfile.getHostsId())).isNull();
    }
}
