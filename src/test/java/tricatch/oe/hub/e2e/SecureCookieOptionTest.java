package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind a proxy that ends TLS the app only sees plain HTTP, so -Doe.secure.cookie=true has to make
 * it mark its cookies Secure and send HSTS anyway; without the option a plain-HTTP request must get
 * neither (a Secure cookie would silently stop being sent on a plain-HTTP deployment).
 */
@Tag("e2e")
class SecureCookieOptionTest {

    private static E2eServer plain;
    private static E2eServer forced;
    private static Playwright playwright;

    @BeforeAll
    static void startAll() throws Exception {
        plain = new E2eServer(39942);
        plain.start();
        forced = new E2eServer(39945, List.of("-Doe.secure.cookie=true"));
        forced.start();
        playwright = Playwright.create();
    }

    @AfterAll
    static void stopAll() {
        if (playwright != null) playwright.close();
        if (forced != null) forced.close();
        if (plain != null) plain.close();
    }

    private static String setCookie(String url) {
        var response = playwright.request().newContext().get(url + "/setup");
        return String.join("\n", response.headersArray().stream()
            .filter(h -> h.name.equalsIgnoreCase("set-cookie")).map(h -> h.value).toList());
    }

    private static String hsts(String url) {
        var response = playwright.request().newContext().get(url + "/setup");
        return response.headers().get("strict-transport-security");
    }

    @Test
    void plainHttpRequest_getsNeitherSecureNorHsts_byDefault() {
        assertThat(setCookie(plain.baseUrl())).contains("oe_csrf=").doesNotContain("Secure");
        assertThat(hsts(plain.baseUrl())).isNull();
    }

    @Test
    void withTheOption_cookiesAreSecure_andHstsIsSent_evenOverPlainHttp() {
        assertThat(setCookie(forced.baseUrl())).contains("oe_csrf=").contains("Secure");
        assertThat(hsts(forced.baseUrl())).startsWith("max-age=");
    }
}
