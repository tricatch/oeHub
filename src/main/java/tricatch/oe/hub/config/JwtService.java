package tricatch.oe.hub.config;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.model.HubConf;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;

public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    private static final String KEY_PRIVATE = "auth.privateKey";
    private static final String KEY_PUBLIC  = "auth.publicKey";

    private final PrivateKey privateKey;
    private final PublicKey  publicKey;

    public JwtService(SqlSessionFactory factory) {

        if( logger.isDebugEnabled() ) logger.debug("load jwt key");

        try (var session = factory.openSession()) {
            var mapper = session.getMapper(HubConfMapper.class);
            var priConf = mapper.findByConfKey(KEY_PRIVATE);
            var pubConf  = mapper.findByConfKey(KEY_PUBLIC);

            // Never log key material: the private key would let anyone who reads the log
            // forge a valid JWT for any user, permanently (until the key is rotated).
            if( logger.isDebugEnabled() ) logger.debug("jwt keys loaded from db - pri.present={}, pub.present={}", priConf != null, pubConf != null);

            if (priConf != null && pubConf != null) {
                privateKey = loadPrivateKey(priConf.getConfVal());
                publicKey  = loadPublicKey(pubConf.getConfVal());
            } else {
                var kp = generateKeyPair();
                privateKey = kp.getPrivate();
                publicKey  = kp.getPublic();

                var now = LocalDateTime.now();
                upsert(mapper, KEY_PRIVATE, Base64.getEncoder().encodeToString(privateKey.getEncoded()), now);
                upsert(mapper, KEY_PUBLIC,  Base64.getEncoder().encodeToString(publicKey.getEncoded()),  now);
                session.commit();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JWT keys", e);
        }
    }

    /** Verified token payload: userNo plus the token_version it was issued with, so the caller
     *  can compare it against the user's current token_version and reject stale tokens (e.g.
     *  issued before a password change). */
    public record VerifiedToken(Long userNo, int tokenVersion) {}

    public String issue(Long userNo, int tokenVersion, boolean rememberMe) {
        var now    = new Date();
        var expiry = rememberMe
            ? new Date(now.getTime() + 365L * 24 * 3600 * 1000)
            : new Date(now.getTime() + 24L  * 3600 * 1000);

        return Jwts.builder()
            .subject(String.valueOf(userNo))
            .claim("tv", tokenVersion)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(privateKey)
            .compact();
    }

    public VerifiedToken verify(String token) {

        if (token == null) return null;
        try {
            var claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            Long userNo = Long.parseLong(claims.getSubject());
            // Tokens issued before this field existed carry no "tv" claim; treat that as
            // version 0, which matches the DEFAULT 0 every existing user row already has.
            Integer tv = claims.get("tv", Integer.class);
            return new VerifiedToken(userNo, tv != null ? tv : 0);
        } catch (JwtException | IllegalArgumentException e) {
            logger.warn("errorJwtVerify - {}", e.getMessage(), e);
        }
        return null;
    }

    private static KeyPair generateKeyPair() throws GeneralSecurityException {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static PrivateKey loadPrivateKey(String b64) throws GeneralSecurityException {
        var bytes = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static PublicKey loadPublicKey(String b64) throws GeneralSecurityException {
        var bytes = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }

    // No HTTP request / logged-in actor at JVM-startup key generation time, so attribute it to
    // the reserved "system" sentinel (0L) rather than a real HUB_USR.user_no (see CLAUDE.md).
    private static void upsert(HubConfMapper mapper, String kid, String val, LocalDateTime now) {
        var conf = new HubConf();
        conf.setConfKey(kid);
        conf.setConfVal(val);
        conf.setCreatedBy(0L);
        conf.setUpdatedBy(0L);
        conf.setCreateAt(now);
        conf.setUpdatedAt(now);
        mapper.upsert(conf);
    }
}
