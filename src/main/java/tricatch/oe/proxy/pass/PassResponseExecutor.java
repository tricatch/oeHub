package tricatch.oe.proxy.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.GatewayTimeoutException;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.*;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;
import tricatch.oe.proxy.server.VThreadExecutor;
import tricatch.oe.proxy.server.VirtualPath;
import tricatch.oe.proxy.util.HtmlUtil;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.LockSupport;

public class PassResponseExecutor implements Stopable {

    private static final Logger logger = LoggerFactory.getLogger(PassResponseExecutor.class);

    private PassRequestExecutor passRequestExecutor;
    private Thread thisThread = null;

    private HttpStreamWriter clientOut = null;
    private HttpStreamReader serverIn = null;

    private String rid;

    public PassResponseExecutor(PassRequestExecutor passRequestExecutor, HttpStreamReader serverIn, HttpStreamWriter clientOut){
        this.passRequestExecutor = passRequestExecutor;
        this.serverIn = serverIn;
        this.clientOut = clientOut;
        this.rid = passRequestExecutor.getUid();
    }

    @Override
    public void run() {

        int bytesRead;
        boolean responseHeaderSent = false;

        try {

            this.thisThread = Thread.currentThread();

            if( logger.isDebugEnabled() ){
                logger.debug( "{}, vtStart", this.passRequestExecutor.getUid() );
            }

            while(true) {

                responseHeaderSent = false;

                // Fresh buffer per response: queued HttpEvent serializes async; reusing one HeaderLines lets the next readHeaders(clear) wipe it first.
                HeaderLines responseHeaders = new HeaderLines(HTTP.INIT_HEADER_LINES);

                //read-res-header
                bytesRead = serverIn.readHeaders(responseHeaders, HTTP.MAX_HEADER_LENGTH);

                this.rid = this.passRequestExecutor.getRid();

                if (bytesRead == -1) {
                    logger.warn("{}, No headers received from server"
                            , rid
                    );
                    writeBadGatewayIfPossible(null);
                    return;
                }

                //parse-res-header
                HttpResponse response = responseHeaders.parseHttpResponse();
                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, Response Headers\n{}"
                            , rid
                            , HttpStream.Flow.RES
                            , responseHeaders
                    );
                    logger.debug("{}, {}, Response: {} {} {} (Body: {}, Connection: {}, ContentLength: {})"
                            , rid
                            , HttpStream.Flow.RES
                            , response.getVersion()
                            , response.getStatusCode()
                            , response.getStatusMessage()
                            , response.getBodyStream()
                            , response.getConnection()
                            , response.getContentLength()
                    );
                }

                // Enqueue RES header HttpEvent
                String clientId = this.passRequestExecutor.getClientId();
                HttpEvent resHeaderEvent = new HttpEvent(clientId, this.rid, HttpEventType.RES_HEADER);
                resHeaderEvent.setHeaders(responseHeaders);
                HttpEventManager.getInstance().enqueue(resHeaderEvent);

                //write-res-header
                clientOut.writeHeaders(responseHeaders);
                responseHeaderSent = true;

                // Relay response body to client
                HttpStream.Connection connection = RelayBody.relayResponseBody(clientId, rid, HttpStream.Flow.RES, response, serverIn, clientOut);
                if (connection == HttpStream.Connection.CLOSE) {
                    passRequestExecutor.setStop(true);
                }

                if( passRequestExecutor.isStop()
                    || "Close".equalsIgnoreCase(response.getConnection())
                ){
                    break;
                }

                LockSupport.unpark(this.passRequestExecutor.getThread());
            }

        } catch (SocketTimeoutException e) {
            logger.error(this.passRequestExecutor.getUid() + ", " + e.getMessage());
            if (!responseHeaderSent) writeGatewayTimeoutIfPossible();
        } catch (SocketException e){
            if( "Connection reset".equals(e.getMessage())
                || "Socket closed".equals(e.getMessage())
            ) {
                logger.error(this.passRequestExecutor.getUid() + ", " + e.getMessage());
            } else {
                logger.error( this.passRequestExecutor.getUid() + ", " + e.getMessage(), e);
            }
            if (!responseHeaderSent) writeBadGatewayIfPossible(e);
        } catch (IOException e) {
            logger.error( this.passRequestExecutor.getUid() + ", " + e.getMessage(), e);
            if (!responseHeaderSent) writeBadGatewayIfPossible(e);
        } finally {

            VThreadExecutor.removeVirtualThread(Thread.currentThread());

            if( logger.isDebugEnabled() ){
                logger.debug( "{}, vtEnd", this.passRequestExecutor.getUid() );
            }
            if (passRequestExecutor.getChildThread() == this.thisThread) {
                passRequestExecutor.setStop(true);
                LockSupport.unpark(this.passRequestExecutor.getThread());
            }
        }

    }

    // Called when the upstream connection fails/times out before any response headers were
    // forwarded to the client for the in-flight request, so the client would otherwise be left
    // waiting forever (see PassRequestExecutor's park loop, which only reads the client's next
    // request after this thread has already given up reading further responses).
    private void writeGatewayTimeoutIfPossible() {
        VirtualPath vp = passRequestExecutor.getCurrentVirtualPath();
        if (clientOut == null || vp == null) return;
        try {
            GatewayTimeoutException ex = new GatewayTimeoutException(
                    this.rid, passRequestExecutor.getCurrentHost(), vp.getTarget(), vp.getPath());
            HtmlUtil.writeGatewayTimeoutResponse(clientOut, ex, passRequestExecutor.getCurrentLocale());
        } catch (IOException io) {
            logger.error("{}, Failed to write 504 response: {}", this.rid, io.getMessage(), io);
        }
    }

    private void writeBadGatewayIfPossible(Exception cause) {
        VirtualPath vp = passRequestExecutor.getCurrentVirtualPath();
        if (clientOut == null || vp == null) return;
        try {
            IOException ioCause = cause instanceof IOException ? (IOException) cause : null;
            BadGatewayException ex = new BadGatewayException(
                    this.rid, passRequestExecutor.getCurrentHost(), vp.getTarget(), vp.getPath(), ioCause);
            HtmlUtil.writeBadGatewayResponse(clientOut, ex, passRequestExecutor.getCurrentLocale());
        } catch (IOException io) {
            logger.error("{}, Failed to write 502 response: {}", this.rid, io.getMessage(), io);
        }
    }

    @Override
    public void stop() {

    }

    @Override
    public String getName() {
        if( this.thisThread==null ) return null;
        return thisThread.getName();
    }
}
