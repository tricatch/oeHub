package tricatch.oe.hub.proxy;

import tricatch.oe.proxy.spi.IdentifierCodec;

/**
 * Wires oeHub's HMAC-signed OID scheme (see {@link OidUtil}) into the oe-proxy library's
 * {@link IdentifierCodec} SPI, so the library's identifier header carries an unforgeable value
 * even though the library itself no longer knows anything about HMAC/base32 encoding. Registered
 * via {@code ReverseProxyServer.setIdentifierCodec} in {@link ReverseProxySettings#init}.
 */
public class OidIdentifierCodec implements IdentifierCodec {

    @Override
    public String encode(long ownerId) {
        return OidUtil.encode(ownerId);
    }

    @Override
    public Long decode(String value) {
        return OidUtil.decode(value);
    }
}
