package tricatch.oe.hub.e2e;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Buffered line/byte reader for hand-rolled HTTP and WebSocket parsing over a raw socket, shared
 * by the e2e relay tests (oeProxy, the forward proxy). Everything downstream of a handshake reads
 * through the same instance rather than the socket's raw InputStream directly, so nothing risks
 * losing bytes already pulled ahead into a different buffer upstream.
 */
final class LineReader {

    private final InputStream in;

    LineReader(InputStream in) {
        this.in = new BufferedInputStream(in);
    }

    String readLine() throws IOException {
        var buf = new ByteArrayOutputStream();
        int prev = -1, b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') {
                var bytes = buf.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            buf.write(b);
            prev = b;
        }
        throw new EOFException("Connection closed before a line terminator");
    }

    int readByte() throws IOException {
        return in.read();
    }

    byte[] readExact(int n) throws IOException {
        var buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) throw new EOFException("Connection closed after " + off + "/" + n + " bytes");
            off += r;
        }
        return buf;
    }
}
