package tricatch.oe.proxy.util;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.i18n.Messages;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HtmlUtil {

    private static final Logger logger = LoggerFactory.getLogger(HtmlUtil.class);

    private static final PebbleEngine pebble = buildPebble();

    private static PebbleEngine buildPebble() {
        var builder = new PebbleEngine.Builder()
            .autoEscaping(true)
            .defaultEscapingStrategy("html");
        if ("true".equals(System.getProperty("dev"))) {
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

    public static void writeBadGatewayResponse(HttpStreamWriter out, BadGatewayException ex, String locale) throws IOException {
        String html;
        try {
            PebbleTemplate template = pebble.getTemplate("templates/error/proxy-bad-gateway.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",          msg(locale));
            ctx.put("rid",          ex.getRid());
            ctx.put("requestHost",  ex.getRequestHost());
            ctx.put("routePath",    ex.getRoutePath());
            ctx.put("targetUrl",    ex.getTargetUrl());
            //Throwable cause = ex.getCause();
            //ctx.put("errorMessage", cause != null ? cause.getMessage() : null);
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            html = writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render bad-gateway template", e);
            html = "<html><body><h1>502 Bad Gateway</h1><p>" + escapeHtml(ex.getMessage()) + "</p></body></html>";
        }

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        out.write("HTTP/1.1 502 Bad Gateway\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/html; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Connection: close\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(HTTP.CRLF);
        out.write(body);
        out.flush();
    }

    public static void writeNotFoundVhostResponse(HttpStreamWriter out, String requestHost, String requestPath, String locale) throws IOException {
        String html;
        try {
            PebbleTemplate template = pebble.getTemplate("templates/error/proxy-not-found-vhost.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",         msg(locale));
            ctx.put("requestHost", requestHost != null ? requestHost : "");
            ctx.put("requestPath", requestPath != null ? requestPath : "");
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            html = writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render proxy-not-found-vhost template", e);
            html = "<html><body><h1>404 Not Found</h1><p>No proxy route configured for " + escapeHtml(requestHost) + "</p></body></html>";
        }

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        out.write("HTTP/1.1 404 Not Found\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/html; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Connection: close\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(HTTP.CRLF);
        out.write(body);
        out.flush();
    }

    public static void writeNoVhostsResponse(HttpStreamWriter out, String clientIp, String oidHeader, String locale) throws IOException {
        String html;
        try {
            PebbleTemplate template = pebble.getTemplate("templates/error/proxy-no-vhosts.pebble");
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("msg",       msg(locale));
            ctx.put("clientIp",  clientIp);
            ctx.put("oidHeader", oidHeader != null ? oidHeader : "");
            ctx.put("ipEnabled", ReverseProxyServer.isIpIdentifierEnabled());
            StringWriter writer = new StringWriter();
            template.evaluate(writer, ctx);
            html = writer.toString();
        } catch (Exception e) {
            logger.warn("Failed to render proxy-no-vhosts template", e);
            html = "<html><body><h1>503 Service Unavailable</h1><p>No virtual host configuration is active. Please configure oeProxy in oeHub.</p></body></html>";
        }

        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        out.write("HTTP/1.1 503 Service Unavailable\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/html; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Connection: close\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(HTTP.CRLF);
        out.write(body);
        out.flush();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

}
