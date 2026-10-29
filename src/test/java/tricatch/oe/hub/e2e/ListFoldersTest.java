package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sidebar tree with one-level folders (list-folders.js + /api/hosts/folders, .../tree-order),
 * driven through the real oeHosts and oeProxy pages: create + name a folder, drag an item into it,
 * turn it on (badge/icon follow), drag the folder between root items, the state surviving a
 * reload, collapse, and deleting the folder putting its item back. Also the denied cases for the
 * new routes (no session).
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ListFoldersTest {

    private static final int PORT = 39960;
    private static final String ADMIN_ID = "folderAdmin";
    private static final String ADMIN_PW = "AdminPass123!";

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeAll
    void startAll() throws Exception {
        server = new E2eServer(PORT);
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        page.onDialog(Dialog::accept); // folder delete goes through confirm()
        page.addInitScript("try { localStorage.setItem('hostsGuideHidden', 'true'); localStorage.setItem('proxyGuideHidden', 'true'); } catch (e) {}");
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
        page.locator("input[name=caName]").fill("oeHub Folder Test CA");
        page.locator("form[action='/setup/ca/generate'] button[type=submit]").click();
        assertThat(page.locator(".badge.bg-success")).hasCount(2);
    }

    @Test
    @Order(2)
    void hosts_folderLifecycle() {
        page.navigate(server.baseUrl() + "/oehub/hosts");
        page.locator("#btnAdd").click();
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(1);
        page.locator("#btnAdd").click();
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(2);
        var firstName = page.locator("#profileList .oe-list-item .oe-list-name").first().textContent();
        var secondName = page.locator("#profileList .oe-list-item .oe-list-name").nth(1).textContent();

        // Renaming a profile to a name it already has elsewhere is refused with a toast, and the
        // row keeps its own name.
        page.locator("#profileList .oe-list-item .oe-list-name").nth(1).dblclick();
        page.keyboard().type(firstName);
        page.keyboard().press("Enter");
        assertThat(page.locator("#toast .oe-toast-msg")).hasText("A profile with that name already exists.");
        assertThat(page.locator("#profileList .oe-list-item .oe-list-name").nth(1)).hasText(secondName);

        nameNewFolder("#btnAddFolder", "#profileList", "Work");

        // Drag the second profile onto the folder row: it moves in and shows under the folder.
        Locator header = page.locator("#profileList .oe-list-folder");
        page.locator("#profileList .oe-list-item").nth(1).dragTo(header);
        assertThat(page.locator("#profileList .oe-list-item.in-folder")).hasCount(1);
        assertThat(page.locator("#profileList .oe-list-item.in-folder .oe-list-name")).hasText(secondName);
        assertThat(page.locator("#profileList .oe-list-folder-count")).hasText("0/1");

        // Turning the folder's profile on lights up the folder: badge "on/total" and icon.
        page.locator("#profileList .oe-list-item.in-folder .oe-tree-switch").click();
        assertThat(page.locator("#profileList .oe-list-item.in-folder .oe-tree-switch")).hasAttribute("aria-pressed", "true");
        assertThat(page.locator("#profileList .oe-list-folder-count")).hasText("1/1");
        assertThat(page.locator("#profileList .oe-list-folder")).hasClass(Pattern.compile("has-on"));

        // Folders interleave with the root profiles: the folder (created last, so after both
        // profiles) is dragged above the first root profile, and that order is what gets saved.
        assertThat(page.locator("#profileList > *").first()).hasClass(Pattern.compile("oe-list-item"));
        page.waitForResponse(r -> r.url().endsWith("/tree-order"), () ->
            header.dragTo(page.locator("#profileList > .oe-list-item").first(),
                new Locator.DragToOptions().setTargetPosition(20, 3)));
        assertThat(page.locator("#profileList > *").first()).hasClass(Pattern.compile("oe-tree-group"));

        // Persisted: after a reload the folder still holds its profile, above the root profile.
        page.reload();
        assertThat(page.locator("#profileList .oe-list-folder .oe-list-folder-name")).hasText("Work");
        assertThat(page.locator("#profileList > *").first()).hasClass(Pattern.compile("oe-tree-group"));
        assertThat(page.locator("#profileList .oe-list-item").first()).hasClass(Pattern.compile("in-folder"));
        assertThat(page.locator("#profileList .oe-list-folder-count")).hasText("1/1");
        assertThat(page.locator("#profileList .oe-list-folder")).hasAttribute("aria-expanded", "true");

        // Collapse hides the folder's items, and that survives a reload too.
        page.locator("#profileList .oe-list-folder").click();
        assertThat(page.locator("#profileList .oe-list-folder.collapsed")).hasCount(1);
        assertThat(page.locator("#profileList .oe-list-folder")).hasAttribute("aria-expanded", "false");
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(1);
        page.reload();
        assertThat(page.locator("#profileList .oe-list-folder.collapsed")).hasCount(1);
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(1);

        // Deleting the folder puts its profile back at the root - nothing is lost.
        page.locator("#profileList .oe-list-folder").hover();
        page.locator("#profileList .oe-list-folder-del").click();
        assertThat(page.locator("#profileList .oe-list-folder")).hasCount(0);
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(2);
        assertThat(page.locator("#profileList .oe-list-item.in-folder")).hasCount(0);
        page.reload();
        assertThat(page.locator("#profileList .oe-list-item")).hasCount(2);
    }

    @Test
    @Order(3)
    void proxy_itemMovesIntoAFolder() {
        page.navigate(server.baseUrl() + "/oehub/proxy");
        page.locator("#btnAdd").click();
        assertThat(page.locator("#vhostList .oe-list-item")).hasCount(1);

        nameNewFolder("#btnAddFolder", "#vhostList", "Edge");
        page.locator("#vhostList .oe-list-item").first().dragTo(page.locator("#vhostList .oe-list-folder"));
        assertThat(page.locator("#vhostList .oe-list-item.in-folder")).hasCount(1);

        page.reload();
        assertThat(page.locator("#vhostList .oe-list-folder .oe-list-folder-name")).hasText("Edge");
        assertThat(page.locator("#vhostList .oe-list-item.in-folder")).hasCount(1);
    }

    @Test
    @Order(4)
    void folderRoutes_withoutSession_are401() {
        var anon = playwright.request().newContext();
        try {
            assertThat(anon.get(server.baseUrl() + "/api/hosts/folders").status()).isEqualTo(401);
            assertThat(anon.get(server.baseUrl() + "/api/proxy/vhosts/folders").status()).isEqualTo(401);
            // A state-changing request with no session never gets as far as the auth check: the
            // app-wide CSRF before-filter already rejects it (no oe_csrf cookie/header pair).
            assertThat(anon.post(server.baseUrl() + "/api/hosts/folders").status()).isEqualTo(403);
            assertThat(anon.put(server.baseUrl() + "/api/hosts/tree-order",
                    RequestOptions.create().setData("[]")).status()).isEqualTo(403);
        } finally {
            anon.dispose();
        }
    }

    // A new folder opens straight into inline rename with its default name selected.
    private void nameNewFolder(String addButton, String list, String name) {
        page.locator(addButton).click();
        Locator nameEl = page.locator(list + " .oe-list-folder-name.editing");
        assertThat(nameEl).hasCount(1);
        page.keyboard().type(name);
        // Saved, not just shown: wait for the rename PATCH so a later reload can't race it.
        var saved = page.waitForResponse(r -> r.url().contains("/folders/") && r.request().method().equals("PATCH"),
                () -> page.keyboard().press("Enter"));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(page.locator(list + " .oe-list-folder-name")).hasText(name);
    }
}
