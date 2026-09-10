package tricatch.oe.proxy.service;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// Mirrors HostsProfServiceDeleteAllTest - PROXY_VHOST gets the same orphan-handling treatment
// (cloudGroupService design doc §2.5 explicitly names both tables).
class ProxyVhostServiceDeleteAllTest extends MapperTestBase {

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
    void deleteAll_reassignsPublicVhostsToWsSystem_deletesPrivateAndCollabo() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("vhostDeleteAllOwner");
        var service = new ProxyVhostService(FACTORY);

        var publicVhost = service.create(owner.getUserNo()); // defaults to visibility='public'
        var privateVhost = service.create(owner.getUserNo());
        service.updateVisibility(privateVhost.getVhostId(), owner.getUserNo(), "private");

        service.deleteAll(owner.getUserNo());

        var survived = service.get(publicVhost.getVhostId());
        assertThat(survived).isNotNull();
        assertThat(survived.getUserNo()).isEqualTo(wsSystem.getUserNo());
        assertThat(survived.getCreatedBy()).isEqualTo(owner.getUserNo());

        assertThat(service.get(privateVhost.getVhostId())).isNull();
    }

    @Test
    void deleteAll_reassignedVhost_dedupesNameAgainstWsSystemsExisting() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("vhostDeleteAllDupeOwner");
        var service = new ProxyVhostService(FACTORY);

        var existing = service.create(wsSystem.getUserNo());
        service.updateProfile(existing.getVhostId(), wsSystem.getUserNo(), "Default");

        var ownersVhost = service.create(owner.getUserNo());
        service.updateProfile(ownersVhost.getVhostId(), owner.getUserNo(), "Default");

        service.deleteAll(owner.getUserNo());

        var reassigned = service.get(ownersVhost.getVhostId());
        assertThat(reassigned.getUserNo()).isEqualTo(wsSystem.getUserNo());
        assertThat(reassigned.getVhostProfile()).isNotEqualTo("Default");
    }
}
