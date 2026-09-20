package tricatch.oe.hosts.controller;

import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/**
 * Works out the PROXY_SVR address shown on hosts pages (and substituted into their content): the
 * IP of the host name the visitor connected to, falling back to this server's own address.
 *
 * <p>The name comes from the request's Host header, which the caller controls. Looking up whatever
 * name arrives would let anyone make the server run DNS lookups for names of their choosing, so
 * only a name on the admin's allowed-domains list is looked up - for every caller, logged in or not. Every
 * other name - and any failed lookup - falls back to the server's own address. IP address literals
 * need no lookup, so they always pass.
 */
final class ProxyIpResolver {

    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}([.][0-9]{1,3}){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private ProxyIpResolver() {}

    // An IP address literal (IPv4, or IPv6 without the brackets): resolving one never touches DNS.
    static boolean isIpLiteral(String host) {
        return IPV4.matcher(host).matches() || (host.indexOf(':') >= 0 && IPV6_CHARS.matcher(host).matches());
    }

    /**
     * @param hostHeader   the raw Host header (may carry a port, or IPv6 brackets), possibly null
     * @param localAddress the server-side address the request arrived on, used as the fallback
     * @param allowed      whether a host name is on the allowed-domains list (an empty list allows none)
     * @param lookup       resolves a host name (or literal) to an IP string, or null when it fails
     *                     or times out; only ever called for names this method lets through
     */
    static String resolve(String hostHeader, String localAddress, Predicate<String> allowed,
                          Function<String, String> lookup) {
        if (hostHeader == null || hostHeader.isBlank()) return localAddress;

        String hostname;
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            hostname = end > 0 ? hostHeader.substring(1, end) : hostHeader;
        } else {
            int colon = hostHeader.indexOf(':');
            hostname = colon > 0 ? hostHeader.substring(0, colon) : hostHeader;
        }

        boolean literal = isIpLiteral(hostname);
        if (!literal && !allowed.test(hostname)) {
            return localAddress;
        }

        var resolved = lookup.apply(hostname);
        return resolved != null ? resolved : localAddress;
    }
}
