package tricatch.oe.fwdproxy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.mapper.MapperTestBase;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ForwardProxyServer had no test coverage before this file, despite being an always-on
 * (0.0.0.0-bound), unauthenticated-by-default network surface with security-critical logic:
 * SSRF-pivot blocking (isLoopbackTarget/overrideFor), destination whitelisting, per-account
 * brute-force lockout, and (as of this session) auth timing parity for non-existent users.
 */
class ForwardProxyServerTest extends MapperTestBase {

    @BeforeAll
    static void initServer() {
        ForwardProxyServer.init(FACTORY);
    }

    @BeforeEach
    void resetWhitelist() {
        ForwardProxyServer.setWhitelist("", 0L);
    }

    private HubUser insertUserWithPassword(String userId, String rawPassword) {
        var now = LocalDateTime.now();
        var user = new HubUser();
        user.setUserId(userId);
        user.setPassword(PasswordUtil.hash(rawPassword));
        user.setRole("usr");
        user.setCreateAt(now);
        user.setUpdatedAt(now);
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(HubUserMapper.class).insert(user);
        }
        return user;
    }

    // ── authenticate() ──────────────────────────────────────────────────────

    @Test
    void authenticate_correctPassword_succeeds() {
        var userId = "fwdauth-" + newId();
        insertUserWithPassword(userId, "correct-horse-battery");
        assertThat(ForwardProxyServer.authenticate(userId, "correct-horse-battery")).isTrue();
    }

    @Test
    void authenticate_wrongPassword_fails() {
        var userId = "fwdauth-" + newId();
        insertUserWithPassword(userId, "correct-horse-battery");
        assertThat(ForwardProxyServer.authenticate(userId, "wrong-password")).isFalse();
    }

    @Test
    void authenticate_unknownUser_failsWithoutError() {
        // Must not throw even though there is no row to compare against - this is exactly the
        // path DUMMY_PASSWORD_HASH exists to keep constant-time relative to a real user.
        assertThat(ForwardProxyServer.authenticate("no-such-user-" + newId(), "whatever")).isFalse();
    }

    @Test
    void authenticate_nullCredentials_fail() {
        assertThat(ForwardProxyServer.authenticate(null, "x")).isFalse();
        assertThat(ForwardProxyServer.authenticate("someone", null)).isFalse();
    }

    @Test
    void authenticate_lockedOutAfterRepeatedFailures_blocksEvenCorrectPassword() {
        var userId = "fwdlock-" + newId();
        insertUserWithPassword(userId, "correct-horse-battery");
        for (int i = 0; i < 5; i++) {
            assertThat(ForwardProxyServer.authenticate(userId, "wrong")).isFalse();
        }
        // 6th attempt: correct password, but the account is now locked out.
        assertThat(ForwardProxyServer.authenticate(userId, "correct-horse-battery")).isFalse();
    }

    // ── isWhitelisted() ─────────────────────────────────────────────────────

    @Test
    void isWhitelisted_emptyList_allowsAnyHost() {
        ForwardProxyServer.setWhitelist("", 0L);
        assertThat(ForwardProxyServer.isWhitelisted("example.com")).isTrue();
    }

    @Test
    void isWhitelisted_matchesExactAndWildcardSubdomain_caseInsensitively() {
        ForwardProxyServer.setWhitelist("*.Example.com\nfoo.com\n# comment\n\n", 0L);
        assertThat(ForwardProxyServer.isWhitelisted("EXAMPLE.com")).isTrue();
        assertThat(ForwardProxyServer.isWhitelisted("api.example.com")).isTrue();
        assertThat(ForwardProxyServer.isWhitelisted("foo.com")).isTrue();
        assertThat(ForwardProxyServer.isWhitelisted("evil.com")).isFalse();
        assertThat(ForwardProxyServer.isWhitelisted(null)).isFalse();
        assertThat(ForwardProxyServer.isWhitelisted("")).isFalse();
    }

    // ── isLoopbackTarget() ──────────────────────────────────────────────────

    @Test
    void isLoopbackTarget_detectsLocalAndAnyAddressForms() {
        assertThat(ForwardProxyServer.isLoopbackTarget("127.0.0.1")).isTrue();
        assertThat(ForwardProxyServer.isLoopbackTarget("localhost")).isTrue();
        assertThat(ForwardProxyServer.isLoopbackTarget("LOCALHOST")).isTrue();
        assertThat(ForwardProxyServer.isLoopbackTarget("foo.localhost")).isTrue();
        assertThat(ForwardProxyServer.isLoopbackTarget("0.0.0.0")).isTrue();
        assertThat(ForwardProxyServer.isLoopbackTarget("::1")).isTrue();
        assertThat(ForwardProxyServer.isLoopbackTarget("::")).isTrue();
    }

    @Test
    void isLoopbackTarget_ordinaryHostsAreNotFlagged() {
        assertThat(ForwardProxyServer.isLoopbackTarget("example.com")).isFalse();
        assertThat(ForwardProxyServer.isLoopbackTarget("8.8.8.8")).isFalse();
        assertThat(ForwardProxyServer.isLoopbackTarget(null)).isFalse();
        assertThat(ForwardProxyServer.isLoopbackTarget("")).isFalse();
    }

    // ── mergeHosts() ────────────────────────────────────────────────────────

    @Test
    void mergeHosts_firstLineWinsWithinProfile_firstProfileWinsAcrossProfiles() {
        var higherPriority = new HostsProf();
        higherPriority.setHostsContent("127.0.0.1 foo.oe\n127.0.0.2 foo.oe\n# comment\n\n10.0.0.1 bar.oe");
        var lowerPriority = new HostsProf();
        lowerPriority.setHostsContent("9.9.9.9 foo.oe\n9.9.9.9 baz.oe");

        var merged = ForwardProxyServer.mergeHosts(List.of(higherPriority, lowerPriority));

        assertThat(merged).containsEntry("foo.oe", "127.0.0.1"); // first line, first profile
        assertThat(merged).containsEntry("bar.oe", "10.0.0.1");
        assertThat(merged).containsEntry("baz.oe", "9.9.9.9");   // only present in the 2nd profile
    }

    @Test
    void mergeHosts_blankOrMalformedContent_isSkipped() {
        var blank = new HostsProf();
        blank.setHostsContent(null);
        var malformed = new HostsProf();
        malformed.setHostsContent("just-one-token\n127.0.0.1 ok.oe");

        var merged = ForwardProxyServer.mergeHosts(List.of(blank, malformed));

        assertThat(merged).hasSize(1);
        assertThat(merged).containsEntry("ok.oe", "127.0.0.1");
    }

    // ── hostOnly() ──────────────────────────────────────────────────────────

    @Test
    void hostOnly_stripsPort_andHandlesIPv6Literals() {
        assertThat(ForwardProxyServer.hostOnly("example.com:8080")).isEqualTo("example.com");
        assertThat(ForwardProxyServer.hostOnly("example.com")).isEqualTo("example.com");
        assertThat(ForwardProxyServer.hostOnly("[::1]:8080")).isEqualTo("::1");
        assertThat(ForwardProxyServer.hostOnly("[::1]")).isEqualTo("::1");
        assertThat(ForwardProxyServer.hostOnly("::1")).isEqualTo("::1");
        assertThat(ForwardProxyServer.hostOnly(null)).isNull();
    }

    // ── overrideFor() ───────────────────────────────────────────────────────

    @Test
    void overrideFor_loopbackDestination_isRedirectedToBlockedPage() {
        var addr = ForwardProxyServer.overrideFor("anyone", "127.0.0.1:9999", "10.1.1.1", "10.1.1.1");
        assertThat(addr).isNotNull();
        assertThat(addr.getAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    void overrideFor_nonWhitelistedDestination_isRedirectedToBlockedPage() {
        ForwardProxyServer.setWhitelist("only-this.example.com", 0L);
        var addr = ForwardProxyServer.overrideFor("anyone", "not-whitelisted.example.com:443", "10.1.1.1", "10.1.1.1");
        assertThat(addr).isNotNull();
        assertThat(addr.getAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    void overrideFor_userWithNoHostsProfileLoaded_resolvesHostItself() {
        // Not in any host map, and not a literal loopback/IP, so overrideFor resolves it itself
        // (rather than deferring to LittleProxy's own resolution) to catch DNS rebinding. A
        // reserved, never-resolvable TLD (RFC 2606) keeps this deterministic without depending on
        // network access: resolution fails either way, so it fails closed to the blocked page.
        InetSocketAddress addr = ForwardProxyServer.overrideFor(
                "user-with-no-cached-hosts-" + newId(), "definitely-nonexistent-host.invalid:443", "10.1.1.1", "10.1.1.1");
        assertThat(addr).isNotNull();
        assertThat(addr.getAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    void overrideFor_loopbackDestination_isAllowedWhenClientIsAlsoLoopback() {
        // A client that's already connecting from 127.0.0.1 gains nothing from this SSRF pivot -
        // it already has direct network access to this machine's loopback-bound ports - so the
        // guard is exempted for it (see overrideFor()'s class-level reasoning).
        var addr = ForwardProxyServer.overrideFor("anyone", "127.0.0.1:9999", "10.1.1.1", "127.0.0.1");
        assertThat(addr).isNotNull();
        assertThat(addr.getAddress().isLoopbackAddress()).isTrue(); // destination itself IS 127.0.0.1:9999
        assertThat(addr.getPort()).isEqualTo(9999); // not redirected to BlockedPageServer's port
    }

    @Test
    void overrideFor_hostsProfileEntryPointingAtLoopback_isAllowedWhenClientIsAlsoLoopback() {
        var userId = "fwdloopback-" + newId();
        var user = insertUserWithPassword(userId, "correct-horse-battery");

        var hostsService = new tricatch.oe.hosts.service.HostsProfService(FACTORY);
        var profile = hostsService.create(user.getUserNo());
        hostsService.updateContent(profile.getHostsId(), user.getUserNo(), "127.0.0.1 foo.oe");
        hostsService.toggleSelected(profile.getHostsId(), user.getUserNo());
        ForwardProxyServer.refreshUserHosts(user);

        var blockedForRemoteClient = ForwardProxyServer.overrideFor(userId, "foo.oe:443", "10.1.1.1", "203.0.113.9");
        assertThat(blockedForRemoteClient.getPort()).isEqualTo(BlockedPageServer.getPort());

        var addr = ForwardProxyServer.overrideFor(userId, "foo.oe:443", "10.1.1.1", "127.0.0.1");
        assertThat(addr.getAddress().isLoopbackAddress()).isTrue();
        assertThat(addr.getPort()).isEqualTo(443); // not redirected to BlockedPageServer's port
    }
}
