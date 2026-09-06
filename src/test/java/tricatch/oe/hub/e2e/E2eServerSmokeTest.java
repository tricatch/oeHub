package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ProcessBuilder-launched server + Playwright wiring itself, ahead of the full
 * setup/account/login/oeHosts/oeProxy scenario tests.
 */
@Tag("e2e")
class E2eServerSmokeTest {

    private static final int PORT = 39912;

    private static E2eServer server;
    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void startAll() throws Exception {
        server = new E2eServer(PORT);
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void stopAll() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @Test
    void freshServerRedirectsToSetupWizard() {
        Page page = browser.newPage();
        page.navigate(server.baseUrl() + "/");
        assertThat(page.url()).contains("/setup");
        page.close();
    }
}
