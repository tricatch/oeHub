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
import org.littleshoot.proxy.ActivityTrackerAdapter;
import org.littleshoot.proxy.FullFlowContext;
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
import tricatch.oe.hub.config.Role;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.service.ProxyConfService;
import tricatch.oe.proxy.util.HtmlUtil;
import tricatch.oe.proxy.util.LittleProxyInternals;
import tricatch.oe.proxy.util.SelfLoopOwnerRegistry;

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

    // Allowed domains (whitelist): only destinations matching one of these patterns may be relayed
    // through the forward proxy — everything else gets a 403. Empty (the default) allows nothing:
    // the forward proxy is meant for a known set of (typically internal) domains. A pattern may be
    // a bare domain ("foo.com") or wildcard-prefixed ("*.foo.com"); either form matches the domain
    // itself and all subdomains, mirroring the requestDomains semantics used by the oeOID Chrome
    // extension (see background.js).
    private static final String KEY_WHITELIST = "fwdproxy.whitelist";

    // A client whose own connection to this proxy is itself loopback (127.0.0.1/::1) is exempted
    // from the loopback-target SSRF guards in overrideFor()/clientToProxyRequest() - it already
    // has direct network access to every loopback-bound port on this machine, so tunneling to one
    // through the proxy grants it nothing new. Not persisted/exposed anywhere; a plain code toggle.
    private static final boolean EXEMPT_LOOPBACK_CLIENTS = true;

    // Reason codes for the 403 page (HtmlUtil.renderFwdProxyForbidden picks its copy by this
    // value); anything else (including missing/expired) falls back to the whitelist copy.
    static final String BLOCK_REASON_LOOPBACK = "loopback";
    static final String BLOCK_REASON_WHITELIST = "whitelist";
    static final String BLOCK_REASON_UNRESOLVED = "unresolved";

    // overrideFor() redirects a blocked CONNECT tunnel to BlockedPageServer as a *new*, separate
    // TCP connection (LittleProxy dials 127.0.0.1:36981 itself) - so by the time BlockedPageServer
    // renders the actual 403 page, it has no memory of why that particular tunnel was redirected,
    // only the Host header of whatever request flows through it. This map is the only thing the
    // two sides share to correlate a block decision with its page: overrideFor() records the
    // reason under the target hostname right before returning the redirect, and
    // BlockedPageServer.handle() reads (and clears) it once it has the same hostname from the
    // tunneled request. Best-effort only - two concurrent blocked requests for the same host with
    // different reasons could race and show the wrong copy to one of them, which is an acceptable
    // trade-off for what is purely explanatory page text, not a security decision.
    private static final ConcurrentHashMap<String, String> blockReasonByHost = new ConcurrentHashMap<>();

    private static void rememberBlockReason(String host, String reason) {
        if (host != null) blockReasonByHost.put(host.toLowerCase(), reason);
    }

    /** Package-visible so BlockedPageServer can read the reason overrideFor() recorded for host. */
    static String takeBlockReason(String host) {
        return host == null ? null : blockReasonByHost.remove(host.toLowerCase());
    }

    private static SqlSessionFactory sqlSessionFactory;
    private static HttpProxyServer server;

    private static final ConcurrentHashMap<String, Map<String, String>> userHostMap = new ConcurrentHashMap<>();

    // userNo for each currently-authenticated userId, cached alongside userHostMap (same
    // lifecycle - populated in refreshUserHosts(), which already has the full HubUser in hand at
    // both call sites: successful proxy auth, and the admin hosts-profile page saving/selecting
    // profiles). Lets proxyToServerConnectionSucceeded() below resolve a userNo for the
    // self-loop-owner registration without a second DB lookup. userNo never changes for an
    // account, so unlike the host map this never needs active invalidation.
    private static final ConcurrentHashMap<String, Long> userNoMap = new ConcurrentHashMap<>();

    private static volatile String whitelistText = "";
    private static volatile List<String> whitelistPatterns = List.of();

    // Per-account auth throttle: this callback (org.littleshoot.proxy.ProxyAuthenticator) gets
    // only userName/password, no client IP, so — unlike AuthController's per-IP login lockout —
    // this is keyed by the attempted userId. Otherwise bcrypt cost is the only thing slowing an
    // online brute-force attempt against any HUB_USR account through this always-on, 0.0.0.0-bound
    // proxy port. In-memory only, same trade-off as AuthController's lockout. Backed by a
    // size-bounded, expiring cache (like AuthController's) so attempts against random userIds can't
    // grow memory without limit; each write refreshes an entry's expiry, so it outlives its lockout.
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final java.time.Duration ATTEMPT_WINDOW = java.time.Duration.ofMinutes(15);
    private static final java.time.Duration LOCKOUT_DURATION = java.time.Duration.ofMinutes(15);

    private record AuthAttempts(int count, java.time.Instant windowStart, java.time.Instant lockedUntil) {}

    private static final com.github.benmanes.caffeine.cache.Cache<String, AuthAttempts> authAttemptsByUser =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(ATTEMPT_WINDOW.plus(LOCKOUT_DURATION))
                    .maximumSize(10_000)
                    .build();

    // userId is attacker-controlled and unbounded; the key only needs to tell accounts apart.
    private static String attemptKey(String userId) {
        var key = userId.toLowerCase();
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

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

    public static void setWhitelist(String text, Long actorUserNo) {
        applyWhitelist(text != null ? text : "");
        new ProxyConfService(sqlSessionFactory).set(KEY_WHITELIST, null, whitelistText, actorUserNo);
    }

    private static void applyWhitelist(String text) {
        whitelistText = text;
        whitelistPatterns = text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> (line.startsWith("*.") ? line.substring(2) : line).toLowerCase())
                .toList();
    }

    /** True only when host matches one of the whitelist's patterns; an empty whitelist allows nothing. */
    public static boolean isWhitelisted(String host) {
        var patterns = whitelistPatterns;
        if (patterns.isEmpty() || host == null || host.isBlank()) return false;
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
                .plusActivityTracker(new ActivityTrackerAdapter() {
                    // Self-loop registration lives entirely here rather than in HttpFilters.
                    // proxyToServerConnectionSucceeded(), which looks purpose-built for this but
                    // never fires for a raw CONNECT tunnel (see LittleProxyInternals' javadoc).
                    // serverConnected() itself always fires, but its own FullFlowContext argument
                    // carries a permanently-null server context for the same reason - so this
                    // reaches past it via LittleProxyInternals for the live one instead.
                    @Override
                    public void serverConnected(FullFlowContext flowContext, InetSocketAddress serverAddress) {
                        if (serverAddress == null || serverAddress.getPort() != ReverseProxyServer.HTTPS_PORT) return;

                        var clientConnection = LittleProxyInternals.clientConnectionOf(flowContext);
                        if (clientConnection == null) return;
                        var clientDetails = clientConnection.getClientDetails();
                        String userId = clientDetails == null ? null : clientDetails.getUserName();
                        if (userId == null) return;

                        // Same self-loop test as overrideFor()'s PROXY_SVR_PLACEHOLDER branch and the
                        // literal-loopback-hosts-profile-entry case: this connection's destination is
                        // either loopback outright, or exactly the address the browser used to reach
                        // this forward proxy (the ${PROXY_SVR} case, which may not itself be loopback
                        // when the browser reaches this server over the LAN).
                        InetSocketAddress clientLocalAddr = clientConnection.getContext() != null
                                && clientConnection.getContext().channel().localAddress() instanceof InetSocketAddress a
                                ? a : null;
                        boolean isSelfLoop = serverAddress.getAddress().isLoopbackAddress()
                                || (clientLocalAddr != null
                                    && clientLocalAddr.getAddress().getHostAddress().equals(serverAddress.getAddress().getHostAddress()));
                        if (!isSelfLoop) return;

                        Long userNo = userNoMap.get(userId);
                        if (userNo == null) return;

                        ChannelHandlerContext liveServerCtx = LittleProxyInternals.liveProxyToServerContext(clientConnection);
                        if (liveServerCtx != null && liveServerCtx.channel().localAddress() instanceof InetSocketAddress local) {
                            logger.debug("Self-loop registry register: ip={} port={} userNo={}", local.getAddress().getHostAddress(), local.getPort(), userNo);
                            SelfLoopOwnerRegistry.register(local.getAddress().getHostAddress(), local.getPort(), userNo);
                        }
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
                                    boolean whitelisted = isWhitelisted(host);
                                    // A client whose own TCP connection to this proxy is itself loopback gains
                                    // nothing by tunneling to this server's loopback interface through the
                                    // proxy - it already has direct network access to every loopback-bound
                                    // port on this machine. The SSRF pivot this guard exists for (see
                                    // overrideFor()) only matters for a client that ISN'T already local.
                                    boolean clientIsLoopback = EXEMPT_LOOPBACK_CLIENTS && isLoopbackTarget(clientIp(ctx));
                                    boolean loopback = !clientIsLoopback && isLoopbackTarget(host);
                                    logger.info("Forward proxy match: user={} method={} host={} whitelisted={} loopbackTarget={} clientIsLoopback={}",
                                            authenticatedUser(ctx), request.method(), host, whitelisted, loopback, clientIsLoopback);
                                    if (loopback || !whitelisted) {
                                        // CONNECT (HTTPS): if the blocked-page server is up, let the tunnel
                                        // succeed here and redirect it there in overrideFor() below, so the
                                        // client completes a real TLS handshake and renders the 403 page
                                        // instead of just seeing the CONNECT itself fail. Otherwise (or for
                                        // plain HTTP, which renders a short-circuit response fine either way)
                                        // block immediately.
                                        if (request.method() != HttpMethod.CONNECT || !BlockedPageServer.isRunning()) {
                                            var reason = loopback ? BLOCK_REASON_LOOPBACK : BLOCK_REASON_WHITELIST;
                                            logger.info("Forward proxy blocked ({}): user={} host={}",
                                                    loopback ? "target is loopback/any-local" : "not whitelisted",
                                                    authenticatedUser(ctx), host);
                                            return blockedResponse(request, host, reason);
                                        }
                                    }
                                }
                                return null;
                            }

                            @Override
                            public InetSocketAddress proxyToServerResolutionStarted(String resolvingServerHostAndPort) {
                                return overrideFor(authenticatedUser(ctx), resolvingServerHostAndPort, localServerIp(ctx), clientIp(ctx));
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
        var key = attemptKey(userId);
        if (isAuthLocked(key)) {
            logger.debug("Forward proxy auth blocked (locked out) for userId={}", userId);
            return false;
        }
        try (var session = sqlSessionFactory.openSession()) {
            var user = session.getMapper(HubUserMapper.class).findByUserId(userId);
            // Always run exactly one bcrypt comparison, real user or not - see DUMMY_PASSWORD_HASH.
            var hashToCheck = user != null ? user.getPassword() : DUMMY_PASSWORD_HASH;
            var isCorrectPassword = PasswordUtil.matches(password, hashToCheck);
            // An account that may not hold a session (awaiting approval, suspended) must not get
            // through here either: this proxy is a second door to the same accounts as the login page.
            if (user == null || !isCorrectPassword || !Role.canLogin(user.getRole())) {
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
        var a = authAttemptsByUser.getIfPresent(key);
        return a != null && a.lockedUntil() != null && java.time.Instant.now().isBefore(a.lockedUntil());
    }

    private static void recordAuthFailure(String key) {
        var now = java.time.Instant.now();
        authAttemptsByUser.asMap().compute(key, (k, old) -> {
            var windowStart = old == null ? null : old.windowStart();
            var count = old == null ? 0 : old.count();
            var lockedUntil = old == null ? null : old.lockedUntil();
            if (windowStart == null || java.time.Duration.between(windowStart, now).compareTo(ATTEMPT_WINDOW) > 0) {
                windowStart = now;
                count = 0;
            }
            count++;
            if (count >= MAX_FAILED_ATTEMPTS) {
                lockedUntil = now.plus(LOCKOUT_DURATION);
            }
            return new AuthAttempts(count, windowStart, lockedUntil);
        });
    }

    private static void recordAuthSuccess(String key) {
        authAttemptsByUser.invalidate(key);
    }

    /** Recomputes and caches the given user's merged host->ip map from their selected oeHosts profiles. */
    public static void refreshUserHosts(HubUser user) {
        if (user == null || sqlSessionFactory == null) return;
        try {
            var selected = new HostsProfService(sqlSessionFactory).list(user.getUserNo()).stream()
                    .filter(HostsProf::isSelected)
                    .toList();
            userHostMap.put(user.getUserId(), mergeHosts(selected));
            userNoMap.put(user.getUserId(), user.getUserNo());
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

    /** The remote address of the client that connected to this (0.0.0.0-bound) forward-proxy port. */
    private static String clientIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress remote) {
            return remote.getAddress().getHostAddress();
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

    /** Builds the 403 response for a blocked destination, styled like oeProxy's other error pages. */
    private static HttpResponse blockedResponse(HttpRequest request, String host, String reason) {
        var locale = HtmlUtil.resolveLocale(
                request.headers().get(HttpHeaderNames.COOKIE),
                request.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE));
        var html = HtmlUtil.renderFwdProxyForbidden(host, reason, locale);
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
    static InetSocketAddress overrideFor(String userId, String hostAndPort, String localServerIp, String clientIp) {
        // A client whose own connection to this proxy is itself loopback gains nothing from
        // reaching this server's loopback interface through the proxy - it already has direct
        // network access to every loopback-bound port on this machine (that's exactly what "the
        // client is on 127.0.0.1" means). The three isLoopbackTarget()/isLoopbackAddress() guards
        // below exist to stop a genuinely remote client from using an authored oeHosts entry (or a
        // rebinding DNS answer) as an SSRF pivot into this server's own loopback interface - that
        // risk doesn't apply here, so this client is exempted from all three.
        boolean clientIsLoopback = EXEMPT_LOOPBACK_CLIENTS && isLoopbackTarget(clientIp);

        // Only reachable here for a blocked host (loopback target, or non-whitelisted) when the
        // CONNECT was deliberately let through by clientToProxyRequest because BlockedPageServer
        // is up (see there) - redirect the tunnel to it instead of the real destination. Must
        // mirror clientToProxyRequest's block condition exactly, or a host that satisfies one
        // check but not the other would fall through to a real connection below.
        var target = hostOnly(hostAndPort);
        boolean targetIsLoopback = !clientIsLoopback && isLoopbackTarget(target);
        boolean targetIsWhitelisted = isWhitelisted(target);
        if (targetIsLoopback || !targetIsWhitelisted) {
            logger.info("Forward proxy override: user={} host={} clientIsLoopback={} blocked=true ({})",
                    userId, target, clientIsLoopback,
                    targetIsLoopback ? "target is loopback/any-local, second check" : "not whitelisted, second check");
            rememberBlockReason(target, targetIsLoopback ? BLOCK_REASON_LOOPBACK : BLOCK_REASON_WHITELIST);
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
        }

        HostPort hp = splitHostAndPort(hostAndPort, 80);
        String host = hp.host();
        int port = hp.port();

        String ip = userId == null ? null : userHostMap.getOrDefault(userId, Map.of()).get(host.toLowerCase());
        logger.info("Forward proxy override: user={} host={} hostMapHit={} ip={} clientIsLoopback={}",
                userId, host, ip != null, ip, clientIsLoopback);
        if (ip != null) {
            if (PROXY_SVR_PLACEHOLDER.equals(ip)) {
                if (localServerIp == null) return null;
                ip = localServerIp;
            } else if (!clientIsLoopback && isLoopbackTarget(ip)) {
                // A user's own oeHosts profile is free-text content they authored (mergeHosts()
                // parses arbitrary "ip hostname" lines from it) - a line like "127.0.0.1 evil.local"
                // would otherwise let them CONNECT to a hostname that passes both checks above,
                // then have this override map silently redirect the tunnel to this server's own
                // loopback interface, the same SSRF pivot isLoopbackTarget(target) blocks for the
                // literal-host case. PROXY_SVR_PLACEHOLDER is exempt: it resolves to this server's
                // real network-facing IP as seen by the client, not its loopback interface.
                logger.info("Forward proxy override: user={} host={} blocked=true (hosts-profile entry points at loopback)",
                        userId, host);
                rememberBlockReason(host, BLOCK_REASON_LOOPBACK);
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
            }
            try {
                byte[] addr = InetAddress.getByName(ip).getAddress();
                InetAddress forced = InetAddress.getByAddress(host, addr);
                return new InetSocketAddress(forced, port);
            } catch (UnknownHostException e) {
                logger.info("Forward proxy override: user={} host={} overrideIp={} is itself unresolvable: {}",
                        userId, host, ip, e.getMessage());
                return null;
            }
        }

        // No host-map override: target isn't a literal IP/"localhost" (isLoopbackTarget(target)
        // above already returned false unless the client is loopback), so resolve it ourselves and
        // check the *resolved* address before connecting - otherwise an attacker-registered domain
        // whose DNS answer is loopback/any-local (DNS rebinding) would sail through every
        // string-based check here and reach this server's own loopback interface once LittleProxy
        // resolves it independently. Pinning the address we just checked (rather than returning
        // null and letting LittleProxy resolve again) also closes the TOCTOU window a rebinding DNS
        // server could otherwise use.
        try {
            var future = DNS_RESOLVER.submit(() -> InetAddress.getByName(host));
            var resolved = future.get(DNS_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!clientIsLoopback && (resolved.isLoopbackAddress() || resolved.isAnyLocalAddress())) {
                logger.info("Forward proxy blocked (DNS-rebind to loopback): host={} resolvedTo={}",
                        host, resolved.getHostAddress());
                rememberBlockReason(host, BLOCK_REASON_LOOPBACK);
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
            }
            logger.info("Forward proxy override: user={} host={} hostMapHit=false resolvedViaRealDns={}",
                    userId, host, resolved.getHostAddress());
            var forced = InetAddress.getByAddress(host, resolved.getAddress());
            return new InetSocketAddress(forced, port);
        } catch (Exception e) {
            // Same fail-closed redirect as an actual whitelist/DNS-rebind block, but the cause
            // here is just as often mundane (host not in any selected oeHosts profile and genuinely
            // unresolvable, e.g. a fictitious/internal-only TLD, or DNS took longer than
            // DNS_RESOLVE_TIMEOUT_MS) - log it so "why did my request get blocked?" is answerable
            // from the log instead of indistinguishable from a real whitelist rejection.
            logger.info("Forward proxy blocked (no hosts-profile override and real DNS lookup failed): " +
                    "user={} host={}: {}", userId, host, e.getMessage());
            rememberBlockReason(host, BLOCK_REASON_UNRESOLVED);
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
        }
    }
}
