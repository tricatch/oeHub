package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The editor auto-save (static/js/autosave.js) on the oeHosts page: the amber outline and
 * "Unsaved changes" while an edit waits for its debounced save, a failed save turning red and
 * being retried until it goes through (instead of being dropped), the unsaved edit surviving a
 * switch to another profile and back, Ctrl+S saving without waiting for the debounce, and leaving
 * the page with an unsaved edit asking first.
 * Save failures are simulated by intercepting the content PATCH in the browser.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutosaveTest {

    private static final int PORT = 39965;
    private static final String ADMIN_ID = "autosaveAdmin";
    private static final String ADMIN_PW = "AdminPass123!";
    private static final String CONTENT_PATCH = "**/api/hosts/*/content";
    private static final LocatorAssertions.ContainsTextOptions SAVE_WAIT =
        new LocatorAssertions.ContainsTextOptions().setTimeout(5000);

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;
    private final List<String> dialogTypes = new ArrayList<>();

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT);
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        page.onDialog(d -> { dialogTypes.add(d.type()); d.accept(); });
        page.addInitScript("try { localStorage.setItem('hostsGuideHidden', 'true'); } catch (e) {}");
    }

    @AfterAll
    void stopAll() {
        if (page != null) page.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void setupAdminAndCa() {
        page.navigate(server.baseUrl() + "/setup");
        page.locator("form[action='/setup'] input[name=userId]").fill(ADMIN_ID);
        page.locator("form[action='/setup'] input[name=password]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] input[name=confirm]").fill(ADMIN_PW);
        page.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(page.locator(".badge.bg-success")).hasCount(1);
        page.locator("input[name=caName]").fill("oeHub Autosave Test CA");
        page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();
        assertThat(page.locator(".badge.bg-success")).hasCount(2);
    }

    @Test
    @Order(2)
    void anEditShowsAsUnsavedUntilTheDebouncedSaveLands() {
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.locator("#btnAdd").click();
        page.locator("#btnAdd").click();
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(2);
        page.locator("#profileList .oe-list-item").first().click();

        typeInEditor("10.0.0.1 first.oe.test");
        assertThat(page.locator("#cmHostsEditor")).hasClass(Pattern.compile("oe-autosave-dirty"));
        assertThat(page.locator("#saveStatus")).hasText("Unsaved changes");

        assertThat(page.locator("#saveStatus")).containsText("Saved", SAVE_WAIT);
        assertThat(page.locator("#cmHostsEditor")).not().hasClass(Pattern.compile("oe-autosave-"));
    }

    @Test
    @Order(3)
    void aFailedSaveTurnsRedSurvivesASwitchAndIsRetriedUntilItGoesThrough() {
        page.route(CONTENT_PATCH, route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(503)));
        typeInEditor("10.0.0.2 retried.oe.test");
        assertThat(page.locator("#cmHostsEditor")).hasClass(Pattern.compile("oe-autosave-failed"), new LocatorAssertions.HasClassOptions().setTimeout(5000));
        assertThat(page.locator("#saveStatus")).hasText("Save failed - retrying");

        // Away and back while the save is still failing: the edit is still in the editor, not the
        // last content the server holds.
        page.locator("#profileList .oe-list-item").nth(1).click();
        assertThat(page.locator("#cmHostsEditor")).not().hasClass(Pattern.compile("oe-autosave-"));
        page.locator("#profileList .oe-list-item").first().click();
        assertThat(editorValue()).contains("retried.oe.test");
        assertThat(page.locator("#cmHostsEditor")).hasClass(Pattern.compile("oe-autosave-failed"));

        // Once the server answers again a retry saves it, with no further edit needed.
        page.unroute(CONTENT_PATCH);
        assertThat(page.locator("#saveStatus")).containsText("Saved", new LocatorAssertions.ContainsTextOptions().setTimeout(35000));
        assertThat(page.locator("#cmHostsEditor")).not().hasClass(Pattern.compile("oe-autosave-"));

        page.reload();
        page.locator("#profileList .oe-list-item").first().click();
        assertThat(editorValue()).contains("retried.oe.test");
        assertThat(dialogTypes).isEmpty(); // nothing was unsaved, so the reload didn't ask
    }

    @Test
    @Order(4)
    void ctrlSSavesWithoutWaitingForTheDebounce() {
        typeInEditor("10.0.0.4 ctrl-s.oe.test");
        page.keyboard().press("Control+S");
        // Well inside the 2s debounce, so only the shortcut can have saved it.
        assertThat(page.locator("#saveStatus")).containsText("Saved",
            new LocatorAssertions.ContainsTextOptions().setTimeout(1500));
        assertThat(page.locator("#cmHostsEditor")).not().hasClass(Pattern.compile("oe-autosave-"));
    }

    @Test
    @Order(5)
    void leavingThePageWithAnUnsavedEditAsksFirst() {
        page.route(CONTENT_PATCH, route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(503)));
        typeInEditor("10.0.0.3 unsaved.oe.test");
        assertThat(page.locator("#cmHostsEditor")).hasClass(Pattern.compile("oe-autosave-"));
        page.reload();
        assertThat(dialogTypes).containsExactly("beforeunload");
        page.unroute(CONTENT_PATCH);
    }

    private void typeInEditor(String line) {
        page.locator("#cmHostsEditor").click();
        page.keyboard().press("Control+End");
        page.keyboard().press("Enter");
        page.keyboard().type(line);
    }

    private String editorValue() {
        return (String) page.evaluate("() => ace.edit('cmHostsEditor').getValue()");
    }
}
