package tricatch.oe.proxy.pass;

import io.github.azagniotov.matcher.AntPathMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.NotFoundProxyVirtualHostsException;
import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.HeaderLines;
import tricatch.oe.proxy.http.io.HttpRequest;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;
import tricatch.oe.proxy.server.VThreadExecutor;
import tricatch.oe.proxy.server.VirtualHosts;
import tricatch.oe.proxy.server.VirtualPath;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.NotFoundVhostException;
import tricatch.oe.proxy.util.HtmlUtil;
import tricatch.oe.proxy.util.SocketUtils;
import tricatch.oe.proxy.util.SysUtil;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class PassRequestExecutor implements Stopable {

    private static final Logger logger = LoggerFactory.getLogger(PassRequestExecutor.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher.Builder().build();

    // WebSocket connections are long-lived and often sit idle between messages, so they get their
    // own (longer) socket timeout instead of the configurable connectTimeout/readTimeout used for
    // everything else. Not yet admin-configurable like those two — just named here for now so the
    // value isn't a duplicated magic number.
    private static final int WEBSOCKET_IDLE_TIMEOUT_MS = 1000 * 60 * 5;

    private Socket clientSocket;
    private HttpStreamReader clientIn = null;
    private HttpStreamWriter clientOut = null;

    private Socket serverSocket = null;
    private HttpStreamReader serverIn = null;
    private HttpStreamWriter serverOut = null;
    private VirtualPath preVirtualPath = null;

    // clientOut is handed to a spawned PassResponseExecutor as well, and a target change can
    // force-close the server socket it's blocked reading from, waking it into its own error path.
    // At most one HTTP response may reach the client per connection, so whichever thread (this
    // one or the child) hits an error first claims the write; the loser skips it rather than
    // racing unsynchronized writes onto the shared HttpStreamWriter.
    private final AtomicBoolean errorResponseClaimed = new AtomicBoolean(false);

    // Bumped every time a new server socket/child PassResponseExecutor replaces the previous one
    // (target change). A child captures the generation it was spawned for; if forceCloseServerSocket()
    // wakes it with an error after a newer generation has already started, it can tell it's been
    // superseded and must not write anything (error or otherwise) to the shared clientOut.
    private final AtomicLong socketGeneration = new AtomicLong(0);

    private final int connectTimeout;
    private final int readTimeout;

    private boolean stop = false;

    private Thread thisThread = null;
    private volatile Thread child = null;

    private final String uid = SysUtil.generateRequestId();
    private String rid = uid;
    private int reqCounter = 0;
    private VirtualHosts virtualHosts = null;
    private String clientId = null;
    private String currentLocale = "en";
    private String currentHost = null;
    private String currentMethod = null;
    private String oidHeader = null;

    public PassRequestExecutor(Socket clientSocket, int connectTimeout, int readTimeout){

        this.clientSocket = clientSocket;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public void setStop(boolean stop){
        this.stop = stop;
    }

    public boolean isStop(){
        return this.stop;
    }

    public Thread getChildThread(){
        return this.child;
    }

    public String getUid(){
        return this.uid;
    }
    public String getRid(){
        return this.rid;
    }
    
    public String getClientId(){
        return this.clientId;
    }

    public String getCurrentLocale(){
        return this.currentLocale;
    }

    public String getCurrentHost(){
        return this.currentHost;
    }

    public String getCurrentMethod(){
        return this.currentMethod;
    }

    public long getSocketGeneration(){
        return this.socketGeneration.get();
    }

    public VirtualPath getCurrentVirtualPath(){
        return this.preVirtualPath;
    }

    public Thread getThread(){
        return this.thisThread;
    }

    /**
     * At most one caller may write the client-facing error response for this connection.
     * @return true if the caller won the claim and may write to clientOut; false if another
     *         thread already claimed it (the caller should skip writing).
     */
    public boolean claimErrorResponse(){
        return errorResponseClaimed.compareAndSet(false, true);
    }

    @Override
    public void run() {

        try {

            this.clientId = this.clientSocket.getInetAddress().getHostAddress();

            if( logger.isDebugEnabled() ){
                logger.debug( "{}, vtStart / {}", this.uid, this.clientId );
            }

            thisThread = Thread.currentThread();
            clientIn = new HttpStreamReader(clientSocket.getInputStream(), HTTP.BODY_BUFFER_SIZE);
            clientOut = new HttpStreamWriter(clientSocket.getOutputStream());

            while (true) {

                reqCounter++;
                this.rid = this.uid + '-' + reqCounter;

                //read-req-header
                HeaderLines requestHeaders = new HeaderLines(HTTP.INIT_HEADER_LINES);
                int bytesRead = clientIn.readHeaders(requestHeaders, HTTP.MAX_HEADER_LENGTH);

                if (bytesRead == -1) {
                    logger.warn("{}, No headers received from client"
                            , rid
                    );
                    return;
                }

                // resolve locale: oe_lang cookie → Accept-Language → en
                String acceptLanguage = requestHeaders.getHeaderValueAsString(HTTP.HEADER.ACCEPT_LANGUAGE);
                String cookieHeader = requestHeaders.getHeaderValueAsString(HTTP.HEADER.COOKIE);
                this.currentLocale = HtmlUtil.resolveLocale(cookieHeader, acceptLanguage);

                this.oidHeader = requestHeaders.getHeaderValueAsString(HTTP.HEADER.OEHUB_OID);
                this.virtualHosts = ReverseProxyServer.getVirtualHosts(this.clientId, this.oidHeader);

                // Enqueue REQ header HttpEvent
                HttpEvent reqHeaderEvent = new HttpEvent(this.clientId, this.rid, HttpEventType.REQ_HEADER);
                reqHeaderEvent.setHeaders(requestHeaders);
                HttpEventManager.getInstance().enqueue(reqHeaderEvent);

                // Parse HTTP request
                HttpRequest httpRequest = requestHeaders.parseHttpRequest();
                this.currentHost = httpRequest.getHost();
                this.currentMethod = httpRequest.getMethod();

                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, Request Headers\n{}"
                            , rid
                            , HttpStream.Flow.REQ
                            , requestHeaders
                    );
                    logger.debug("{}, {}, Request: {} {} {} (Host: {}, Body: {}, Connection: {}, ContentLength: {})"
                            , rid
                            , HttpStream.Flow.REQ
                            , httpRequest.getMethod()
                            , httpRequest.getPath()
                            , httpRequest.getVersion()
                            , httpRequest.getHost()
                            , httpRequest.getHttpStream()
                            , httpRequest.getConnection()
                            , httpRequest.getContentLength()
                    );
                }

                VirtualPath virtualPath = getVirtualPath(rid, httpRequest.getHost(), httpRequest.getPath());

                //browser > keep-alive > route > target server
                boolean targetChanged = preVirtualPath == null
                        || !preVirtualPath.getTarget().toString().equals(virtualPath.getTarget().toString());
                preVirtualPath = virtualPath;

                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, targetChanged={}, targetServerSocket={}"
                            , rid
                            , HttpStream.Flow.REQ
                            , targetChanged
                            , serverSocket
                    );
                }

                //create socket - url matched
                if (serverSocket == null || targetChanged) {

                    //create new server socket - new target route
                    if (targetChanged && serverSocket != null) forceCloseServerSocket();

                    serverSocket = createServerSocket(rid, httpRequest.getHost(), virtualPath);
                    serverIn = new HttpStreamReader(serverSocket.getInputStream(), HTTP.BODY_BUFFER_SIZE);
                    serverOut = new HttpStreamWriter(serverSocket.getOutputStream());

                    String tName = Thread.currentThread().getName();
                    if( tName.endsWith("x0") ) tName = tName.substring(0, tName.length()-1) + reqCounter;

                    long myGeneration = socketGeneration.incrementAndGet();
                    child =  VThreadExecutor.run(
                            new PassResponseExecutor(this, serverIn, clientOut, myGeneration)
                            , tName
                        );

                }

                //write-req-header
                serverOut.writeHeaders(requestHeaders);

                if (HttpStream.WEBSOCKET == httpRequest.getHttpStream()) {
                    this.clientSocket.setSoTimeout(WEBSOCKET_IDLE_TIMEOUT_MS);
                    this.serverSocket.setSoTimeout(WEBSOCKET_IDLE_TIMEOUT_MS);
                }

                // Relay request body to server if exists
                HttpStream.Connection connection = RelayBody.relayRequestBody(this.clientId, rid, HttpStream.Flow.REQ, httpRequest, clientIn, serverOut);
                if (connection == HttpStream.Connection.CLOSE) {
                    this.stop = true;
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, stop={} or park"
                            , rid
                            , HttpStream.Flow.REQ
                            , this.stop
                    );
                }

                if (this.stop) {
                    break;
                }

                LockSupport.park();

                // PassResponseExecutor may have set stop=true (e.g. upstream read timeout) while
                // this thread was parked; honor it now instead of blocking on the next client read
                // with no one left to relay a response for the request already sent upstream.
                if (this.stop) {
                    break;
                }
            }
        } catch (BadGatewayException e) {
            logger.error("{}, {}, Upstream connection failed: {}"
                    , e.getRid()
                    , HttpStream.Flow.REQ
                    , e.getMessage()
                    , e
            );
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeBadGatewayResponse(clientOut, e, this.currentLocale);
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write 502 response: {}", uid, io.getMessage(), io);
            }
        } catch (SocketTimeoutException e){
            logger.error( uid + ", " + e.getMessage());
        } catch (SocketException e){
            if( "Connection reset".equals(e.getMessage())
                    || "Socket closed".equals(e.getMessage())
            ) {
                logger.error( uid + ", " + e.getMessage());
            } else {
                logger.error( uid + ", " + e.getMessage(), e);
            }
        } catch (NotFoundVhostException e) {
            logger.warn("{}, {}", uid, e.getMessage());
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeNotFoundVhostResponse(clientOut, e.getRequestHost(), e.getRequestPath(), this.currentLocale);
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write not-found-vhost response: {}", uid, io.getMessage(), io);
            }
        } catch (IOException e) {
            logger.error(uid + ", " + e.getMessage(), e);
        } catch (NotFoundProxyVirtualHostsException e) {
            logger.warn("{}, {}", uid, e.getMessage());
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeNoVhostsResponse(clientOut, this.clientId, this.oidHeader, this.currentLocale);
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write no-vhosts response: {}", uid, io.getMessage(), io);
            }
        } catch (IllegalArgumentException e) {
            // Malformed request line, or ambiguous Content-Length/Transfer-Encoding framing
            // rejected by HeaderLines.validateFraming() to prevent request smuggling.
            logger.warn("{}, Rejected malformed/ambiguous request: {}", uid, e.getMessage());
            try {
                if (clientOut != null && claimErrorResponse()) {
                    HtmlUtil.writeBadRequestResponse(clientOut, e.getMessage());
                }
            } catch (IOException io) {
                logger.error("{}, Failed to write 400 response: {}", uid, io.getMessage(), io);
            }
        } finally {
            VThreadExecutor.removeVirtualThread(Thread.currentThread());
            this.stop = true;
            closeAll();
        }


    }

    private void closeAll(){

        if( logger.isDebugEnabled() ){
            logger.debug( "{}, vtEnd & closeSocket, vtRes={}", this.uid, child != null ? child.getName() : "none" );
        }

        closeQuietly(serverIn, "serverIn");
        closeQuietly(serverOut, "serverOut");
        closeQuietly(serverSocket, "serverSocket");

        closeQuietly(clientIn, "clientIn");
        closeQuietly(clientOut, "clientOut");
        closeQuietly(clientSocket, "clientSocket");

        serverIn = null;
        serverOut = null;
        serverSocket = null;

        clientIn = null;
        clientOut = null;
        clientSocket = null;
    }

    private void forceCloseServerSocket(){

        closeQuietly(serverIn, "previous serverIn");
        closeQuietly(serverOut, "previous serverOut");
        closeQuietly(serverSocket, "previous serverSocket");

        serverIn = null;
        serverOut = null;
        serverSocket = null;
    }

    private void closeQuietly(Closeable resource, String label) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception e) {
            logger.debug("Error closing {}: {}", label, e.getMessage());
        }
    }

    private VirtualPath getVirtualPath(String rid, String vhost, String uri) throws IOException {

        if( logger.isDebugEnabled() ){
            logger.debug( "{}, {}, Find virtual path, vhost={}, uri={}"
                    , rid
                    , HttpStream.Flow.REQ
                    , vhost
                    , uri
            );
        }

        List<VirtualPath> urls = virtualHosts.get(vhost);

        if( urls==null || urls.isEmpty()) throw new NotFoundVhostException(vhost, uri, "Undefined vhost - " + vhost);

        VirtualPath virtualPath = null;

        for (VirtualPath vu : urls) {
            boolean matched = PATH_MATCHER.isMatch(vu.getPath(), uri);

            if (matched) {
                virtualPath = vu;
                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, Reserved path - {}, {}, {}, {}"
                            , rid
                            , HttpStream.Flow.REQ
                            , vhost
                            , vu.getPath()
                            , vu.getTarget()
                            , uri
                    );
                }
                break;
            }
        }

        if( virtualPath == null ) throw new NotFoundVhostException(vhost, uri, "Not found path - " + uri + " -- " + vhost);

        return virtualPath;
    }

    private Socket createServerSocket(String rid, String vhost, VirtualPath virtualPath) throws BadGatewayException {

        if( logger.isDebugEnabled() ){
            logger.debug( "{}, {}, Create socket, vhost={}, uri={}"
                    , rid
                    , HttpStream.Flow.REQ
                    , vhost
                    , virtualPath.getTarget()
            );
        }

        URL target = virtualPath.getTarget();

        try {
            if( "https".equals(target.getProtocol()) ){
                int port = target.getPort() <= 0 ? 443 : target.getPort();
                if( logger.isDebugEnabled() ){
                    logger.debug("{}, {}, Create HTTPS {}:{} / {}"
                            , rid
                            , HttpStream.Flow.REQ
                            , target.getHost()
                            , port
                            , vhost
                    );
                }
                return SocketUtils.createHttps(vhost, target.getHost(), port, this.connectTimeout, this.readTimeout);
            } else {
                int port = target.getPort() <= 0 ? 80 : target.getPort();
                if( logger.isDebugEnabled() ) logger.debug("{}, {}, Create HTTP {}:{} / {}"
                        , rid
                        , HttpStream.Flow.REQ
                        , target.getHost()
                        , port
                        , vhost
                );
                return SocketUtils.createHttp(target.getHost(), port, this.connectTimeout, this.readTimeout);
            }
        } catch (IOException e) {
            throw new BadGatewayException(rid, vhost, target, virtualPath.getPath(), e);
        }
    }

    @Override
    public void stop() {
        this.closeAll();
    }

    @Override
    public String getName() {
        if( this.thisThread==null ) return null;
        return thisThread.getName();
    }
}
