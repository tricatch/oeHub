package tricatch.oe.fwdproxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.ibatis.session.SqlSessionFactory;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.proxy.service.ProxyConfService;
import tricatch.oe.proxy.util.HtmlUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Forward (upstream) proxy on a fixed port, authenticated against oeHub accounts (HUB_USR).
 * Once a client authenticates, requests for hosts present in that user's currently-selected
 * oeHosts profiles are routed to the IP recorded there instead of normal DNS resolution — the
 * per-user host->ip map is kept in memory (userHostMap) rather than queried per request.
 *
 * Always on — started unconditionally at boot (see OeHubApplication.main), not admin-toggleable.
 *
 * Merge semantics for a user's selected profiles intentionally mirror parseHostsToMap()/
 * mergeSelectedProfiles() in static/js/util.js (used by the oeHosts editor's merge preview):
 * first line for a host wins within a profile, and the first (highest-priority) profile wins
 * across profiles.
 */
public class ForwardProxyServer {

    private static final Logger logger = LoggerFactory.getLogger(ForwardProxyServer.class);

    private static final int PORT = 36980;
    private static final String REALM = "oeHub";
    // Matches the ${PROXY_SVR} placeholder oeHosts profiles use (see static/js/util.js /
    // hosts.pebble) to mean "oeHub's own IP as seen by this client" — resolved here per
    // connection from the accepted socket's local address, since the client-side JS
    // substitution used for --host-resolver-rules never runs for this proxy path.
    private static final String PROXY_SVR_PLACEHOLDER = "${PROXY_SVR}";

    // Relay whitelist: when non-empty, only destinations matching one of these patterns may be
    // relayed through the forward proxy — everything else gets a 403. Empty (the default) means
    // unrestricted, preserving prior behavior. A pattern may be a bare domain ("foo.com") or
    // wildcard-prefixed ("*.foo.com"); either form matches the domain itself and all subdomains,
    // mirroring the requestDomains semantics used by the oeOID Chrome extension (see background.js).
    private static final String KEY_WHITELIST = "fwdproxy.whitelist";

    private static SqlSessionFactory sqlSessionFactory;
    private static HttpProxyServer server;

    private static final ConcurrentHashMap<String, Map<String, String>> userHostMap = new ConcurrentHashMap<>();

    private static volatile String whitelistText = "";
    private static volatile List<String> whitelistPatterns = List.of();

    // Per-account auth throttle: this callback (org.littleshoot.proxy.ProxyAuthenticator) gets
    // only userName/password, no client IP, so — unlike AuthController's per-IP login lockout —
    // this is keyed by the attempted userId. Otherwise bcrypt cost is the only thing slowing an
    // online brute-force attempt against any HUB_USR account through this always-on, 0.0.0.0-bound
    // proxy port. In-memory only, same trade-off as AuthController's lockout.
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final java.time.Duration ATTEMPT_WINDOW = java.time.Duration.ofMinutes(15);
    private static final java.time.Duration LOCKOUT_DURATION = java.time.Duration.ofMinutes(15);

    private static final class AuthAttempts {
        int count;
        java.time.Instant windowStart;
        java.time.Instant lockedUntil;
    }

    private static final ConcurrentHashMap<String, AuthAttempts> authAttemptsByUser = new ConcurrentHashMap<>();

    // Same timing-parity rationale as AuthController.DUMMY_PASSWORD_HASH: without this, a
    // non-existent userId short-circuits before any bcrypt comparison, making account existence
    // enumerable via response timing over this always-on, 0.0.0.0-bound proxy port.
    private static final String DUMMY_PASSWORD_HASH = PasswordUtil.hash("no-such-user-timing-parity");

    // overrideFor (below) resolves an otherwise-unmapped hostname itself so a DNS answer pointing
    // at loopback/any-local (DNS rebinding) is caught before connecting - isLoopbackTarget() alone
    // only catches literal IPs/"localhost". Bounded by a timeout on its own virtual thread so a
    // slow/unresponsive attacker-controlled domain can't tie up the connection-resolution path.
    private static final ExecutorService DNS_RESOLVER = Executors.newVirtualThreadPerTaskExecutor();
    private static final long DNS_RESOLVE_TIMEOUT_MS = 500;

    public static int getPort() {
        return PORT;
    }

    public static void init(SqlSessionFactory factory) {
        sqlSessionFactory = factory;
        var stored = new ProxyConfService(factory).get(KEY_WHITELIST, null);
        applyWhitelist(stored != null ? stored : "");
    }

    public static String getWhitelist() {
        return whitelistText;
    }

    public static void setWhitelist(String text) {
        applyWhitelist(text != null ? text : "");
        new ProxyConfService(sqlSessionFactory).set(KEY_WHITELIST, null, whitelistText);
    }

    private static void applyWhitelist(String text) {
        whitelistText = text;
        whitelistPatterns = text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> (line.startsWith("*.") ? line.substring(2) : line).toLowerCase())
                .toList();
    }

    /** True when the whitelist is empty (unrestricted) or host matches one of its patterns. */
    static boolean isWhitelisted(String host) {
        var patterns = whitelistPatterns;
        if (patterns.isEmpty()) return true;
        if (host == null || host.isBlank()) return false;
        var h = host.toLowerCase();
        for (var base : patterns) {
            if (h.equals(base) || h.endsWith("." + base)) return true;
        }
        return false;
    }

    /**
     * True when the destination literally names this proxy host's own loopback interface, or
     * the unspecified/"any" address (0.0.0.0 / ::) — which several OSes (Linux included) treat
     * as "this host" on an outbound connect() the same as loopback. Always enforced — unlike
     * isWhitelisted(), an empty whitelist never permits this. A client tunneling to e.g.
     * 127.0.0.1:<h2-console-port> from the oeHub host itself defeats a downstream service's
     * "reject non-local requesters" check, since that check sees the connection as local. Only
     * checked against literal IPs/"localhost" (no DNS lookup here) so ordinary hostnames —
     * including LAN devices an oeHosts profile intentionally targets — cost nothing extra per
     * request; a hostname that itself resolves to a loopback address (DNS rebinding) is not
     * caught by this check - overrideFor() below closes that gap for the actual connection by
     * resolving the hostname itself (bounded by a timeout) and checking the resolved address,
     * since it alone decides what IP the tunnel/connection is made to.
     */
    static boolean isLoopbackTarget(String host) {
        if (host == null || host.isBlank()) return false;
        var h = host.trim();
        if (h.equalsIgnoreCase("localhost") || h.toLowerCase(java.util.Locale.ROOT).endsWith(".localhost")) {
            return true;
        }
        if (!looksLikeIpLiteral(h)) return false;
        try {
            var addr = InetAddress.getByName(h);
            return addr.isLoopbackAddress() || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // Digits/dots/colons only — every legacy numeric form InetAddress.getByName() parses as a
    // literal without a DNS lookup (dotted-quad "127.0.0.1", shorthand "127.1", pure-decimal
    // "2130706433", IPv6) matches this, while a real hostname always contains a letter or
    // hyphen and never reaches getByName() here at all.
    private static boolean looksLikeIpLiteral(String h) {
        return h.matches("[0-9.:]+");
    }

    public static synchronized void start() {
        if (server != null) return;

        var bootstrap = DefaultHttpProxyServer.bootstrap()
                .withName("oe-fwdproxy")
                .withPort(PORT)
                .withAllowLocalOnly(false)
                .withProxyAuthenticator(new ProxyAuthenticator() {
                    @Override
                    public boolean authenticate(String userId, String password) {
                        return ForwardProxyServer.authenticate(userId, password);
                    }

                    @Override
                    public String getRealm() {
                        return REALM;
                    }
                })
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                        return new HttpFiltersAdapter(originalRequest, ctx) {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest request) {
                                    var host = targetHost(request);
                                    if (isLoopbackTarget(host) || !isWhitelisted(host)) {
                                        // CONNECT (HTTPS): if the blocked-page server is up, let the tunnel
                                        // succeed here and redirect it there in overrideFor() below, so the
                                        // client completes a real TLS handshake and renders the 403 page
                                        // instead of just seeing the CONNECT itself fail. Otherwise (or for
                                        // plain HTTP, which renders a short-circuit response fine either way)
                                        // block immediately.
                                        if (request.method() != HttpMethod.CONNECT || !BlockedPageServer.isRunning()) {
                                            logger.info("Forward proxy blocked (not whitelisted): user={} host={}",
                                                    authenticatedUser(ctx), host);
                                            return blockedResponse(request, host);
                                        }
                                    }
                                }
                                return null;
                            }

                            @Override
                            public InetSocketAddress proxyToServerResolutionStarted(String resolvingServerHostAndPort) {
                                return overrideFor(authenticatedUser(ctx), resolvingServerHostAndPort, localServerIp(ctx));
                            }
                        };
                    }
                });

        try {
            server = bootstrap.start();
            logger.info("Forward proxy (LittleProxy) started on 0.0.0.0:{}", PORT);
        } catch (Exception e) {
            server = null;
            logger.error("Failed to start forward proxy on port {}: {}", PORT, e.getMessage(), e);
        }
    }

    // Package-private (not private) so ForwardProxyServerTest can exercise the lockout and
    // timing-parity behavior directly, matching this class's existing convention for its other
    // pure/testable logic (isWhitelisted, isLoopbackTarget, mergeHosts, hostOnly, overrideFor).
    static boolean authenticate(String userId, String password) {
        if (userId == null || password == null) return false;
        var key = userId.toLowerCase();
        if (isAuthLocked(key)) {
            logger.debug("Forward proxy auth blocked (locked out) for userId={}", userId);
            return false;
        }
        try (var session = sqlSessionFactory.openSession()) {
            var user = session.getMapper(HubUserMapper.class).findByUserId(userId);
            // Always run exactly one bcrypt comparison, real user or not - see DUMMY_PASSWORD_HASH.
            var hashToCheck = user != null ? user.getPassword() : DUMMY_PASSWORD_HASH;
            var isCorrectPassword = PasswordUtil.matches(password, hashToCheck);
            if (user == null || !isCorrectPassword) {
                recordAuthFailure(key);
                logger.debug("Forward proxy auth failed for userId={}", userId);
                return false;
            }
            recordAuthSuccess(key);
            refreshUserHosts(user);
            return true;
        } catch (Exception e) {
            logger.warn("Forward proxy auth error for userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    private static boolean isAuthLocked(String key) {
        var a = authAttemptsByUser.get(key);
        if (a == null) return false;
        synchronized (a) {
            return a.lockedUntil != null && java.time.Instant.now().isBefore(a.lockedUntil);
        }
    }

    private static void recordAuthFailure(String key) {
        var a = authAttemptsByUser.computeIfAbsent(key, k -> new AuthAttempts());
        synchronized (a) {
            var now = java.time.Instant.now();
            if (a.windowStart == null || java.time.Duration.between(a.windowStart, now).compareTo(ATTEMPT_WINDOW) > 0) {
                a.windowStart = now;
                a.count = 0;
            }
            a.count++;
            if (a.count >= MAX_FAILED_ATTEMPTS) {
                a.lockedUntil = now.plus(LOCKOUT_DURATION);
            }
        }
    }

    private static void recordAuthSuccess(String key) {
        authAttemptsByUser.remove(key);
    }

    /** Recomputes and caches the given user's merged host->ip map from their selected oeHosts profiles. */
    public static void refreshUserHosts(HubUser user) {
        if (user == null || sqlSessionFactory == null) return;
        try {
            var selected = new HostsProfService(sqlSessionFactory).list(user.getUserNo()).stream()
                    .filter(HostsProf::isSelected)
                    .toList();
            userHostMap.put(user.getUserId(), mergeHosts(selected));
        } catch (Exception e) {
            logger.warn("Failed to refresh forward-proxy host map for userId={}: {}", user.getUserId(), e.getMessage());
        }
    }

    static Map<String, String> mergeHosts(List<HostsProf> selectedProfiles) {
        var merged = new LinkedHashMap<String, String>();
        for (var profile : selectedProfiles) {
            var content = profile.getHostsContent();
            if (content == null || content.isBlank()) continue;
            for (var rawLine : content.split("\n")) {
                var line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                var parts = line.split("\\s+");
                if (parts.length < 2) continue;
                merged.putIfAbsent(parts[1].toLowerCase(), parts[0]);
            }
        }
        return merged;
    }

    /** Reads the username LittleProxy's built-in auth stored on this connection (null if unauthenticated). */
    static String authenticatedUser(ChannelHandlerContext ctx) {
        ClientToProxyConnection c2p = ctx.pipeline().get(ClientToProxyConnection.class);
        if (c2p == null || c2p.getClientDetails() == null) {
            return null;
        }
        return c2p.getClientDetails().getUserName();
    }

    /** The local address this client's connection was accepted on — i.e. oeHub's own IP as seen by that client. */
    private static String localServerIp(ChannelHandlerContext ctx) {
        if (ctx.channel().localAddress() instanceof InetSocketAddress local) {
            return local.getAddress().getHostAddress();
        }
        return null;
    }

    /** Extracts the bare target hostname (no port) from a client request, CONNECT or plain. */
    static String targetHost(HttpRequest request) {
        if (request.method() == HttpMethod.CONNECT) {
            return hostOnly(request.uri());
        }
        var host = request.headers().get(HttpHeaderNames.HOST);
        if (host == null) {
            try {
                host = URI.create(request.uri()).getHost();
            } catch (Exception e) {
                host = null;
            }
        }
        return hostOnly(host);
    }

    static String hostOnly(String hostAndPort) {
        if (hostAndPort == null) return null;
        return splitHostAndPort(hostAndPort, -1).host();
    }

    private record HostPort(String host, int port) {}

    /**
     * Splits "host:port" into host and port, defaulting the port when absent.
     * Handles bracketed IPv6 literals ("[::1]:8080", "[::1]") as well as a bare
     * IPv6 literal with no port ("::1") — the latter has multiple colons and no
     * brackets, so lastIndexOf(':') alone would wrongly chop off part of the address.
     */
    private static HostPort splitHostAndPort(String hostAndPort, int defaultPort) {
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            if (close < 0) return new HostPort(hostAndPort, defaultPort);
            String host = hostAndPort.substring(1, close);
            String rest = hostAndPort.substring(close + 1);
            if (rest.startsWith(":")) {
                try {
                    return new HostPort(host, Integer.parseInt(rest.substring(1)));
                } catch (NumberFormatException e) {
                    return new HostPort(host, defaultPort);
                }
            }
            return new HostPort(host, defaultPort);
        }
        if (hostAndPort.indexOf(':') != hostAndPort.lastIndexOf(':')) {
            // Multiple colons with no brackets: a bare IPv6 literal, not host:port.
            return new HostPort(hostAndPort, defaultPort);
        }
        int idx = hostAndPort.lastIndexOf(':');
        if (idx < 0) return new HostPort(hostAndPort, defaultPort);
        try {
            return new HostPort(hostAndPort.substring(0, idx), Integer.parseInt(hostAndPort.substring(idx + 1)));
        } catch (NumberFormatException e) {
            return new HostPort(hostAndPort, defaultPort);
        }
    }

    /** Builds the 403 response for a non-whitelisted destination, styled like oeProxy's other error pages. */
    private static HttpResponse blockedResponse(HttpRequest request, String host) {
        var locale = HtmlUtil.resolveLocale(
                request.headers().get(HttpHeaderNames.COOKIE),
                request.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE));
        var html = HtmlUtil.renderFwdProxyForbidden(host, locale);
        var body = Unpooled.copiedBuffer(html, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, body);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        return response;
    }

    /**
     * Looks up host:port in the user's cached map; falls back to resolving the host itself
     * (rebind-safe, see below) when there's no override.
     */
    static InetSocketAddress overrideFor(String userId, String hostAndPort, String localServerIp) {
        // Only reachable here for a blocked host (loopback target, or non-whitelisted) when the
        // CONNECT was deliberately let through by clientToProxyRequest because BlockedPageServer
        // is up (see there) - redirect the tunnel to it instead of the real destination. Must
        // mirror clientToProxyRequest's block condition exactly, or a host that satisfies one
        // check but not the other would fall through to a real connection below.
        var target = hostOnly(hostAndPort);
        if (isLoopbackTarget(target) || !isWhitelisted(target)) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
        }

        HostPort hp = splitHostAndPort(hostAndPort, 80);
        String host = hp.host();
        int port = hp.port();

        String ip = userId == null ? null : userHostMap.getOrDefault(userId, Map.of()).get(host.toLowerCase());
        if (ip != null) {
            if (PROXY_SVR_PLACEHOLDER.equals(ip)) {
                if (localServerIp == null) return null;
                ip = localServerIp;
            } else if (isLoopbackTarget(ip)) {
                // A user's own oeHosts profile is free-text content they authored (mergeHosts()
                // parses arbitrary "ip hostname" lines from it) - a line like "127.0.0.1 evil.local"
                // would otherwise let them CONNECT to a hostname that passes both checks above,
                // then have this override map silently redirect the tunnel to this server's own
                // loopback interface, the same SSRF pivot isLoopbackTarget(target) blocks for the
                // literal-host case. PROXY_SVR_PLACEHOLDER is exempt: it resolves to this server's
                // real network-facing IP as seen by the client, not its loopback interface.
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
            }
            try {
                byte[] addr = InetAddress.getByName(ip).getAddress();
                InetAddress forced = InetAddress.getByAddress(host, addr);
                return new InetSocketAddress(forced, port);
            } catch (UnknownHostException e) {
                return null;
            }
        }

        // No host-map override: target isn't a literal IP/"localhost" (isLoopbackTarget(target)
        // above already returned false), so resolve it ourselves and check the *resolved* address
        // before connecting - otherwise an attacker-registered domain whose DNS answer is
        // loopback/any-local (DNS rebinding) would sail through every string-based check here and
        // reach this server's own loopback interface once LittleProxy resolves it independently.
        // Pinning the address we just checked (rather than returning null and letting LittleProxy
        // resolve again) also closes the TOCTOU window a rebinding DNS server could otherwise use.
        try {
            var future = DNS_RESOLVER.submit(() -> InetAddress.getByName(host));
            var resolved = future.get(DNS_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (resolved.isLoopbackAddress() || resolved.isAnyLocalAddress()) {
                logger.info("Forward proxy blocked (DNS-rebind to loopback): host={} resolvedTo={}",
                        host, resolved.getHostAddress());
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
            }
            var forced = InetAddress.getByAddress(host, resolved.getAddress());
            return new InetSocketAddress(forced, port);
        } catch (Exception e) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
        }
    }
}
