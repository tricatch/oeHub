package tricatch.oe.proxy.pass;

import org.junit.jupiter.api.Test;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RelayContentLengthTest {

    @Test
    void relaysFullBody_andReportsKeepAlive() throws Exception {
        byte[] body = "the full, complete body".getBytes();

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(body), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayContentLength.relay("client1", "rid1", HttpStream.Flow.RES, body.length, in, out, false);

        assertThat(result).isEqualTo(HttpStream.Connection.KEEP_ALIVE);
        assertThat(rawOut.toByteArray()).isEqualTo(body);
    }

    @Test
    void prematureEof_reportsClose_insteadOfKeepAlive() throws Exception {
        // Content-Length declares 10000 bytes but the backend only sends 4000 before closing.
        byte[] actualBytes = new byte[4000];
        for (int i = 0; i < actualBytes.length; i++) actualBytes[i] = (byte) (i % 256);

        HttpStreamReader in = new HttpStreamReader(new ByteArrayInputStream(actualBytes), HTTP.BODY_BUFFER_SIZE);
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        HttpStreamWriter out = new HttpStreamWriter(rawOut);

        HttpStream.Connection result = RelayContentLength.relay("client1", "rid2", HttpStream.Flow.RES, 10000, in, out, false);

        // A truncated body means the connection is dead/desynced and must not be reused.
        assertThat(result).isEqualTo(HttpStream.Connection.CLOSE);
        assertThat(rawOut.toByteArray()).isEqualTo(actualBytes);
    }
}
