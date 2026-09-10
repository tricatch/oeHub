package tricatch.oe.hub.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.WorkspaceMapper;
import tricatch.oe.hub.mapper.WsInviteMapper;
import tricatch.oe.hub.mapper.WsKeyMapper;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.mapper.HostsProfMapper;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.proxy.mapper.ProxyConfMapper;
import tricatch.oe.proxy.mapper.ProxyVhostMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

public class DatabaseConfig {

    // oeHub is a single-machine embedded app (H2 file + AUTO_SERVER, no network DB), so pool
    // sizing only needs to cover this JVM's own request-handling threads, not a shared server
    // fleet. MIN keeps a couple of connections warm to avoid reconnect latency on the first
    // requests after idle periods; MAX caps how many concurrent physical connections (each an
    // AUTO_SERVER TCP round trip) a request burst can open.
    private static final int DB_POOL_MIN_IDLE = 2;
    private static final int DB_POOL_MAX_SIZE = 10;

    public static SqlSessionFactory buildSqlSessionFactory() {
        var dataDir = AppHome.oeHubDir().resolve("data");
        var dbPath = dataDir.resolve("oeHub-h2");

        var hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("oeHub-h2-pool");
        hikariConfig.setJdbcUrl("jdbc:h2:file:" + dbPath + ";AUTO_SERVER=TRUE");
        hikariConfig.setUsername("sa");
        hikariConfig.setPassword(loadOrCreateDbPassword(dataDir));
        hikariConfig.setDriverClassName("org.h2.Driver");
        hikariConfig.setMinimumIdle(DB_POOL_MIN_IDLE);
        hikariConfig.setMaximumPoolSize(DB_POOL_MAX_SIZE);
        var ds = new HikariDataSource(hikariConfig);

        var env = new Environment("default", new JdbcTransactionFactory(), ds);
        var config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(HubUserMapper.class);
        config.addMapper(WorkspaceMapper.class);
        config.addMapper(WsInviteMapper.class);
        config.addMapper(WsKeyMapper.class);
        config.addMapper(HubConfMapper.class);
        config.addMapper(HostsProfMapper.class);
        config.addMapper(HostsConfMapper.class);
        config.addMapper(HostsUaMapper.class);
        config.addMapper(HostsUrlMapper.class);
        config.addMapper(ProxyVhostMapper.class);
        config.addMapper(ProxyConfMapper.class);

        var factory = new SqlSessionFactoryBuilder().build(config);
        initSchema(factory);
        return factory;
    }

    // A fixed literal password here would be a documented credential for the local H2 TCP/web
    // server (which listens independently of oeHub's own login) — anyone who can read the H2
    // source or this class could connect and read the whole database, including password hashes,
    // bypassing oeHub's auth entirely. Generate one at first run instead and persist it next to
    // the database file, same trust boundary as the file itself.
    private static String loadOrCreateDbPassword(Path dataDir) {
        var pwFile = dataDir.resolve(".h2-password");
        try {
            if (Files.exists(pwFile)) {
                return Files.readString(pwFile, StandardCharsets.UTF_8).trim();
            }
            Files.createDirectories(dataDir);
            var bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            var password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Files.writeString(pwFile, password, StandardCharsets.UTF_8);
            AppHome.restrictToOwner(pwFile);
            return password;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load/create H2 database password", e);
        }
    }

