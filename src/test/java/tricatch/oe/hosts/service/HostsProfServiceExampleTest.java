package tricatch.oe.hosts.service;

import org.junit.jupiter.api.Test;
import tricatch.oe.mapper.MapperTestBase;

import static org.assertj.core.api.Assertions.assertThat;

// A new profile starts with sample content. The sample demonstrates ${PROXY_SVR} (oeProxy's
// address), which means nothing in workspace mode - it has no oeProxy - so it is left out there.
class HostsProfServiceExampleTest extends MapperTestBase {

    @Test
    void selfHosted_sampleShowsThePlaceholder() {
        var owner = insertUser("example-self-hosted");

        var created = new HostsProfService(FACTORY).create(owner.getUserNo());

        assertThat(created.getHostsContent()).contains("foo.oe").contains("${PROXY_SVR}");
    }

    @Test
    void workspaceMode_sampleHasNoPlaceholder_butStillShowsAnExample() {
        var owner = insertUser("example-workspace");
        var previous = System.getProperty("oe.mode");
        System.setProperty("oe.mode", "workspace");
        try {
            var created = new HostsProfService(FACTORY).create(owner.getUserNo());

            assertThat(created.getHostsContent()).contains("foo.oe").doesNotContain("PROXY_SVR");
            assertThat(created.getHostsContent()).endsWith("\n");
        } finally {
            if (previous == null) System.clearProperty("oe.mode");
            else System.setProperty("oe.mode", previous);
        }
    }
}
