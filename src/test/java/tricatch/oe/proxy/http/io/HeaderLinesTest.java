package tricatch.oe.proxy.http.io;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderLinesTest {

    private HeaderLines responseLines(String statusLine, String... headerLines) {
        HeaderLines lines = new HeaderLines(1 + headerLines.length);
        lines.addHeaderLine(new ByteBuffer(statusLine.getBytes()));
        for (String h : headerLines) {
            lines.addHeaderLine(new ByteBuffer(h.getBytes()));
        }
        return lines;
    }

    @Test
    void contentLength_isClassifiedAsContentLength() {
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "Content-Length: 42");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.CONTENT_LENGTH);
    }

    @Test
    void transferEncodingChunked_isClassifiedAsChunked() {
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "Transfer-Encoding: chunked");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.CHUNKED);
    }

    @Test
    void status204_isClassifiedAsNone_evenWithoutHeaders() {
        HeaderLines lines = responseLines("HTTP/1.1 204 No Content");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void status304_isClassifiedAsNone_evenWithoutHeaders() {
        HeaderLines lines = responseLines("HTTP/1.1 304 Not Modified");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void informational1xx_isClassifiedAsNone_evenWithoutHeaders() {
        HeaderLines lines = responseLines("HTTP/1.1 100 Continue");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void websocketUpgrade_isClassifiedAsWebsocket() {
        HeaderLines lines = responseLines("HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.WEBSOCKET);
    }

    @Test
    void noContentLengthAndNoTransferEncoding_isClassifiedAsUntilClose() {
        // RFC 7230 3.3.3 case 7: body is delimited by the server closing the connection
        HeaderLines lines = responseLines("HTTP/1.1 200 OK");
        assertThat(lines.parseHttpResponse(false).getBodyStream()).isEqualTo(HttpStream.UNTIL_CLOSE);
    }

    @Test
    void headResponse_isClassifiedAsNone_evenWithoutHeaders() {
        // A HEAD response never has a body, even without Content-Length/Transfer-Encoding — must
        // not fall through to UNTIL_CLOSE like a same-shaped GET response would.
        HeaderLines lines = responseLines("HTTP/1.1 200 OK");
        assertThat(lines.parseHttpResponse(true).getBodyStream()).isEqualTo(HttpStream.NONE);
    }

    @Test
    void headResponse_isClassifiedAsNone_evenWithContentLength() {
        // A HEAD response may carry a Content-Length describing what a GET would return, without
        // actually sending a body.
        HeaderLines lines = responseLines("HTTP/1.1 200 OK", "Content-Length: 1234");
        assertThat(lines.parseHttpResponse(true).getBodyStream()).isEqualTo(HttpStream.NONE);
    }
}
