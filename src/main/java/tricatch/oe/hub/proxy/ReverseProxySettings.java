package tricatch.oe.hub.proxy;

import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.config.AppHome;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubConf;
import tricatch.oe.hub.proxy.model.ProxyVhost;
import tricatch.oe.hub.proxy.service.ProxyConfService;
import tricatch.oe.hub.proxy.service.ProxyVhostService;
import tricatch.oe.proxy.ReverseProxyServer;

/**
 * Everything ReverseProxyServer (now the standalone oe-proxy library, see CLAUDE.md) used to do
 * with the oeHub database directly - toggle persistence, the OID HMAC secret, the CA files, and
 * the DB-backed virtual-hosts lazy-reload - lives here instead, wired into the library purely
 * through its {@code tricatch.oe.proxy.spi} interfaces and static setters.
 */
public class ReverseProxySettings {

    private static final Logger logger = LoggerFactory.getLogger(ReverseProxySettings.class);

    private static final String KEY_IP_IDENTIFIER_ENABLED = "identifier.ip.enabled";
    private static final String KEY_TRUST_INTERNAL_CERT_ENABLED = "cert.trust.internal.enabled";
    private static final String KEY_INTERNAL_ONLY_UPSTREAM = "upstream.internal.only";
    private static final String KEY_OID_SECRET = "oid.secret";

    // The address shown as ${PROXY_SVR} on hosts/share pages (see HostsController) and substituted
    // into their content - what other machines should use to reach this reverse proxy. A plain
    // admin-set value rather than something derived per visitor: it used to be looked up from DNS
    // for the domain name the visitor happened to connect with (bounded by an admin allow-list, to
    // stop an unauthenticated caller from turning the lookup into an arbitrary-domain DNS oracle),
    // but that only mattered for deployments reachable through several different domains/paths at
    // once. Defaults to the loopback address, which is only meaningful for a reverse proxy running
    // on the same machine as its clients; every other deployment needs the admin to set it.
    private static final String KEY_PROXY_SVR_ADDRESS = "proxysvr.address";
    private static final String DEFAULT_PROXY_SVR_ADDRESS = "127.0.0.1";
    private static volatile String proxySvrAddress = DEFAULT_PROXY_SVR_ADDRESS;

    private static SqlSessionFactory sqlSessionFactory = null;

    public static void init(SqlSessionFactory factory) {
        sqlSessionFactory = factory;

        var confService = new ProxyConfService(factory);
        var storedIpIdentifier = confService.get(KEY_IP_IDENTIFIER_ENABLED, null);
        ReverseProxyServer.setIpIdentifierEnabled("true".equals(storedIpIdentifier));
        var storedTrustInternal = confService.get(KEY_TRUST_INTERNAL_CERT_ENABLED, null);
        ReverseProxyServer.setTrustInternalCertEnabled(!"false".equals(storedTrustInternal));
        var storedInternalOnly = confService.get(KEY_INTERNAL_ONLY_UPSTREAM, null);
        ReverseProxyServer.setInternalOnlyUpstream(!"false".equals(storedInternalOnly));

        var storedProxySvr = confService.get(KEY_PROXY_SVR_ADDRESS, null);
        proxySvrAddress = (storedProxySvr != null && !storedProxySvr.isBlank()) ? storedProxySvr : DEFAULT_PROXY_SVR_ADDRESS;

        OidUtil.init(loadOrCreateOidSecret(factory));
        ReverseProxyServer.setIdentifierCodec(new OidIdentifierCodec());
        // oeHub's browser extension and forward proxy send this header name.
        ReverseProxyServer.setIdentifierHeaderName("X-OeHub-Oid");

        var caDir = AppHome.oeHubDir().resolve("root-ca");
        ReverseProxyServer.setCaFiles(caDir.resolve("ca.cer"), caDir.resolve("ca.pfx"), "");

        ReverseProxyServer.setVirtualHostsLoader(ReverseProxySettings::loadVirtualHosts);
        ReverseProxyServer.setErrorPageRenderer(new PebbleErrorPageRenderer());
    }

