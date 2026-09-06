package tricatch.oe.proxy.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * The IP-based fallback for reverse-proxy owner resolution when a request carries no
 * X-OeHub-Oid header (see ReverseProxyServer.resolveOid()). Replaces the old implicit
 * IP-to-owner map (which was written as a side effect of applying vhost config or of a lazy
 * cache-miss reload, and never expired) with an explicit, time-bounded claim: an owner only ends
 * up here by deliberately clicking "Use This IP" on the oeProxy page (see
 * ProxyController.apiTakeIp / ReverseProxyServer.claimIp), so an entry always reflects a recent,
 * intentional choice rather than a stale side effect of some unrelated request.
 *
 * expireAfterWrite bounds how long a claim stays valid. Claiming a new IP for an owner releases
 * any previous claim for that same owner first, since only one IP can meaningfully be "current"
 * for routing purposes at a time - otherwise an owner who claims from home, then later from work,
 * would silently leave two IPs both routing to them indefinitely.
 */
public class ClaimedIpRegistry {

    private static final Duration TTL = Duration.ofHours(8);

    private static final Cache<String, String> IP_TO_OID = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .build();

    private ClaimedIpRegistry() {}

    public static void claim(String ip, String oid) {
        if (ip == null || oid == null) return;
        release(oid);
        IP_TO_OID.put(ip, oid);
    }

    /** Drops this owner's claim, whichever IP currently holds it (if any). */
    public static void release(String oid) {
        if (oid == null) return;
        IP_TO_OID.asMap().values().removeIf(oid::equals);
    }

    public static String get(String ip) {
        return ip == null ? null : IP_TO_OID.getIfPresent(ip);
    }

    public static long ttlHours() {
        return TTL.toHours();
    }
}
