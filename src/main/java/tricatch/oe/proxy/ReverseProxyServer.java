package tricatch.oe.proxy;

import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import tricatch.oe.hub.config.AppHome;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubConf;
import tricatch.oe.proxy.cfg.Config;
import tricatch.oe.proxy.cfg.VirtualHost;
import tricatch.oe.proxy.event.HttpEventManager;
import tricatch.oe.proxy.exception.ConfigException;
import tricatch.oe.proxy.exception.NotFoundProxyVirtualHostsException;
import tricatch.oe.proxy.exception.NotReadyCaException;
import tricatch.oe.proxy.mapper.ProxyVhostMapper;
import tricatch.oe.proxy.model.ProxyVhost;
import tricatch.oe.proxy.server.*;
import tricatch.oe.proxy.service.ProxyConfService;
import tricatch.oe.proxy.service.ProxyVhostService;
import tricatch.oe.proxy.util.ClaimedIpRegistry;
import tricatch.oe.proxy.util.OidUtil;
import tricatch.oe.proxy.util.SSLUtil;
import tricatch.oe.proxy.util.VirtualHostUtil;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.MalformedURLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class ReverseProxyServer {

    private static final Logger logger = LoggerFactory.getLogger(ReverseProxyServer.class);

    private static final String VHOST_DIR = "./conf/vhost";

    private static final ThreadPoolExecutor serverExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    // Routes are keyed by owner (OID, an opaque encoding of HUB_USR.user_no — see OidUtil),
    // not by client IP. IP-based fallback identification now lives in ClaimedIpRegistry (an
    // explicit, time-bounded claim - see resolveOid()) rather than an unbounded map here.
    private static final ConcurrentHashMap<String, VirtualHosts> oidVirtualHostsMap = new ConcurrentHashMap<>();
    private static SqlSessionFactory sqlSessionFactory = null;

    // OID identification (X-OeHub-Oid header) is always available; IP-based identification
    // is a fallback that an admin can disable via /oehub/settings when it's unreliable
    // (e.g. clients sitting behind a shared/NATed IP).
    private static final String KEY_IP_IDENTIFIER_ENABLED = "identifier.ip.enabled";
    private static volatile boolean ipIdentifierEnabled = true;

    // When an https backend's certificate fails validation, the client has no way to tell that's
    // the cause - PassRequestExecutor only ever surfaces a generic BadGatewayException. Internal
    // dev backends commonly use ad-hoc self-signed certs with no common CA to import, so this is
    // on by default; SocketUtils.isPrivateNetworkAddress still confines the bypass to
    // private/loopback/link-local backends even while enabled.
    private static final String KEY_TRUST_INTERNAL_CERT_ENABLED = "cert.trust.internal.enabled";
    private static volatile boolean trustInternalCertEnabled = true;

    // Server-only secret behind X-OeHub-Oid — see OidUtil. Generated once and persisted the same
    // way JwtService persists its signing key, so it survives restarts but never leaves this server.
    private static final String KEY_OID_SECRET = "oid.secret";

    public static void init(SqlSessionFactory factory) {
        sqlSessionFactory = factory;
        var stored = new ProxyConfService(factory).get(KEY_IP_IDENTIFIER_ENABLED, null);
        ipIdentifierEnabled = !"false".equals(stored);
        var storedTrustInternal = new ProxyConfService(factory).get(KEY_TRUST_INTERNAL_CERT_ENABLED, null);
        trustInternalCertEnabled = !"false".equals(storedTrustInternal);
        OidUtil.init(loadOrCreateOidSecret(factory));
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
            toSave.setUpdatedAt(java.time.LocalDateTime.now());
            mapper.upsert(toSave);
            session.commit();
            return secret;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize OID secret", e);
        }
    }

    public static boolean isIpIdentifierEnabled() {
        return ipIdentifierEnabled;
    }

    public static void setIpIdentifierEnabled(boolean enabled) {
        ipIdentifierEnabled = enabled;
        new ProxyConfService(sqlSessionFactory).set(KEY_IP_IDENTIFIER_ENABLED, null, String.valueOf(enabled));
    }

    public static boolean isTrustInternalCertEnabled() {
        return trustInternalCertEnabled;
    }

    public static void setTrustInternalCertEnabled(boolean enabled) {
        trustInternalCertEnabled = enabled;
        new ProxyConfService(sqlSessionFactory).set(KEY_TRUST_INTERNAL_CERT_ENABLED, null, String.valueOf(enabled));
    }

    // Exposed so ForwardProxyServer can recognize a ${PROXY_SVR} self-loop connection (its own
    // outbound connection back into this reverse proxy) without hardcoding the port a second time.
    public static final int HTTPS_PORT = 443;

    private static Config config = null;
    private static SSLContext sslContext = null;
    private static SSLPassServer sslPassServer = null;

    static {

        config = new Config();

        config.getHttps().setPort(HTTPS_PORT);
        config.getHttps().setConnectTimeout(3000);
        config.getHttps().setReadTimeout(30000);

        config.getConsole().setPort(36900);
        config.getConsole().setConnectTimeout(3000);
        config.getConsole().setReadTimeout(30000);

        java.nio.file.Path caDir = AppHome.oeHubDir().resolve("root-ca");
        config.getCa().setCert(caDir.resolve("ca.cer").toString());
        config.getCa().setPriKey(caDir.resolve("ca.pfx").toString());
        config.getCa().setPriPwd("");
    }

    private static void initSslContext() throws ConfigException {

        var ca = config.getCa();

        var certPath = java.nio.file.Path.of(ca.getCert());
        var keyPath  = java.nio.file.Path.of(ca.getPriKey());

        if (!java.nio.file.Files.exists(certPath) || !java.nio.file.Files.exists(keyPath)) {
            throw new NotReadyCaException("CA files not found: " + certPath + ", " + keyPath);
        }

        try {
            if (sslContext != null) {
                logger.info("Clearing existing SSL context");
            }
            sslContext = SSLUtil.initializeSSLContext(config);
            logger.info("SSL context initialized successfully");
        } catch (NotReadyCaException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to initialize SSL context", e);
            sslContext = null;
            throw e;
        }
    }

    public static synchronized void startSslPassServer() throws ConfigException, InterruptedException {

        initSslContext();

        if( sslPassServer!=null ){

            logger.info("Restart {}", SSLPassServer.class.getSimpleName());

            VThreadExecutor.stopAll();
            sslPassServer.stop();

            for(int i=0;i<10;i++){
                Thread.sleep(1000);
                RunState runState = sslPassServer.getRunState();
                if( RunState.STOPPED == runState ){
                    break;
                }
            }

        } else {

            logger.info("Start {}", SSLPassServer.class.getSimpleName());

            HttpEventManager.getInstance();
        }

        sslPassServer = new SSLPassServer();
        serverExecutor.execute(sslPassServer);
    }


    public static void setVirtualHosts(Long userNo, String virtualHostsConfigYaml) throws MalformedURLException {

        Representer representer = new Representer(new DumperOptions());
        representer.getPropertyUtils().setSkipMissingProperties(true);
        LoaderOptions loaderOptions = new LoaderOptions();
        // virtualHostsConfigYaml is attacker-controllable (any authenticated user's own vhost
        // content). Plain Constructor honors an explicit YAML tag (e.g. "!!javax.script.ScriptEngineManager")
        // on ANY node regardless of the field's declared Java type, and merely constructing that
        // node can have side effects — a well-known SnakeYAML RCE gadget class. Reject every
        // explicit tag; legitimate vhost YAML never needs one (types are resolved implicitly via
        // the VirtualHost/VirtualDomain/VirtualLocation JavaBean shape).
        loaderOptions.setTagInspector(tag -> false);

        Constructor constructorVirtualHost = new Constructor(VirtualHost.class, loaderOptions);
        Yaml yamlVirtualHost = new Yaml(constructorVirtualHost, representer);

        VirtualHost virtualHost = yamlVirtualHost.load(virtualHostsConfigYaml);

        String oid = OidUtil.encode(userNo);
        oidVirtualHostsMap.put(oid, VirtualHostUtil.convert(virtualHost.getVirtual()));
    }

    // Drops only the routing table. An IP claim is a deliberate, user-initiated action (see
    // ClaimedIpRegistry.claim()/apiTakeIp) and must not be cleared as a side effect of unrelated
    // vhost changes - it is released only by that same explicit action, either when the owner
    // claims a different IP (claim() releases their own prior claim first) or when another account
    // claims this same IP (claim()'s put() naturally overwrites the single owner a claimed IP maps
    // to). See invalidateVirtualHosts() below for the analogous no-side-effect table clear.
    public static void clearVirtualHosts(Long userNo) {
        oidVirtualHostsMap.remove(OidUtil.encode(userNo));
    }

    // Drops only the cached routing table, leaving any IP claim intact — for invalidating an
    // owner who isn't the current request (e.g. a collaborator on a shared vhost someone else
    // just edited). Their next request finds no cached entry and lazily reloads via getVirtualHosts().
    public static void invalidateVirtualHosts(Long userNo) {
        oidVirtualHostsMap.remove(OidUtil.encode(userNo));
    }

    /** Claims clientIp for userNo in ClaimedIpRegistry - see ProxyController.apiTakeIp. */
    public static void claimIp(String clientIp, Long userNo) {
        ClaimedIpRegistry.claim(clientIp, OidUtil.encode(userNo));
    }

    public static long claimedIpTtlHours() {
        return ClaimedIpRegistry.ttlHours();
    }

    /** The oid currently holding clientIp in ClaimedIpRegistry, or null if unclaimed. */
    public static String getClaimedOid(String clientIp) {
        return ClaimedIpRegistry.get(clientIp);
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

    /**
     * Resolves the owner oid for a request the same way getVirtualHosts() does — X-OeHub-Oid
     * header first, then the (togglable) IP-based fallback — without requiring a vhost lookup
     * to succeed. Used to tag each HttpEvent enqueued for a request/response with its true
     * owner, so the live traffic monitor (ProxyController.monitorEvent) can filter by account
     * instead of raw client IP: under NAT/CGNAT/shared egress, two different oeHub accounts can
     * share one IP, and IP-only matching would let one account's monitor view see the other's
     * live traffic (headers, cookies, bodies). Returns null when ownership can't be resolved —
     * callers should simply not attribute/route the event rather than fail the request over it.
     */
    public static String resolveOid(String clientIp, String oidHeader) {
        if (oidHeader != null && !oidHeader.isBlank()) {
            Long userNo = OidUtil.decode(oidHeader);
            return userNo == null ? null : OidUtil.encode(userNo);
        }
        if (ipIdentifierEnabled) {
            String oid = ClaimedIpRegistry.get(clientIp);
            if (oid != null) return oid;
        }
        // No X-OeHub-Oid header and no claimed IP (see ClaimedIpRegistry / "Use This IP" on the
        // oeProxy page) - ownership genuinely can't be resolved. There is deliberately no
        // loopback/sole-account shortcut here anymore: it silently identified any 127.0.0.1
        // request as the one existing account, which broke down the moment a second account was
        // created and gave no visible signal that identification was implicit rather than explicit.
        return null;
    }

    public static VirtualHosts getVirtualHosts(String clientIp, String oidHeader) throws NotFoundProxyVirtualHostsException {
        // X-OeHub-Oid header takes precedence over the IP-based owner lookup,
        // since a shared/proxied client IP can otherwise resolve to the wrong owner.
        // The IP-based fallback itself can be turned off entirely via settings when
        // IP identification is untrustworthy (see ipIdentifierEnabled).
        if (oidHeader != null && !oidHeader.isBlank() && OidUtil.decode(oidHeader) == null) {
            throw new NotFoundProxyVirtualHostsException("Invalid X-OeHub-Oid header: " + oidHeader);
        }
        String oid = resolveOid(clientIp, oidHeader);
        if (oid == null) throw new NotFoundProxyVirtualHostsException("No owner mapped for IP: " + clientIp);
        VirtualHosts virtualHosts = oidVirtualHostsMap.get(oid);
        if (virtualHosts == null) {
            virtualHosts = reloadVirtualHostsFromDb(clientIp, oid);
        }
        if (virtualHosts == null) throw new NotFoundProxyVirtualHostsException("No virtual hosts configured for owner: " + oid);
        return virtualHosts;
    }

    // A server restart clears oidVirtualHostsMap, so the first request from an owner who
    // hasn't re-logged-in or re-saved yet would otherwise fail with NotFoundProxyVirtualHostsException.
    // Rebuild that owner's entry on demand from their persisted, currently-selected vhosts instead.
    private static VirtualHosts reloadVirtualHostsFromDb(String clientIp, String oid) {
        Long userNo = OidUtil.decode(oid);
        if (userNo == null || sqlSessionFactory == null) return null;
        try {
            var vhostService = new ProxyVhostService(sqlSessionFactory);
            var confService   = new ProxyConfService(sqlSessionFactory);
            var merged = mergeVhostYaml(vhostService.listSelected(userNo));
            if (merged == null || merged.isBlank()) return null;
            var localSvrOverride = confService.get("local_svr", userNo);
            var localSvr = localSvrOverride != null && !localSvrOverride.isBlank() ? localSvrOverride : clientIp;
            setVirtualHosts(userNo, merged.replace("${LOCAL_SVR}", localSvr));
            return oidVirtualHostsMap.get(oid);
        } catch (Exception e) {
            logger.warn("Failed to lazily reload virtual hosts for owner {}: {}", oid, e.getMessage());
            return null;
        }
    }

    public static Config getConfig(){
        return config;
    }
    public static SSLContext getSslContext(){
        return sslContext;
    }

    @SuppressWarnings("unchecked")
    public static String mergeVhostYaml(java.util.List<ProxyVhost> vhosts) {
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        var allVirtual  = new java.util.ArrayList<>();
        var seenDomains = new java.util.LinkedHashSet<String>();

        for (var vhost : vhosts) {
            if (vhost.getVhostContent() == null || vhost.getVhostContent().isBlank()) continue;
            try {
                var parsed = (java.util.Map<String, Object>) yaml.load(vhost.getVhostContent());
                if (parsed != null && parsed.get("virtual") instanceof java.util.List<?> virtual) {
                    for (var entry : virtual) {
                        if (entry instanceof java.util.Map<?, ?> map) {
                            var domain = (String) map.get("domain");
                            if (domain != null && seenDomains.contains(domain)) continue;
                            if (domain != null) seenDomains.add(domain);
                            allVirtual.add(entry);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (allVirtual.isEmpty()) return "";

        var opts = new DumperOptions();
        opts.setIndent(2);
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("virtual", allVirtual);
        return new Yaml(opts).dump(result);
    }

}
