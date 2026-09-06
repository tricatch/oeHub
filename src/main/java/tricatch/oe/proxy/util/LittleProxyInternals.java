package tricatch.oe.proxy.util;

import io.netty.channel.ChannelHandlerContext;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.ProxyToServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Reaches past a bug in littleproxy 2.9.0/2.9.1: for a raw (non-MITM) HTTP CONNECT tunnel,
 * {@code FullFlowContext.getProxyToServerContext()} always returns null. The proxy-to-server
 * channel context is captured once, into a cached {@code FullFlowContext}, the first time ANY
 * activity-tracking code asks for it (see {@code ClientToProxyConnection.flowContextForServerConnection}'s
 * {@code computeIfAbsent}) - and that first ask happens before the proxy-to-server channel exists,
 * permanently freezing a null context into the cached object. Verified empirically against both
 * 2.9.0 and 2.9.1 with a standalone reproduction outside oeHub; the upstream GitHub source shows
 * the same gap (no code path calls the equivalent success callback for a raw CONNECT tunnel), so
 * this is not something a routine version bump fixes.
 *
 * {@code ProxyToServerConnection.getContext()} itself is NOT cached and always returns the live,
 * current channel context - the bug is purely in FullFlowContext's stale copy. This class reaches
 * past that copy via the two private fields that connect a FullFlowContext back to the live
 * objects it was built from:
 * <pre>
 *   FlowContext.clientConnection (private)
 *     -&gt; ClientToProxyConnection.currentServerConnection (private)
 *       -&gt; ProxyToServerConnection.getContext() (public, live)
 * </pre>
 *
 * This depends on undocumented private field names with no compatibility guarantee across
 * littleproxy versions. If a future upgrade renames or removes either field, every method here
 * degrades to returning null (logged once) instead of throwing, so self-loop owner resolution
 * (see SelfLoopOwnerRegistry) simply stops firing rather than crashing the forward proxy - the
 * existing X-OeHub-Oid header / "Use This IP" fallbacks still work. Re-verify this class first
 * whenever the littleproxy dependency version changes.
 */
public class LittleProxyInternals {

    private static final Logger logger = LoggerFactory.getLogger(LittleProxyInternals.class);

    private static final Field CLIENT_CONNECTION_FIELD = lookupField(FlowContext.class, "clientConnection");
    private static final Field CURRENT_SERVER_CONNECTION_FIELD = lookupField(ClientToProxyConnection.class, "currentServerConnection");

    private static volatile boolean warnedOnUse = false;

    private LittleProxyInternals() {}

    private static Field lookupField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            logger.warn("LittleProxyInternals: {}.{} not found (littleproxy version changed?) - " +
                    "self-loop owner resolution disabled: {}", owner.getName(), name, e.toString());
            return null;
        }
    }

    /** The ClientToProxyConnection behind this flow, or null if unavailable. */
    public static ClientToProxyConnection clientConnectionOf(FlowContext flowContext) {
        if (CLIENT_CONNECTION_FIELD == null || flowContext == null) return null;
        try {
            return (ClientToProxyConnection) CLIENT_CONNECTION_FIELD.get(flowContext);
        } catch (Exception e) {
            warnOnUse(e);
            return null;
        }
    }

    /**
     * The real, live proxy-to-server ChannelHandlerContext for this client connection's current
     * server connection - unlike FullFlowContext.getProxyToServerContext(), which is permanently
     * null for a raw CONNECT tunnel (see class javadoc).
     */
    public static ChannelHandlerContext liveProxyToServerContext(ClientToProxyConnection clientConnection) {
        if (CURRENT_SERVER_CONNECTION_FIELD == null || clientConnection == null) return null;
        try {
            ProxyToServerConnection serverConnection = (ProxyToServerConnection) CURRENT_SERVER_CONNECTION_FIELD.get(clientConnection);
            return serverConnection == null ? null : serverConnection.getContext();
        } catch (Exception e) {
            warnOnUse(e);
            return null;
        }
    }

    private static void warnOnUse(Exception e) {
        if (!warnedOnUse) {
            warnedOnUse = true;
            logger.warn("LittleProxyInternals: reflection failed at runtime (littleproxy version changed?) - " +
                    "self-loop owner resolution disabled: {}", e.toString());
        }
    }
}
