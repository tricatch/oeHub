package tricatch.oe.hub.config;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.model.HubConf;
import tricatch.oe.proxy.util.JsonUtil;

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

            if( logger.isDebugEnabled() ) logger.debug("jwt.pri.key={}", JsonUtil.toJson(priConf));
            if( logger.isDebugEnabled() ) logger.debug("jwt.pub.key={}", JsonUtil.toJson(pubConf));

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

    public String issue(Long userNo, boolean rememberMe) {
        var now    = new Date();
        var expiry = rememberMe
            ? new Date(now.getTime() + 365L * 24 * 3600 * 1000)
            : new Date(now.getTime() + 24L  * 3600 * 1000);

        return Jwts.builder()
            .subject(String.valueOf(userNo))
            .issuedAt(now)
            .expiration(expiry)
            .signWith(privateKey)
            .compact();
    }

    public Long verify(String token) {

        if (token == null) return null;
        try {
            var claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (JwtException | NumberFormatException e) {
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

    private static void upsert(HubConfMapper mapper, String kid, String val, LocalDateTime now) {
        var conf = new HubConf();
        conf.setConfKey(kid);
        conf.setConfVal(val);
        conf.setUpdatedAt(now);
        mapper.upsert(conf);
    }
}
