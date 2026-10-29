package tricatch.oe.hub.util;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Pebble {@code asset(path)} function: returns a static file's URL with a content-hash query
 * ({@code /js/util.js?v=1a2b3c4d}), so browsers and any caching proxy in front of oeHub fetch a
 * JS/CSS file again exactly when its content changes, and keep using the cached copy otherwise.
 * <p>
 * In dev mode the file is read from {@code src/main/resources/static} on every call (edits show
 * up without a restart); otherwise from the classpath, hashed once per path. A path that does not
 * resolve to a file is returned unchanged.
 */
public class AssetUrlExtension extends AbstractExtension {

    private final boolean dev;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public AssetUrlExtension(boolean dev) {
        this.dev = dev;
    }

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of("asset", new Function() {
            @Override
            public List<String> getArgumentNames() {
                return List.of("path");
            }

            @Override
            public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
                return url(String.valueOf(args.get("path")));
            }
        });
    }

    String url(String path) {
        if (dev) return withVersion(path);
        return cache.computeIfAbsent(path, this::withVersion);
    }

    private String withVersion(String path) {
        byte[] bytes = read(path);
        if (bytes == null) return path;
        var crc = new CRC32();
        crc.update(bytes);
        return path + "?v=" + String.format("%08x", crc.getValue());
    }

    private byte[] read(String path) {
        if (!path.startsWith("/") || path.contains("..")) return null;
        try {
            if (dev) {
                var file = Path.of("src/main/resources/static" + path);
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }
            try (InputStream in = AssetUrlExtension.class.getResourceAsStream("/static" + path)) {
                return in != null ? in.readAllBytes() : null;
            }
        } catch (IOException e) {
            return null;
        }
    }
}
