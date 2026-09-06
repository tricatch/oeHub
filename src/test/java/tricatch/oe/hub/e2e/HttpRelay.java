package tricatch.oe.hub.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal hand-written HTTP/1.1 client plumbing shared by the raw-socket relay tests (oeProxy,
 * the forward proxy): write a request line by hand, then read back a response without ever
 * waiting for the socket to close. Both proxies under test keep connections alive by design
 * (see e.g. ProxyRelayTest's class comment on the SSL reverse proxy's keep-alive behavior), so a
 * client that waits for EOF instead of parsing Content-Length/chunked framing just stalls until
 * a server-side read-timeout expires.
 */
final class HttpRelay {

    private HttpRelay() {
    }

    record Response(int status, Map<String, String> headers, byte[] body) {}

    static Response send(Socket socket, String requestLine, Map<String, String> headers) throws IOException {
        var sb = new StringBuilder(requestLine).append("\r\n");
        headers.forEach((name, value) -> sb.append(name).append(": ").append(value).append("\r\n"));
        sb.append("\r\n");
        socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return readResponse(new LineReader(socket.getInputStream()));
    }

    static Response readResponse(LineReader in) throws IOException {
        var statusLine = in.readLine();
        var status = Integer.parseInt(statusLine.split(" ")[1]);
        var headers = new LinkedHashMap<String, String>();
        String line;
        while (!(line = in.readLine()).isEmpty()) {
            var sep = line.indexOf(':');
            if (sep > 0) headers.put(line.substring(0, sep).trim().toLowerCase(), line.substring(sep + 1).trim());
        }

        byte[] body;
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            // Read chunk-by-chunk until the terminating 0-length chunk, rather than waiting for
            // the socket to close - a streamed response has no reason to close the connection at
            // all once its writer is done.
            var out = new ByteArrayOutputStream();
            while (true) {
                var sizeLine = in.readLine();
                int size = Integer.parseInt(sizeLine.trim(), 16);
                if (size == 0) {
                    in.readLine(); // trailing blank line after the terminating chunk
                    break;
                }
                out.write(in.readExact(size));
                in.readLine(); // CRLF after each chunk's data
            }
            body = out.toByteArray();
        } else if (headers.containsKey("content-length")) {
            body = in.readExact(Integer.parseInt(headers.get("content-length")));
        } else {
            body = new byte[0];
        }
        return new Response(status, headers, body);
    }
}
