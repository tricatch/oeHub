package tricatch.oe.hub.config;

import io.javalin.http.Context;

/**
 * The address a request really came from.
 *
 * <p>Behind a reverse proxy or tunnel on the same machine (e.g. cloudflared) every request arrives
 * from loopback, so per-address throttles and blocks would lump every client together. With
 * {@code -Doe.client.ip.header=<name>} the address that proxy puts in that header
 * ({@code CF-Connecting-IP}, {@code X-Forwarded-For}, ...) is used instead - but only when the
 * request itself came from loopback, so a client reaching the port directly can't choose its own
 * address by sending the header. Without the option this is just {@code ctx.ip()}.
 */
public final class ClientIp {

    private static final String HEADER = blankToNull(System.getProperty("oe.client.ip.header"));

    private ClientIp() {}

    public static String of(Context ctx) {
        var peer = ctx.ip();
        if (HEADER == null || !isLoopback(peer)) return peer;
        var forwarded = ctx.header(HEADER);
        if (forwarded == null) return peer;
        // A list (X-Forwarded-For) ends with the address the trusted proxy itself saw; entries before
        // it came from the client and can be anything.
        var parts = forwarded.split(",");
        var candidate = parts[parts.length - 1].trim();
        return looksLikeIp(candidate) ? candidate : peer;
    }

    public static boolean isLoopback(String ip) {
        if (ip == null) return false;
        var bare = ip.startsWith("[") && ip.endsWith("]") ? ip.substring(1, ip.length() - 1) : ip;
        return bare.startsWith("127.") || bare.equals("::1") || bare.equals("0:0:0:0:0:0:0:1");
    }

    // Literal IPv4/IPv6 characters only - never resolved, never a hostname.
    private static boolean looksLikeIp(String s) {
        if (s.isEmpty() || s.length() > 45) return false;
        return s.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == '.' || c == ':');
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
