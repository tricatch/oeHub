package tricatch.oe.proxy.service;

import org.junit.jupiter.api.Test;
import tricatch.oe.mapper.MapperTestBase;

import static org.assertj.core.api.Assertions.assertThat;

// Mirrors HostsProfServiceDeleteAllTest - PROXY_VHOST gets the same orphan-handling treatment
// (cloudGroupService design doc §2.5 explicitly names both tables).
class ProxyVhostServiceDeleteAllTest extends MapperTestBase {

    @Test
    void deleteAllForAccountRemoval_reassignsPublicVhostsToWsSystem_deletesPrivateAndCollabo() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("vhostDeleteAllOwner");
        var service = new ProxyVhostService(FACTORY);

        var publicVhost = service.create(owner.getUserNo()); // defaults to visibility='public'
        var privateVhost = service.create(owner.getUserNo());
        service.updateVisibility(privateVhost.getVhostId(), owner.getUserNo(), "private");

        service.deleteAllForAccountRemoval(owner.getUserNo());

        var survived = service.get(publicVhost.getVhostId());
        assertThat(survived).isNotNull();
        assertThat(survived.getUserNo()).isEqualTo(wsSystem.getUserNo());
        assertThat(survived.getCreatedBy()).isEqualTo(owner.getUserNo());

        assertThat(service.get(privateVhost.getVhostId())).isNull();
    }

    @Test
    void deleteAllForAccountRemoval_reassignedVhost_dedupesNameAgainstWsSystemsExisting() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("vhostDeleteAllDupeOwner");
        var service = new ProxyVhostService(FACTORY);

        var existing = service.create(wsSystem.getUserNo());
        service.updateProfile(existing.getVhostId(), wsSystem.getUserNo(), "Default");

        var ownersVhost = service.create(owner.getUserNo());
        service.updateProfile(ownersVhost.getVhostId(), owner.getUserNo(), "Default");

        service.deleteAllForAccountRemoval(owner.getUserNo());

        var reassigned = service.get(ownersVhost.getVhostId());
        assertThat(reassigned.getUserNo()).isEqualTo(wsSystem.getUserNo());
        assertThat(reassigned.getVhostProfile()).isNotEqualTo("Default");
    }

    @Test
    void deleteAll_userRequested_deletesPublicVhostsToo_andHandsNothingToWsSystem() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("vhostUserRequestedDeleteAll");
        var service = new ProxyVhostService(FACTORY);

        var publicVhost = service.create(owner.getUserNo()); // defaults to visibility='public'
        var privateVhost = service.create(owner.getUserNo());
        service.updateVisibility(privateVhost.getVhostId(), owner.getUserNo(), "private");

        service.deleteAll(owner.getUserNo());

        assertThat(service.get(publicVhost.getVhostId())).isNull();
        assertThat(service.get(privateVhost.getVhostId())).isNull();
        // Nothing was reassigned: the user asked for their vhosts to be deleted, not handed over.
        assertThat(service.list(wsSystem.getUserNo())).isEmpty();
    }
}
