package tricatch.oe.hub.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.time.Instant;

/**
 * Blocks an address that is probing for paths: {@link #NOT_FOUND_LIMIT} anonymous 404s within
 * {@link #WINDOW} block every request from it for {@link #BLOCK_DURATION}.
 *
 * <p>Only anonymous requests are counted (the caller checks that): a logged-in user can hit
 * legitimate 404s (a deleted shared profile, a retried save) and is not a scanner. Loopback is
 * never counted, so a missing {@code -Doe.client.ip.header} behind a local proxy - where every
 * client shares loopback - can't end up blocking everyone (see {@link ClientIp}).
 */
public final class ScanBlocker {

    static final int NOT_FOUND_LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final Duration BLOCK_DURATION = Duration.ofMinutes(10);

    private record Counter(int count, Instant windowStart, Instant blockedUntil) {}

    // Size-bounded and expiring like AuthController's throttles: a client cycling through addresses
    // must not be able to grow this without limit. Each write refreshes an entry's expiry, so it
    // always outlives its own block.
    private static final Cache<String, Counter> BY_IP = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW.plus(BLOCK_DURATION))
            .maximumSize(10_000)
            .build();

    private ScanBlocker() {}

    public static boolean isBlocked(String ip) {
        if (ip == null) return false;
        var c = BY_IP.getIfPresent(ip);
        return c != null && c.blockedUntil() != null && Instant.now().isBefore(c.blockedUntil());
    }

    /** Counts one anonymous 404 from {@code ip}; true when this one started a block. */
    public static boolean recordNotFound(String ip) {
        if (ip == null || ClientIp.isLoopback(ip)) return false;
        var now = Instant.now();
        var started = new boolean[1];
        BY_IP.asMap().compute(ip, (k, cur) -> {
            if (cur != null && cur.blockedUntil() != null && now.isBefore(cur.blockedUntil())) return cur;
            if (cur == null || !now.isBefore(cur.windowStart().plus(WINDOW))) cur = new Counter(0, now, null);
            int count = cur.count() + 1;
            if (count >= NOT_FOUND_LIMIT) {
                started[0] = true;
                return new Counter(count, cur.windowStart(), now.plus(BLOCK_DURATION));
            }
            return new Counter(count, cur.windowStart(), null);
        });
        return started[0];
    }
}
