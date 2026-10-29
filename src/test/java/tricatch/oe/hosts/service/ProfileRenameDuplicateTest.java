package tricatch.oe.hosts.service;

import org.junit.jupiter.api.Test;
import tricatch.oe.hub.proxy.service.ProxyVhostService;
import tricatch.oe.hub.util.NameTakenException;
import tricatch.oe.mapper.MapperTestBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Renaming a hosts profile / virtual host to a name the user already has is refused with
// NameTakenException (the controllers answer 409 and the page shows a toast) instead of failing
// on the unique constraint. Case-insensitive, like the automatic "name (2)" naming.
class ProfileRenameDuplicateTest extends MapperTestBase {

    @Test
    void hostsRename_toAnotherProfilesName_isRefused() {
        var user = insertUser("renameDupHosts");
        var other = insertUser("renameDupHostsOther");
        var hosts = new HostsProfService(FACTORY);
        var a = hosts.create(user.getUserNo());
        hosts.updateProfile(a.getHostsId(), user.getUserNo(), "Dev");
        var b = hosts.create(user.getUserNo());

        assertThatThrownBy(() -> hosts.updateProfile(b.getHostsId(), user.getUserNo(), "dev"))
            .isInstanceOf(NameTakenException.class);
        assertThat(hosts.get(b.getHostsId()).getHostsProfile()).isNotEqualTo("dev");

        // A case-only rename of itself is fine, and another user's names don't count.
        assertThat(hosts.updateProfile(a.getHostsId(), user.getUserNo(), "DEV").getHostsProfile()).isEqualTo("DEV");
        var c = hosts.create(other.getUserNo());
        assertThat(hosts.updateProfile(c.getHostsId(), other.getUserNo(), "Dev").getHostsProfile()).isEqualTo("Dev");
    }

    @Test
    void vhostRename_toAnotherVhostsName_isRefused() {
        var user = insertUser("renameDupVhost");
        var vhosts = new ProxyVhostService(FACTORY);
        var a = vhosts.create(user.getUserNo());
        vhosts.updateProfile(a.getVhostId(), user.getUserNo(), "Api");
        var b = vhosts.create(user.getUserNo());

        assertThatThrownBy(() -> vhosts.updateProfile(b.getVhostId(), user.getUserNo(), "API"))
            .isInstanceOf(NameTakenException.class);
        assertThat(vhosts.updateProfile(a.getVhostId(), user.getUserNo(), "api").getVhostProfile()).isEqualTo("api");
    }
}
