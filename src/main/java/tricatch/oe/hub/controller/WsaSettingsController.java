package tricatch.oe.hub.controller;

import io.javalin.http.Context;

import java.util.HashMap;

/**
 * The workspace admin's settings page: the shared Open URL / User-Agent presets of their own
 * workspace. The presets themselves are read and written through the "/api/wsa/hosts/*" routes
 * (AdminHostsUrlController / AdminHostsUaController); the page is only the shell around them.
 */
public class WsaSettingsController {

    public void showSettings(Context ctx) {
        var model = new HashMap<String, Object>();
        model.put("user", AuthController.currentUser(ctx));
        ctx.render("templates/oehub/wsa-settings.pebble", model);
    }
}
