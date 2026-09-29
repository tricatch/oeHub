package tricatch.oe.hub.proxy;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.i18n.Messages;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.GatewayTimeoutException;
import tricatch.oe.proxy.spi.ClientHints;
import tricatch.oe.proxy.spi.ErrorPageRenderer;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * oeHub's branded rendering of the reverse-proxy (oe-proxy library) error pages, registered with
 * the library via {@code ReverseProxyServer.setErrorPageRenderer} - see ReverseProxySettings.init.
 * Each method returns null (falling back to the library's built-in plain page) on any failure.
 */
public class PebbleErrorPageRenderer implements ErrorPageRenderer {

    private static final Logger logger = LoggerFactory.getLogger(PebbleErrorPageRenderer.class);

    private static final PebbleEngine pebble = buildPebble();

    private static PebbleEngine buildPebble() {
        var builder = new PebbleEngine.Builder()
            .autoEscaping(true)
            .defaultEscapingStrategy("html");
        if ("true".equals(System.getProperty("oe.dev"))) {
            var prefix = java.nio.file.Path.of("src/main/resources").toAbsolutePath() + "/";
            builder.loader(new io.pebbletemplates.pebble.loader.FileLoader(prefix)).cacheActive(false);
        }
        return builder.build();
    }

    private static final Map<String, Map<String, String>> MESSAGES = Map.of(
        "en", new Messages("en").asMap(),
        "ko", new Messages("ko").asMap()
    );

    private static Map<String, String> msg(String locale) {
        return MESSAGES.getOrDefault(locale, MESSAGES.get("en"));
    }

    public static String resolveLocale(String cookieHeader, String acceptLanguage) {
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("oe_lang=")) {
                    String lang = trimmed.substring(8).trim();
                    if ("en".equals(lang) || "ko".equals(lang)) return lang;
                }
            }
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            String lang = acceptLanguage.split("[,;]")[0].trim();
            if (lang.length() >= 2) lang = lang.substring(0, 2).toLowerCase();
            if ("en".equals(lang) || "ko".equals(lang)) return lang;
        }
        return "en";
    }

    @Override
    public String badGateway(BadGatewayException ex, ClientHints hints) {
        try {
            var locale = resolveLocale(hints.cookie(), hints.acceptLanguage());
            PebbleTemplate template = pebble.getTemplate("templates/error/proxy-bad-gateway.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",          msg(locale));
            ctx.put("rid",          ex.getRid());
            ctx.put("requestHost",  ex.getRequestHost());
            ctx.put("routePath",    ex.getRoutePath());
            ctx.put("targetUrl",    ex.getTargetUrl());
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            return writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render bad-gateway template", e);
            return null;
        }
    }

    @Override
    public String gatewayTimeout(GatewayTimeoutException ex, ClientHints hints) {
        try {
            var locale = resolveLocale(hints.cookie(), hints.acceptLanguage());
            PebbleTemplate template = pebble.getTemplate("templates/error/proxy-gateway-timeout.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",          msg(locale));
            ctx.put("rid",          ex.getRid());
            ctx.put("requestHost",  ex.getRequestHost());
            ctx.put("routePath",    ex.getRoutePath());
            ctx.put("targetUrl",    ex.getTargetUrl());
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            return writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render gateway-timeout template", e);
            return null;
        }
    }

    @Override
    public String notFoundVhost(String requestHost, String requestPath, ClientHints hints) {
        try {
            var locale = resolveLocale(hints.cookie(), hints.acceptLanguage());
            PebbleTemplate template = pebble.getTemplate("templates/error/proxy-not-found-vhost.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",         msg(locale));
            ctx.put("requestHost", requestHost != null ? requestHost : "");
            ctx.put("requestPath", requestPath != null ? requestPath : "");
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            return writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render proxy-not-found-vhost template", e);
            return null;
        }
    }

    @Override
    public String noVhosts(String clientIp, String oidHeader, boolean ipIdentifierEnabled, ClientHints hints) {
        try {
            var locale = resolveLocale(hints.cookie(), hints.acceptLanguage());
            PebbleTemplate template = pebble.getTemplate("templates/error/proxy-no-vhosts.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",       msg(locale));
            ctx.put("clientIp",  clientIp);
            ctx.put("oidHeader", oidHeader != null ? oidHeader : "");
            ctx.put("ipEnabled", ipIdentifierEnabled);
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            return writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render proxy-no-vhosts template", e);
            return null;
        }
    }

    /**
     * Renders the forward-proxy 403 page as HTML (caller owns the transport / response framing).
     * {@code reason} is one of ForwardProxyServer's BLOCK_REASON_* constants (currently "loopback"
     * or "whitelist") and picks which explanation/remedy copy the page shows - anything else
     * (including null, e.g. a reason that expired before BlockedPageServer could read it) falls
     * back to the whitelist copy, matching this page's behavior before reasons were tracked.
     */
    public static String renderFwdProxyForbidden(String host, String reason, String locale) {
        try {
            PebbleTemplate template = pebble.getTemplate("templates/error/fwdproxy-forbidden.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",    msg(locale));
            ctx.put("host",   host != null ? host : "");
            ctx.put("reason", reason != null ? reason : "");
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            return writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render fwdproxy-forbidden template", e);
            return "<html><body><h1>403 Forbidden</h1><p>Blocked by oeHub forward-proxy: "
                    + escapeHtml(host) + "</p></body></html>";
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
