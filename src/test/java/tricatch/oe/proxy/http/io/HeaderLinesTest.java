package tricatch.oe.proxy.http.io;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class HeaderLinesTest {

    private HeaderLines responseLines(String statusLine, String... headerLines) {
        HeaderLines lines = new HeaderLines(1 + headerLines.length);
        lines.addHeaderLine(new ByteBuffer(statusLine.getBytes()));
        for (String h : headerLines) {
            lines.addHeaderLine(new ByteBuffer(h.getBytes()));
        }
        return lines;
    }

    private HeaderLines requestLines(String requestLine, String... headerLines) {
        HeaderLines lines = new HeaderLines(1 + headerLines.length);
        lines.addHeaderLine(new ByteBuffer(requestLine.getBytes()));
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

    // ── request-smuggling framing validation ────────────────────────────────────────

    @Test
    void request_singleContentLength_isAccepted() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_singleChunkedTransferEncoding_isAccepted() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: chunked");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_duplicateContentLength_isRejected() {
        // CL.CL smuggling: two Content-Length headers, possibly disagreeing on the body length.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5", "Content-Length: 10");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_duplicateContentLength_isRejected_evenWhenIdentical() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5", "Content-Length: 5");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_contentLengthAndTransferEncodingTogether_isRejected() {
        // The classic CL.TE / TE.CL smuggling setup.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Content-Length: 5", "Transfer-Encoding: chunked");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_duplicateTransferEncoding_isRejected() {
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: chunked", "Transfer-Encoding: chunked");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_transferEncodingWithSurroundingWhitespace_isAccepted() {
        // Leading/trailing whitespace around the value is trimmed by the normal header-value
        // parsing, so this is just a plain "chunked" - not an obfuscation attempt.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: chunked ");
        assertThatNoException().isThrownBy(lines::parseHttpRequest);
    }

    @Test
    void request_obfuscatedTransferEncodingValue_isRejected() {
        // Not exactly "chunked" - a common obfuscation trick to slip past one hop's parser while
        // still being interpreted as chunked by the other.
        HeaderLines lines = requestLines("POST /x HTTP/1.1", "Host: a", "Transfer-Encoding: identity, chunked");
        assertThatIllegalArgumentException().isThrownBy(lines::parseHttpRequest);
    }
}
