package tricatch.oe.hub.config;

/**
 * Whether cookies oeHub sets carry {@code Secure} (and the response carries HSTS).
 *
 * <p>By default that follows the request itself: a request that arrived over HTTPS gets them, a plain
 * HTTP one does not - unconditionally adding {@code Secure} would make the cookie silently stop
 * being sent on a plain-HTTP deployment. Behind a proxy that ends TLS the app only ever sees HTTP,
 * so such a deployment sets {@code -Doe.secure.cookie=true} to say the site is served over HTTPS.
 */
public final class CookieSecurity {

    private CookieSecurity() {}

    /** {@code -Doe.secure.cookie=true}: the site is reached over HTTPS whatever this hop sees. */
    public static boolean forced() {
        return "true".equalsIgnoreCase(System.getProperty("oe.secure.cookie"));
    }

    public static boolean isSecure(String requestScheme) {
        return forced() || "https".equalsIgnoreCase(requestScheme);
    }
}