    private static void initSchema(SqlSessionFactory factory) {
        try (var session = factory.openSession(true)) {
            var conn = session.getConnection();

            // HUB_WS must exist before HUB_USR, which FK-references it via ws_no — see the
            // cloudGroupService design doc §2.1 table-creation-order note.
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_WS (
                    ws_no      BIGINT       AUTO_INCREMENT PRIMARY KEY,
                    ws_name    VARCHAR(128) NOT NULL UNIQUE,
                    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
                    created_by BIGINT       NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_USR (
                    user_no       BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1000000000) PRIMARY KEY,
                    user_id       VARCHAR(64) NOT NULL UNIQUE,
                    password      VARCHAR(128) NOT NULL,
                    role          VARCHAR(16) NOT NULL,
                    ws_no         BIGINT NOT NULL,
                    token_version INT NOT NULL DEFAULT 0,
                    public_key                   CLOB NULL,
                    wrapped_private_key          CLOB NULL,
                    wrapped_private_key_recovery CLOB NULL,
                    recovery_verifier VARCHAR(128) NULL,
                    created_by    BIGINT NULL,
                    updated_by    BIGINT NULL,
                    create_at     TIMESTAMP NOT NULL,
                    updated_at    TIMESTAMP NOT NULL,
                    last_login_at TIMESTAMP NULL,
                    CONSTRAINT fk_hub_usr_ws FOREIGN KEY (ws_no) REFERENCES HUB_WS(ws_no)
                )
                """);
            // 1-per-invitee, single-use codes (cloudGroupService design doc §2.8) - group mode
            // only, used to join an existing workspace as role='pending'.
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_WS_INVITE (
                    invite_code VARCHAR(32) NOT NULL PRIMARY KEY,
                    ws_no       BIGINT      NOT NULL,
                    used        BOOLEAN     NOT NULL DEFAULT FALSE,
                    created_by  BIGINT      NOT NULL,
                    updated_by  BIGINT      NULL,
                    create_at   TIMESTAMP   NOT NULL,
                    updated_at  TIMESTAMP   NOT NULL,
                    expires_at  TIMESTAMP   NOT NULL,
                    CONSTRAINT fk_hub_ws_invite_ws FOREIGN KEY (ws_no) REFERENCES HUB_WS(ws_no)
                )
                """);
            // One wrap of the workspace's shared symmetric key per member, each wrapped with that
            // member's own RSA public key (e2eEncryption design doc §3/§4) - must come after both
            // HUB_WS and HUB_USR, which it FK-references.
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_WS_KEY (
                    ws_no           BIGINT    NOT NULL,
                    user_no         BIGINT    NOT NULL,
                    wrapped_ws_key  CLOB      NOT NULL,
                    created_by      BIGINT    NOT NULL,
                    updated_by      BIGINT    NULL,
                    create_at       TIMESTAMP NOT NULL,
                    updated_at      TIMESTAMP NOT NULL,
                    PRIMARY KEY (ws_no, user_no),
                    CONSTRAINT fk_hub_ws_key_ws   FOREIGN KEY (ws_no)   REFERENCES HUB_WS(ws_no),
                    CONSTRAINT fk_hub_ws_key_user FOREIGN KEY (user_no) REFERENCES HUB_USR(user_no)
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_CONF (
                    conf_key   VARCHAR(128) PRIMARY KEY,
                    conf_val   CLOB         NOT NULL,
                    created_by BIGINT       NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_PFILE (
                    hosts_id          VARCHAR(32)    NOT NULL PRIMARY KEY,
                    user_no           BIGINT         NOT NULL,
                    hosts_profile     VARCHAR(128)   NOT NULL,
                    hosts_content     CLOB           NOT NULL,
                    selected          BOOLEAN        NOT NULL DEFAULT FALSE,
                    sort_order        INT            NOT NULL DEFAULT 0,
                    visibility        VARCHAR(16)    NOT NULL DEFAULT 'public',
                    parent_id         VARCHAR(32)    NULL,
                    wrapped_content_key CLOB         NULL,
                    link_content        CLOB         NULL,
                    wrapped_link_key    CLOB         NULL,
                    created_by        BIGINT         NOT NULL,
                    updated_by        BIGINT         NULL,
                    create_at         TIMESTAMP      NOT NULL,
                    updated_at        TIMESTAMP      NOT NULL,
                    CONSTRAINT uq_hosts_pfile_user_profile UNIQUE (user_no, hosts_profile),
                    CONSTRAINT fk_hosts_pfile_parent FOREIGN KEY (parent_id) REFERENCES HOSTS_PFILE(hosts_id)
                )
                """);
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_hosts_pfile_parent ON HOSTS_PFILE(parent_id)");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_hosts_pfile_updated_by ON HOSTS_PFILE(updated_by)");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_CONF (
                    user_no     BIGINT,
                    conf_key    VARCHAR(128)   NOT NULL,
                    conf_val    CLOB,
                    created_by  BIGINT         NOT NULL,
                    updated_by  BIGINT         NULL,
                    create_at   TIMESTAMP      NOT NULL,
                    updated_at  TIMESTAMP      NOT NULL,
                    CONSTRAINT uq_hosts_conf UNIQUE (user_no, conf_key),
                    CONSTRAINT fk_hosts_conf_user FOREIGN KEY (user_no) REFERENCES HUB_USR(user_no)
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_UA (
                    ua_id       VARCHAR(32)  NOT NULL PRIMARY KEY,
                    ua_name     VARCHAR(128) NOT NULL,
                    ua_value    VARCHAR(512) NOT NULL,
                    sort_order  INT          NOT NULL DEFAULT 0,
                    user_no     BIGINT       NULL,
                    created_by  BIGINT       NOT NULL,
                    updated_by  BIGINT       NULL,
                    create_at   TIMESTAMP    NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )
                """);
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_hosts_ua_user_no ON HOSTS_UA(user_no)");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_URL (
                    url_id      VARCHAR(32)  NOT NULL PRIMARY KEY,
                    url_name    VARCHAR(128) NOT NULL,
                    url_value   VARCHAR(512) NOT NULL,
                    sort_order  INT          NOT NULL DEFAULT 0,
                    user_no     BIGINT       NULL,
                    created_by  BIGINT       NOT NULL,
                    updated_by  BIGINT       NULL,
                    create_at   TIMESTAMP    NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )
                """);
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_hosts_url_user_no ON HOSTS_URL(user_no)");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS PROXY_VHOST (
                    vhost_id          VARCHAR(32)    NOT NULL PRIMARY KEY,
                    user_no           BIGINT         NOT NULL,
                    vhost_profile     VARCHAR(128)   NOT NULL,
                    vhost_content     CLOB           NOT NULL,
                    selected          BOOLEAN        NOT NULL DEFAULT FALSE,
                    sort_order        INT            NOT NULL DEFAULT 0,
                    visibility        VARCHAR(16)    NOT NULL DEFAULT 'public',
                    parent_id         VARCHAR(32)    NULL,
                    wrapped_content_key CLOB         NULL,
                    link_content        CLOB         NULL,
                    created_by        BIGINT         NOT NULL,
                    updated_by        BIGINT         NULL,
                    create_at         TIMESTAMP      NOT NULL,
                    updated_at        TIMESTAMP      NOT NULL,
                    CONSTRAINT uq_proxy_vhost_user_profile UNIQUE (user_no, vhost_profile),
                    CONSTRAINT fk_proxy_vhost_parent FOREIGN KEY (parent_id) REFERENCES PROXY_VHOST(vhost_id)
                )
                """);
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_proxy_vhost_parent ON PROXY_VHOST(parent_id)");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_proxy_vhost_updated_by ON PROXY_VHOST(updated_by)");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS PROXY_CONF (
                    user_no     BIGINT,
                    conf_key    VARCHAR(128)   NOT NULL,
                    conf_val    CLOB,
                    created_by  BIGINT         NOT NULL,
                    updated_by  BIGINT         NULL,
                    create_at   TIMESTAMP      NOT NULL,
                    updated_at  TIMESTAMP      NOT NULL,
                    CONSTRAINT uq_proxy_conf UNIQUE (user_no, conf_key),
                    CONSTRAINT fk_proxy_conf_user FOREIGN KEY (user_no) REFERENCES HUB_USR(user_no)
                )
                """);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }
}
