package tricatch.oe.proxy.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * Correlates a forward-proxy-authenticated user with the self-loop TCP connection the forward
 * proxy opens back into oeHub's own reverse proxy when a ${PROXY_SVR} oeHosts override routes a
 * request there. Both proxies run in the same JVM, so instead of trying to inject an
 * X-OeHub-Oid header into what is, for HTTPS, an opaque CONNECT tunnel, the forward proxy
 * registers the local (ephemeral) port it used to dial the reverse proxy, and the reverse proxy
 * looks itself up by the remote port of the socket it just accepted — by TCP definition, the same
 * port number seen from either end of that one connection (see ForwardProxyServer's
 * proxyToServerConnectionSucceeded and PassRequestExecutor.run()).
 *
 * A registration is normally consumed within milliseconds (the reverse proxy reads it the instant
 * it accepts the connection); expireAfterWrite only bounds the rare case where a registered
 * connection never gets accepted (e.g. blocked/redirected before reaching the reverse proxy, or a
 * client that opens then abandons the tunnel), so an unconsumed entry doesn't linger forever.
 */
public class SelfLoopOwnerRegistry {

    private static final Cache<Integer, Long> PORT_TO_USER_NO = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(1000)
            .build();

    private SelfLoopOwnerRegistry() {}

    public static void register(int localPort, Long userNo) {
        if (userNo != null) PORT_TO_USER_NO.put(localPort, userNo);
    }

    /** Looks up and removes the registration for this port, if any — consume-on-read. */
    public static Long take(int localPort) {
        return PORT_TO_USER_NO.asMap().remove(localPort);
    }
}
