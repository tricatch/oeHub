package tricatch.oe.hub.util;

public class HtmlJsonUtil {

    private HtmlJsonUtil() {}

    /**
     * Escapes a JSON string for safe inline embedding inside an HTML {@code <script>} block.
     * Jackson does not escape '/' by default, so a JSON string value containing
     * "&lt;/script&gt;" would otherwise close the enclosing script tag early and let the
     * remainder be parsed as HTML — a stored-XSS vector whenever the JSON was built from
     * user-controlled content (e.g. a shared vhost/hosts profile). {@code <}/{@code >}
     * are valid JSON escapes for '&lt;'/'&gt;', so this is a no-op for JSON semantics.
     */
    public static String escapeForScript(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e");
    }
}
