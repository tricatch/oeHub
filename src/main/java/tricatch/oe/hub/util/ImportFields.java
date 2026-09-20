package tricatch.oe.hub.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed access to the fields of a user-supplied import/restore file. A wrong shape is reported as
 * an {@link IllegalArgumentException} whose message is safe to show the caller, so the handler can
 * answer 400 - instead of a ClassCastException or NullPointerException surfacing as a 500 - and
 * before anything has been written.
 */
public final class ImportFields {

    private ImportFields() {}

    /** A JSON array of objects. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object raw, String what) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + what + "' must be a list");
        }
        var result = new ArrayList<Map<String, Object>>(list.size());
        for (var item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Every entry of '" + what + "' must be an object");
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    /** A JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object raw, String what) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("'" + what + "' must be an object");
        }
        return (Map<String, Object>) map;
    }

    /** A JSON object whose values are all text. */
    public static Map<String, String> textMap(Object raw, String what) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : map(raw, what).entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException("'" + what + "." + entry.getKey() + "' must be text");
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    /** Text that must be present and not blank. */
    public static String requiredText(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("Every entry needs a non-empty '" + key + "'");
        }
        return value;
    }

    /** Optional text: {@code fallback} when absent, an error when present but not text. */
    public static String text(Map<String, Object> m, String key, String fallback) {
        var value = m.get(key);
        if (value == null) return fallback;
        if (value instanceof String s) return s;
        throw new IllegalArgumentException("'" + key + "' must be text");
    }

    /** Optional whole number: {@code fallback} when absent, an error when present but not a number. */
    public static int intValue(Map<String, Object> m, String key, int fallback) {
        var value = m.get(key);
        if (value == null) return fallback;
        if (value instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("'" + key + "' must be a number");
    }
}
