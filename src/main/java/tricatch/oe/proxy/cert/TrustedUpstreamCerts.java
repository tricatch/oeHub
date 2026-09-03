package tricatch.oe.proxy.cert;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.proxy.service.ProxyConfService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Admin-managed allowlist of upstream (origin server) TLS certificate fingerprints, keyed by
 * backend host. Used by {@link tricatch.oe.proxy.util.SocketUtils#createHttps} as a pinned-cert
 * fallback for upstream certificates that don't chain-validate against the JVM's default trust
 * store (self-signed / internal certs) — mirrors the persistence pattern
 * {@code tricatch.oe.fwdproxy.ForwardProxyServer}'s relay whitelist uses (a single delimited text
 * blob in PROXY_CONF, cached in memory, reloaded on save).
 *
 * Text format: one entry per line, "host sha256fingerprint [label...]" (whitespace-separated;
 * fingerprint is the hex SHA-256 of the DER-encoded certificate, colons optional). Blank lines
 * and lines starting with '#' are ignored.
 */
public class TrustedUpstreamCerts {

    private static final String KEY = "upstream.trustedCerts";

    private static SqlSessionFactory sqlSessionFactory;
    private static volatile String text = "";
    private static volatile Map<String, Set<String>> fingerprintsByHost = Map.of();

    public static void init(SqlSessionFactory factory) {
        sqlSessionFactory = factory;
        var stored = new ProxyConfService(factory).get(KEY, null);
        apply(stored != null ? stored : "");
    }

    public static String getText() {
        return text;
    }

    public static void setText(String newText) {
        apply(newText != null ? newText : "");
        new ProxyConfService(sqlSessionFactory).set(KEY, null, text);
    }

    /** Package-visible (not just private) so tests can seed the in-memory cache without a DB. */
    static void apply(String newText) {
        text = newText;
        Map<String, Set<String>> map = new HashMap<>();
        for (String line : newText.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+", 3);
            if (parts.length < 2) continue;
            String host = parts[0].toLowerCase();
            String fingerprint = parts[1].toLowerCase().replace(":", "");
            map.computeIfAbsent(host, h -> new HashSet<>()).add(fingerprint);
        }
        fingerprintsByHost = map;
    }

    /** True when {@code sha256FingerprintHex} (colons optional, any case) is approved for {@code host}. */
    public static boolean isTrusted(String host, String sha256FingerprintHex) {
        if (host == null || sha256FingerprintHex == null) return false;
        var set = fingerprintsByHost.get(host.toLowerCase());
        return set != null && set.contains(sha256FingerprintHex.toLowerCase().replace(":", ""));
    }
}
