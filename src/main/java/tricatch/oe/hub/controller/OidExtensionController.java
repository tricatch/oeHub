package tricatch.oe.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class OidExtensionController {

    private static final String EXTENSION_RESOURCE_ROOT = "/oeoid-extension/";
    private static final List<String> EXTENSION_FILES = List.of(
            "manifest.json",
            "background.js",
            "options.html",
            "options.js",
            "_locales/en/messages.json",
            "_locales/ko/messages.json",
            "icons/icon16.png",
            "icons/icon32.png",
            "icons/icon48.png",
            "icons/icon128.png"
    );

    private final SettingsController settingsController;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OidExtensionController(SettingsController settingsController) {
        this.settingsController = settingsController;
    }

    public void apiDownload(Context ctx) throws IOException {
        var user = AuthController.currentUser(ctx);

        var domainListText = settingsController.getOidDomainListForUser(user.getUserNo());
        var domains = domainListText.lines().map(String::trim).filter(s -> !s.isBlank()).toList();

        var config = new LinkedHashMap<String, Object>();
        config.put("oid", user.getOid());
        config.put("domains", domains);
        var configJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);

        var bytes = buildZip(configJson);

        ctx.res().setContentType("application/zip");
        ctx.res().setHeader("Content-Disposition", "attachment; filename=\"oeOID-extension.zip\"");
        ctx.res().setContentLength(bytes.length);
        ctx.res().getOutputStream().write(bytes);
    }

    private byte[] buildZip(String configJson) throws IOException {
        var byteOut = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(byteOut)) {
            for (String file : EXTENSION_FILES) {
                zipOut.putNextEntry(new ZipEntry("oeOID/" + file));
                try (InputStream in = getClass().getResourceAsStream(EXTENSION_RESOURCE_ROOT + file)) {
                    if (in == null) throw new IOException("Missing extension resource: " + file);
                    in.transferTo(zipOut);
                }
                zipOut.closeEntry();
            }

            zipOut.putNextEntry(new ZipEntry("oeOID/default-config.json"));
            zipOut.write(configJson.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return byteOut.toByteArray();
    }
}
