package tricatch.oe.hub.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request Jetty refuses to parse - here an anonymous multipart POST past Jetty's 200000-byte form
 * limit, the shape internet scanners send to "/" - gets Jetty's own 400 and a one-line warning,
 * not a 500 with a full stack trace logged as an error.
 */
@Tag("e2e")
class MalformedRequestTest {

    private static E2eServer server;

    @BeforeAll
    static void startAll() throws Exception {
        server = new E2eServer(39968);
        server.start();
    }

    @AfterAll
    static void stopAll() {
        if (server != null) server.close();
    }

    @Test
    void oversizedMultipartPost_isA400_andNotLoggedAsAnError() throws Exception {
        var boundary = "e2eBoundary";
        var body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"junk\"\r\n\r\n"
            + "x".repeat(300_000) + "\r\n"
            + "--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        var log = Files.readString(server.homeDir().resolve("server.log"));
        assertThat(log).contains("Rejected malformed request on /").doesNotContain("Uncaught exception");
    }
}
