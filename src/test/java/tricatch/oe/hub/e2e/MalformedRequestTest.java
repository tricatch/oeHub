package tricatch.oe.hub.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests the app must refuse without treating them as its own failure: a multipart (file upload)
 * body sent anywhere but the CA import routes - the shape internet scanners POST to "/" - is
 * refused with 415 before anything reads it, while the CA import routes still take uploads. A body
 * Jetty itself can't parse on an upload route gets Jetty's 400 and a one-line warning, not a 500
 * with a full stack trace logged as an error.
 */
@Tag("e2e")
class MalformedRequestTest {

    private static final String BOUNDARY = "e2eBoundary";

    private static E2eServer server;
    private static CookieManager cookies;
    private static HttpClient http;

    @BeforeAll
    static void startAll() throws Exception {
        server = new E2eServer(39968);
        server.start();
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    @AfterAll
    static void stopAll() {
        if (server != null) server.close();
    }

    @Test
    void multipartPostOutsideTheUploadRoutes_isRefusedWith415() throws Exception {
        var response = postMultipart("/", field("junk", "x".repeat(300_000)));
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(postMultipart("/login", field("userId", "someone")).statusCode()).isEqualTo(415);
        assertThat(postMultipart("/api/hosts", field("content", "1.2.3.4 a.test")).statusCode()).isEqualTo(415);
    }

    @Test
    void caImportRoute_stillTakesAnUpload() throws Exception {
        // The setup page sets the CSRF cookie; the upload form carries it as _csrf, which the CSRF
        // filter reads out of the multipart body. A junk certificate is enough to show the request
        // got all the way to the import handler, which re-renders the setup page with its error.
        http.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/setup")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        var csrf = cookies.getCookieStore().getCookies().stream()
            .filter(c -> c.getName().equals("oe_csrf")).map(HttpCookie::getValue).findFirst().orElseThrow();
        var response = postMultipart("/setup/ca/import",
            field("_csrf", csrf) + file("certFile", "ca.crt", "not a certificate") + file("keyFile", "ca.key", "not a key"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<form");
    }

    @Test
    void unparsableBodyOnAnUploadRoute_isA400_andNotLoggedAsAnError() throws Exception {
        var response = postMultipart("/setup/ca/import", field("junk", "x".repeat(300_000)));
        assertThat(response.statusCode()).isEqualTo(400);
        var log = Files.readString(server.homeDir().resolve("server.log"));
        assertThat(log).contains("Rejected malformed request on /setup/ca/import").doesNotContain("Uncaught exception");
    }

    private static HttpResponse<String> postMultipart(String path, String parts) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofString(parts + "--" + BOUNDARY + "--\r\n"))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String name, String value) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String file(String name, String filename, String content) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
            + "\"\r\nContent-Type: application/octet-stream\r\n\r\n" + content + "\r\n";
    }
}