    private static byte[] loadOrCreateOidSecret(SqlSessionFactory factory) {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(HubConfMapper.class);
            var conf = mapper.findByConfKey(KEY_OID_SECRET);
            if (conf != null) {
                return java.util.Base64.getDecoder().decode(conf.getConfVal());
            }
            var secret = new byte[32];
            new java.security.SecureRandom().nextBytes(secret);

            var toSave = new HubConf();
            toSave.setConfKey(KEY_OID_SECRET);
            toSave.setConfVal(java.util.Base64.getEncoder().encodeToString(secret));
            // No HTTP request / logged-in actor at JVM-startup secret generation time.
            toSave.setCreatedBy(0L);
            toSave.setUpdatedBy(0L);
            toSave.setCreateAt(java.time.LocalDateTime.now());
            toSave.setUpdatedAt(java.time.LocalDateTime.now());
            mapper.upsert(toSave);
            session.commit();
            return secret;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize OID secret", e);
        }
    }

    // A server restart clears ReverseProxyServer's routing table, so the first request from an
    // owner who hasn't re-logged-in or re-saved yet would otherwise fail. Rebuild that owner's
    // entry on demand from their persisted, currently-selected vhosts instead - registered with
    // the library as its VirtualHostsLoader SPI (see ReverseProxyServer.setVirtualHostsLoader).
    private static String loadVirtualHosts(long userNo, String clientIp) throws Exception {
        var vhostService = new ProxyVhostService(sqlSessionFactory);
        var confService   = new ProxyConfService(sqlSessionFactory);
        var selected = vhostService.listSelected(userNo);
        var merged = ReverseProxyServer.mergeVhostYaml(selected.stream().map(ProxyVhost::getVhostContent).toList());
        if (merged == null || merged.isBlank()) return null;
        var localSvrOverride = confService.get("local_svr", userNo);
        var localSvr = localSvrOverride != null && !localSvrOverride.isBlank() ? localSvrOverride : clientIp;
        return merged.replace("${LOCAL_SVR}", localSvr);
    }

    public static boolean isIpIdentifierEnabled() {
        return ReverseProxyServer.isIpIdentifierEnabled();
    }

    public static void setIpIdentifierEnabled(boolean enabled, Long actorUserNo) {
        ReverseProxyServer.setIpIdentifierEnabled(enabled);
        new ProxyConfService(sqlSessionFactory).set(KEY_IP_IDENTIFIER_ENABLED, null, String.valueOf(enabled), actorUserNo);
    }

    public static boolean isTrustInternalCertEnabled() {
        return ReverseProxyServer.isTrustInternalCertEnabled();
    }

    public static void setTrustInternalCertEnabled(boolean enabled, Long actorUserNo) {
        ReverseProxyServer.setTrustInternalCertEnabled(enabled);
        new ProxyConfService(sqlSessionFactory).set(KEY_TRUST_INTERNAL_CERT_ENABLED, null, String.valueOf(enabled), actorUserNo);
    }

    public static boolean isInternalOnlyUpstream() {
        return ReverseProxyServer.isInternalOnlyUpstream();
    }

    public static void setInternalOnlyUpstream(boolean enabled, Long actorUserNo) {
        ReverseProxyServer.setInternalOnlyUpstream(enabled);
        new ProxyConfService(sqlSessionFactory).set(KEY_INTERNAL_ONLY_UPSTREAM, null, String.valueOf(enabled), actorUserNo);
    }

    public static String getProxySvrAddress() {
        return proxySvrAddress;
    }

    public static void setProxySvrAddress(String address, Long actorUserNo) {
        var trimmed = address == null ? "" : address.trim();
        proxySvrAddress = trimmed.isEmpty() ? DEFAULT_PROXY_SVR_ADDRESS : trimmed;
        new ProxyConfService(sqlSessionFactory).set(KEY_PROXY_SVR_ADDRESS, null, proxySvrAddress, actorUserNo);
    }

    /** The userId of the account behind oid, for display (e.g. "so-and-so already claimed this IP"). */
    public static String getUserIdForOid(String oid) {
        Long userNo = OidUtil.decode(oid);
        if (userNo == null || sqlSessionFactory == null) return null;
        try (var session = sqlSessionFactory.openSession()) {
            var user = session.getMapper(HubUserMapper.class).findByUserNo(userNo);
            return user != null ? user.getUserId() : null;
        } catch (Exception e) {
            logger.warn("Failed to resolve userId for claimed-IP oid: {}", e.getMessage());
            return null;
        }
    }
}
