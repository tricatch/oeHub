package tricatch.oe.proxy.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * renderFwdProxyForbidden() picks different explanation/remedy copy depending on the reason
 * ForwardProxyServer recorded for a blocked destination (its BLOCK_REASON_* constants - "loopback",
 * "whitelist", "unresolved" - are package-private there, so this test uses the same literal
 * strings rather than depending on them) - these tests exercise every reason branch in the
 * Pebble template directly, without needing a running proxy.
 */
class HtmlUtilTest {

    @Test
    void renderFwdProxyForbidden_loopbackReason_showsLoopbackCopy() {
        var html = HtmlUtil.renderFwdProxyForbidden("foo.oe", "loopback", "en");
        assertThat(html).contains("loopback").contains("foo.oe");
        assertThat(html).doesNotContain("relay whitelist");
    }

    @Test
    void renderFwdProxyForbidden_unresolvedReason_showsUnresolvedCopy() {
        // status-desc isn't rendered with the `raw` filter, so autoescaping turns the apostrophe
        // in "couldn't" into &#39; - assert on a substring either side of it instead.
        var html = HtmlUtil.renderFwdProxyForbidden("foo.oe", "unresolved", "en");
        assertThat(html).contains("resolved by real DNS").contains("foo.oe");
    }

    @Test
    void renderFwdProxyForbidden_whitelistReason_showsWhitelistCopy() {
        var html = HtmlUtil.renderFwdProxyForbidden("foo.oe", "whitelist", "en");
        assertThat(html).contains("relay whitelist").contains("foo.oe");
    }

    @Test
    void renderFwdProxyForbidden_unknownOrMissingReason_fallsBackToWhitelistCopy() {
        // e.g. BlockedPageServer read the reason after it already expired/was consumed.
        assertThat(HtmlUtil.renderFwdProxyForbidden("foo.oe", null, "en")).contains("relay whitelist");
        assertThat(HtmlUtil.renderFwdProxyForbidden("foo.oe", "something-unrecognized", "en")).contains("relay whitelist");
    }
}
