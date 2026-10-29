package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launch arguments the editor and the share page hand to Chrome (through an oelink:// link or a
 * copied command) are built from content other accounts can author: hosts lines, extra args, user
 * agent, data dir. This runs the shared builders in a real browser and checks that such content
 * cannot add flags of its own or break out of a quoted value.
 */
@Tag("e2e")
class LaunchArgsSanitizingTest {

    private static final int PORT = 39941;

    private static E2eServer server;
    private static Playwright playwright;
    private static Browser browser;
    private static Page page;

    @BeforeAll
    static void startAll() throws Exception {
        server = new E2eServer(PORT);
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        // A blank static page: the scripts under test are plain browser scripts with no server dependency.
        page.navigate(server.baseUrl() + "/js/util.js");
        page.addScriptTag(new Page.AddScriptTagOptions().setUrl(server.baseUrl() + "/js/util.js"));
        page.addScriptTag(new Page.AddScriptTagOptions().setUrl(server.baseUrl() + "/js/chrome-launch-args.js"));
    }

    @AfterAll
    static void stopAll() {
        if (page != null) page.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @SuppressWarnings("unchecked")
    private static List<String> args(String optsJson) {
        return (List<String>) page.evaluate("opts => buildChromeArgParts(opts)", parse(optsJson));
    }

    private static Object parse(String json) {
        return page.evaluate("s => JSON.parse(s)", json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostsLinesThatAreNotPlainRulesAreDropped() {
        Object map = page.evaluate("""
            () => parseHostsToMap([
              '127.0.0.1 ok.example.com',
              '10.0.0.1 *.wild.example.com',
              '${PROXY_SVR} proxied.example.com',
              '::1 v6.example.com',
              '1.2.3.4 evil.com" --renderer-cmd-prefix=calc',
              '1.2.3.4 a.com,MAP b.com 9.9.9.9',
              '1.2.3.4\\'$(touch x) c.com',
              '5.6.7.8 space;semi.com',
            ].join('\\n'))
            """);
        assertThat((java.util.Set<Object>) ((java.util.Map<Object, Object>) map).keySet())
            .containsExactlyInAnyOrder("ok.example.com", "*.wild.example.com", "proxied.example.com", "v6.example.com");
    }

    @Test
    void quotesAndControlCharactersCannotEndAQuotedValue() {
        var parts = args("""
            {"uaEnabled":true,"userAgent":"Mozilla\\" --renderer-cmd-prefix=calc \\"x",
             "uddEnabled":true,"userDataDir":"C:\\\\x\\"\\n--no-sandbox",
             "hrrEnabled":true,"rulesStr":"MAP a 1.1.1.1\\" --gpu-launcher=y"}
            """);
        for (String p : parts) {
            // every produced word is exactly one flag with one opening and one closing quote
            assertThat(p.chars().filter(c -> c == '"').count()).as(p).isEqualTo(2);
            assertThat(p).doesNotContain("\n");
        }
    }

    @Test
    void extraArgsThatRunProgramsOrOpenDebugChannelsAreDropped() {
        var parts = args("""
            {"extraArgsEnabled":true,"extraArgsVal":"--disable-sync\\n--renderer-cmd-prefix=calc\\n--GPU-launcher=x\\n--remote-debugging-port=9222\\n--no-sandbox\\n--ignore-certificate-errors-spki-list=a\\n--disk-cache-size=1"}
            """);
        assertThat(parts).containsExactlyInAnyOrder("--disable-sync", "--disk-cache-size=1");
    }
}
