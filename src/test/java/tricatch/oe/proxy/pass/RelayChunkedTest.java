package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RelayChunkedTest {

    private byte[] chunkedEncode(byte[] data, int chunkSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < data.length) {
            int len = Math.min(chunkSize, data.length - offset);
            out.write(Integer.toHexString(len).getBytes(StandardCharsets.US_ASCII));
            out.write(HTTP.CRLF);
            out.write(data, offset, len);
            out.write(HTTP.CRLF);
            offset += len;
        }
        out.write('0');
        out.write(HTTP.CRLF);
        out.write(HTTP.CRLF); // empty trailer section
        return out.toByteArray();
    }

    @Test
    void relaysSmallChunkedBody_byteForByte_andReportsKeepAlive() throws Exception {
        byte[] body = "hello, chunked body".getBytes();
        byte[] encoded = chunkedEncode(body, 6);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(encoded), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayChunked.relay("client1", "rid1", HttpStream.Flow.RES, in, out);

        assertThat(result).isEqualTo(HttpStream.Connection.KEEP_ALIVE);
        assertThat(rawOut.toByteArray()).isEqualTo(encoded);
    }

    @Test
    void relaysFullChunkedBody_evenWhenItExceedsTheMonitorCap() throws Exception {
        // One chunk's worth over HTTP.MONITOR_BODY_LIMIT: the monitor-event collector must
        // stop accumulating past the cap, but the full wire-format stream must still reach the client.
        byte[] body = new byte[HTTP.MONITOR_BODY_LIMIT + 1024];
        new Random(7).nextBytes(body);
        byte[] encoded = chunkedEncode(body, HTTP.BODY_BUFFER_SIZE);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(encoded), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayChunked.relay("client1", "rid2", HttpStream.Flow.RES, in, out);

        assertThat(result).isEqualTo(HttpStream.Connection.KEEP_ALIVE);
        assertThat(rawOut.toByteArray()).isEqualTo(encoded);
    }
}
