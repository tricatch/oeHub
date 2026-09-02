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
                                    if (!isWhitelisted(host)) {
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

    private static boolean authenticate(String userId, String password) {
        if (userId == null || password == null) return false;
        try (var session = sqlSessionFactory.openSession()) {
            var user = session.getMapper(HubUserMapper.class).findByUserId(userId);
            if (user == null || !PasswordUtil.matches(password, user.getPassword())) {
                logger.debug("Forward proxy auth failed for userId={}", userId);
                return false;
            }
            refreshUserHosts(user);
            return true;
        } catch (Exception e) {
            logger.warn("Forward proxy auth error for userId={}: {}", userId, e.getMessage());
            return false;
        }
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
        int idx = hostAndPort.lastIndexOf(':');
        return idx >= 0 ? hostAndPort.substring(0, idx) : hostAndPort;
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

    /** Looks up host:port in the user's cached map; returns null (=normal DNS) when there's no override. */
    static InetSocketAddress overrideFor(String userId, String hostAndPort, String localServerIp) {
        // Only reachable here for a non-whitelisted host when the CONNECT was deliberately let
        // through by clientToProxyRequest because BlockedPageServer is up (see there) - redirect
        // the tunnel to it instead of the real destination.
        if (!isWhitelisted(hostOnly(hostAndPort))) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), BlockedPageServer.getPort());
        }

        if (userId == null) return null;
        var map = userHostMap.get(userId);
        if (map == null || map.isEmpty()) return null;

        String host;
        int port;
        int idx = hostAndPort.lastIndexOf(':');
        if (idx >= 0) {
            host = hostAndPort.substring(0, idx);
            port = Integer.parseInt(hostAndPort.substring(idx + 1));
        } else {
            host = hostAndPort;
            port = 80;
        }

        String ip = map.get(host.toLowerCase());
        if (ip == null) return null;
        if (PROXY_SVR_PLACEHOLDER.equals(ip)) {
            if (localServerIp == null) return null;
            ip = localServerIp;
        }

        try {
            byte[] addr = InetAddress.getByName(ip).getAddress();
            InetAddress forced = InetAddress.getByAddress(host, addr);
            return new InetSocketAddress(forced, port);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
