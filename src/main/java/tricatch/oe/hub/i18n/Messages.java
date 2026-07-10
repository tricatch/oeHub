package tricatch.oe.hub.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class Messages {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> map = new LinkedHashMap<>();
    private final String json;

    public Messages(String locale) {
        load("i18n/messages_en.properties");
        if (!"en".equals(locale)) load("i18n/messages_" + locale + ".properties");
        String serialized;
        try { serialized = MAPPER.writeValueAsString(map); }
        catch (Exception e) { serialized = "{}"; }
        json = serialized;
    }

    private void load(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return;
            var props = new Properties();
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            props.stringPropertyNames().stream().sorted()
                 .forEach(k -> map.put(k, props.getProperty(k)));
        } catch (IOException ignored) {}
    }

    public Map<String, String> asMap() { return Collections.unmodifiableMap(map); }
    public String toJson()             { return json; }
}
