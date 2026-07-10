package tricatch.oe.proxy.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tricatch.oe.proxy.http.HTTP;
import tricatch.oe.proxy.http.io.HttpStream;
import tricatch.oe.proxy.http.io.ByteBuffer;
import tricatch.oe.proxy.http.io.HttpStreamReader;
import tricatch.oe.proxy.http.io.HttpStreamWriter;
import tricatch.oe.proxy.event.HttpEvent;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.event.HttpEventType;

import java.io.IOException;

/**
 * Class for handling chunked transfer encoding HTTP body relay operations
 */
public class RelayChunked {
    
    private static final Logger logger = LoggerFactory.getLogger(RelayChunked.class);
    
    /**
     * Relay chunked transfer encoding response body
     * @param clientId Client identifier
     * @param rid Request ID for logging
     * @param flow Body stream flow direction (REQ/RES)
     * @param in Input stream reader
     * @param out Output stream writer
     * @return HttpStream.Connection indicating whether connection should be closed
     * @throws IOException when I/O error occurs
     */
    public static HttpStream.Connection relay(String clientId, String rid, HttpStream.Flow flow, HttpStreamReader in, HttpStreamWriter out) throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Relaying chunked body"
                    , rid
                    , flow
            );
        }

        ByteBuffer chunkSizeBuffer = new ByteBuffer(HTTP.CHUNK_SIZE_LINE_LENGTH);
        ByteBuffer chunkTrailerBuffer = new ByteBuffer(HTTP.CHUNK_SIZE_LINE_LENGTH);
        byte[] chunkBodyBuffer = new byte[HTTP.BODY_BUFFER_SIZE];
        java.io.ByteArrayOutputStream bodyCollector = new java.io.ByteArrayOutputStream();
        
        while (true) {
            // Read chunk size line
            int bytesRead = in.readLine(chunkSizeBuffer, HTTP.CHUNK_SIZE_LINE_LENGTH);
            
            if (bytesRead == -1) {
                logger.warn("{}, {}, Unexpected end of stream while reading chunk size"
                        , rid
                        , flow
                );
                break;
            }
            
            int chunkSize;
            try {
                chunkSize = parseHexChunkSize(chunkSizeBuffer.getBuffer(), chunkSizeBuffer.getLength());
            } catch (NumberFormatException e) {
                logger.error("{}, {}, Invalid chunk size: {}"
                        , rid
                        , flow
                        , new String(chunkSizeBuffer.getBuffer(), 0, chunkSizeBuffer.getLength())
                );
                break;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("{}, {}, Chunk size: {} / hx{}"
                        , rid
                        , flow
                        , chunkSize
                        , new String(chunkSizeBuffer.getBuffer(), 0, chunkSizeBuffer.getLength())
                );
            }

            // Write chunk size to client
            out.write(chunkSizeBuffer.getBuffer(), 0, chunkSizeBuffer.getLength());
            out.write(HTTP.CRLF);
            
            if (chunkSize == 0) {

                for(;;){
                    bytesRead = in.readLine(chunkTrailerBuffer, HTTP.CHUNK_SIZE_LINE_LENGTH);
                    if( bytesRead < 0 ){
                        logger.warn("{}, {}, Unexpected end of stream while reading chunk trailer"
                                , rid
                                , flow
                        );
                        break;
                    }

                    if( bytesRead>0 ){
                        if( logger.isDebugEnabled() ){
                            logger.debug("{}, {}, Chunk trailer: {}"
                                    , rid
                                    , flow
                                    , new String(chunkTrailerBuffer.getBuffer(), 0, chunkTrailerBuffer.getLength())
                            );
                        }
                        out.write(chunkTrailerBuffer.getBuffer(), 0, chunkTrailerBuffer.getLength());
                    }

                    out.write(HTTP.CRLF);
                    out.flush();

                    if( bytesRead == 0 ) break;
                }

                // End of chunked body - read and relay trailer headers
                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, End of chunked body (chunk size 0) - reading trailer headers"
                            , rid
                            , flow
                    );
                }
                
                break;
            }
            
            // Relay chunk data
            int remainingBytes = chunkSize;
            while (remainingBytes > 0) {
                int bytesToRead = Math.min(chunkBodyBuffer.length, remainingBytes);
                bytesRead = in.read(chunkBodyBuffer, 0, bytesToRead);
                
                if (bytesRead == -1) {
                    logger.warn("{}, {}, Unexpected end of stream while reading chunk data"
                            , rid
                            , flow
                    );
                    break;
                }
                out.write(chunkBodyBuffer, 0, bytesRead);

                // Collect body data for logging
                bodyCollector.write(chunkBodyBuffer, 0, bytesRead);

                remainingBytes -= bytesRead;
                
                if (logger.isDebugEnabled()) {
                    logger.debug("{}, {}, Relayed {} bytes of chunk, remaining: {}"
                            , rid
                            , flow
                            , bytesRead
                            , remainingBytes
                    );
                }
            }
            
            // Read and relay chunk end (CR-LF)
            int cr = in.read();
            int lf = in.read();
            if (cr == '\r' && lf == '\n') {
                out.write(HTTP.CRLF);
                out.flush();
            } else {
                logger.warn("{}, {}, Invalid chunk end marker", rid, flow);
                break;
            }
        }
        
        out.flush();
        
        // Enqueue body HttpEvent
        HttpEvent bodyEvent = new HttpEvent(clientId, rid, 
            flow == HttpStream.Flow.REQ ? HttpEventType.REQ_BODY : HttpEventType.RES_BODY);
        bodyEvent.setBody(bodyCollector.toByteArray());
        bodyEvent.setHttpStream(HttpStream.CHUNKED);
        HttpEventManager.getInstance().enqueue(bodyEvent);

        if (logger.isDebugEnabled()) {
            logger.debug("{}, {}, Chunked body relay completed"
                    , rid
                    , flow
            );
        }
        
        return HttpStream.Connection.KEEP_ALIVE;
    }

    private static int parseHexChunkSize(byte[] buf, int len) {
        int end = len;
        for (int i = 0; i < len; i++) {
            byte b = buf[i];
            if (b == ';' || b == '\r' || b == '\n') { end = i; break; }
        }
        int start = 0;
        while (start < end && buf[start] == ' ') start++;
        while (end > start && buf[end - 1] == ' ') end--;
        if (start >= end) throw new NumberFormatException("Empty chunk size");
        int result = 0;
        for (int i = start; i < end; i++) {
            byte b = buf[i];
            int digit;
            if      (b >= '0' && b <= '9') digit = b - '0';
            else if (b >= 'a' && b <= 'f') digit = b - 'a' + 10;
            else if (b >= 'A' && b <= 'F') digit = b - 'A' + 10;
            else throw new NumberFormatException("Invalid hex char: " + (char) b);
            result = (result << 4) | digit;
        }
        return result;
    }
}
