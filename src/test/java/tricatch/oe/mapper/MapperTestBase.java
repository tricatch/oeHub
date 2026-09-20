package tricatch.oe.mapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import tricatch.oe.hub.config.PasswordUtil;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.mapper.TeamMapper;
import tricatch.oe.hub.mapper.WorkspaceMapper;
import tricatch.oe.hub.mapper.WsInviteMapper;
import tricatch.oe.hub.mapper.WsKeyMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.hub.model.Workspace;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.mapper.HostsProfMapper;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.proxy.mapper.ProxyConfMapper;
import tricatch.oe.proxy.mapper.ProxyVhostMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;

public abstract class MapperTestBase {

    protected static final SqlSessionFactory FACTORY;
    protected static Long TEST_WS_NO;
    private static final Path DB_DIR = Path.of("build", "test-db");

    static {
        try {
            Files.createDirectories(DB_DIR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var dbPath = DB_DIR.resolve("oehub-test").toAbsolutePath();
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + dbPath);
        ds.setUser("sa");
        ds.setPassword("oeHub");

        var env = new Environment("test", new JdbcTransactionFactory(), ds);
        var cfg = new Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(HubUserMapper.class);
        cfg.addMapper(TeamMapper.class);
        cfg.addMapper(WorkspaceMapper.class);
        cfg.addMapper(WsInviteMapper.class);
        cfg.addMapper(WsKeyMapper.class);
        cfg.addMapper(HubConfMapper.class);
        cfg.addMapper(HostsProfMapper.class);
        cfg.addMapper(HostsConfMapper.class);
        cfg.addMapper(HostsUaMapper.class);
        cfg.addMapper(HostsUrlMapper.class);
        cfg.addMapper(ProxyVhostMapper.class);
        cfg.addMapper(ProxyConfMapper.class);
        cfg.addMapper(tricatch.oe.hub.mapper.HubApiTokenMapper.class);
        cfg.addMapper(tricatch.oe.hub.mapper.HubAuditLogMapper.class);

        FACTORY = new SqlSessionFactoryBuilder().build(cfg);
        initSchema();

        // One shared workspace for the whole test run - not deleted in clearTables() (only
        // HUB_USR and its dependents are), so every test's insertUser() can reference it without
        // recreating a workspace per test.
        try (var session = FACTORY.openSession(true)) {
            var workspace = new Workspace();
            workspace.setWsName("Test Workspace");
            workspace.setStatus("active");
            var now = LocalDateTime.now();
            workspace.setCreateAt(now);
            workspace.setUpdatedAt(now);
            session.getMapper(WorkspaceMapper.class).insert(workspace);
            TEST_WS_NO = workspace.getWsNo();
        }

        // Mirrors OeHubApplication.main()'s boot order: ReverseProxyServer.init() loads/creates
        // the OidUtil HMAC secret before anything can call setVirtualHosts()/getVirtualHosts()
        // (see ProxyController), which OidUtil.encode/decode now require.
        tricatch.oe.proxy.ReverseProxyServer.init(FACTORY);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.walk(DB_DIR)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {}
        }));
    }

    private static void initSchema() {
        try (var session = FACTORY.openSession(true)) {
            var conn = session.getConnection();
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_WS (
                    ws_no      BIGINT       AUTO_INCREMENT PRIMARY KEY,
                    ws_name    VARCHAR(128) NOT NULL UNIQUE,
                    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
                    created_by BIGINT       NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_TEAM (
                    team_no    BIGINT       AUTO_INCREMENT PRIMARY KEY,
                    ws_no      BIGINT       NOT NULL,
                    team_name  VARCHAR(128) NOT NULL,
                    created_by BIGINT       NOT NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_hub_team_ws_name UNIQUE (ws_no, team_name),
                    CONSTRAINT fk_hub_team_ws FOREIGN KEY (ws_no) REFERENCES HUB_WS(ws_no)
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_USR (
                    user_no       BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1000000000) PRIMARY KEY,
                    user_id       VARCHAR(64)  NOT NULL UNIQUE,
                    password      VARCHAR(128) NOT NULL,
                    role          VARCHAR(3)   NOT NULL,
                    ws_no         BIGINT       NOT NULL,
                    team_no       BIGINT       NULL,
                    token_version INT          NOT NULL DEFAULT 0,
                    public_key                   CLOB NULL,
                    wrapped_private_key          CLOB NULL,
                    wrapped_private_key_recovery CLOB NULL,
                    recovery_verifier VARCHAR(128) NULL,
                    created_by    BIGINT       NULL,
                    updated_by    BIGINT       NULL,
                    create_at     TIMESTAMP    NOT NULL,
                    updated_at    TIMESTAMP    NOT NULL,
                    last_login_at TIMESTAMP    NULL,
                    CONSTRAINT fk_hub_usr_ws FOREIGN KEY (ws_no) REFERENCES HUB_WS(ws_no),
                    CONSTRAINT fk_hub_usr_team FOREIGN KEY (team_no) REFERENCES HUB_TEAM(team_no)
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_WS_INVITE (
                    invite_code VARCHAR(32) NOT NULL PRIMARY KEY,
                    ws_no       BIGINT      NOT NULL,
                    team_no     BIGINT      NULL,
                    used        BOOLEAN     NOT NULL DEFAULT FALSE,
                    created_by  BIGINT      NOT NULL,
                    updated_by  BIGINT      NULL,
                    create_at   TIMESTAMP   NOT NULL,
                    updated_at  TIMESTAMP   NOT NULL,
                    expires_at  TIMESTAMP   NOT NULL,
                    CONSTRAINT fk_hub_ws_invite_ws FOREIGN KEY (ws_no) REFERENCES HUB_WS(ws_no),
                    CONSTRAINT fk_hub_ws_invite_team FOREIGN KEY (team_no) REFERENCES HUB_TEAM(team_no)
                )""");
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
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_CONF (
                    conf_key   VARCHAR(128) PRIMARY KEY,
                    conf_val   CLOB         NOT NULL,
                    created_by BIGINT       NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_API_TOKEN (
                    token_id     VARCHAR(32)  NOT NULL PRIMARY KEY,
                    user_no      BIGINT       NOT NULL,
                    token_name   VARCHAR(128) NOT NULL,
                    token_hash   VARCHAR(128) NOT NULL UNIQUE,
                    created_by   BIGINT       NOT NULL,
                    updated_by   BIGINT       NULL,
                    create_at    TIMESTAMP    NOT NULL,
                    updated_at   TIMESTAMP    NOT NULL,
                    last_used_at TIMESTAMP    NULL,
                    expires_at   TIMESTAMP    NULL
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_AUDIT_LOG (
                    audit_id    BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    ws_no       BIGINT       NOT NULL,
                    action      VARCHAR(64)  NOT NULL,
                    target_type VARCHAR(32)  NULL,
                    target_id   VARCHAR(64)  NULL,
                    detail      CLOB         NULL,
                    created_by  BIGINT       NOT NULL,
                    updated_by  BIGINT       NULL,
                    create_at   TIMESTAMP    NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_PFILE (
                    hosts_id      VARCHAR(32)  NOT NULL PRIMARY KEY,
                    user_no       BIGINT       NOT NULL,
                    hosts_profile VARCHAR(128) NOT NULL,
                    hosts_content CLOB         NOT NULL,
                    selected      BOOLEAN      NOT NULL DEFAULT FALSE,
                    sort_order    INT          NOT NULL DEFAULT 0,
                    visibility    VARCHAR(16)  NOT NULL DEFAULT 'public',
                    parent_id     VARCHAR(32)  NULL,
                    wrapped_content_key CLOB   NULL,
                    link_content        CLOB   NULL,
                    wrapped_link_key    CLOB   NULL,
                    created_by    BIGINT       NOT NULL,
                    updated_by    BIGINT       NULL,
                    create_at     TIMESTAMP    NOT NULL,
                    updated_at    TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_hosts_pfile_user_profile UNIQUE (user_no, hosts_profile),
                    CONSTRAINT fk_hosts_pfile_parent FOREIGN KEY (parent_id) REFERENCES HOSTS_PFILE(hosts_id)
                )""");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_hosts_pfile_parent ON HOSTS_PFILE(parent_id)");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_hosts_pfile_updated_by ON HOSTS_PFILE(updated_by)");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_CONF (
                    user_no    BIGINT,
                    conf_key   VARCHAR(128) NOT NULL,
                    conf_val   CLOB,
                    created_by BIGINT       NOT NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_hosts_conf UNIQUE (user_no, conf_key),
                    CONSTRAINT fk_hosts_conf_user FOREIGN KEY (user_no) REFERENCES HUB_USR(user_no)
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_UA (
                    ua_id      VARCHAR(32)  NOT NULL PRIMARY KEY,
                    ua_name    VARCHAR(128) NOT NULL,
                    ua_value   VARCHAR(512) NOT NULL,
                    sort_order INT          NOT NULL DEFAULT 0,
                    user_no    BIGINT       NULL,
                    created_by BIGINT       NOT NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_URL (
                    url_id     VARCHAR(32)  NOT NULL PRIMARY KEY,
                    url_name   VARCHAR(128) NOT NULL,
                    url_value  VARCHAR(512) NOT NULL,
                    sort_order INT          NOT NULL DEFAULT 0,
                    user_no    BIGINT       NULL,
                    created_by BIGINT       NOT NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS PROXY_VHOST (
                    vhost_id      VARCHAR(32)  NOT NULL PRIMARY KEY,
                    user_no       BIGINT       NOT NULL,
                    vhost_profile VARCHAR(128) NOT NULL,
                    vhost_content CLOB         NOT NULL,
                    selected      BOOLEAN      NOT NULL DEFAULT FALSE,
                    sort_order    INT          NOT NULL DEFAULT 0,
                    visibility    VARCHAR(16)  NOT NULL DEFAULT 'public',
                    parent_id     VARCHAR(32)  NULL,
                    wrapped_content_key CLOB   NULL,
                    link_content        CLOB   NULL,
                    created_by    BIGINT       NOT NULL,
                    updated_by    BIGINT       NULL,
                    create_at     TIMESTAMP    NOT NULL,
                    updated_at    TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_proxy_vhost_user_profile UNIQUE (user_no, vhost_profile),
                    CONSTRAINT fk_proxy_vhost_parent FOREIGN KEY (parent_id) REFERENCES PROXY_VHOST(vhost_id)
                )""");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_proxy_vhost_parent ON PROXY_VHOST(parent_id)");
            conn.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_proxy_vhost_updated_by ON PROXY_VHOST(updated_by)");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS PROXY_CONF (
                    user_no    BIGINT,
                    conf_key   VARCHAR(128) NOT NULL,
                    conf_val   CLOB,
                    created_by BIGINT       NOT NULL,
                    updated_by BIGINT       NULL,
                    create_at  TIMESTAMP    NOT NULL,
                    updated_at TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_proxy_conf UNIQUE (user_no, conf_key),
                    CONSTRAINT fk_proxy_conf_user FOREIGN KEY (user_no) REFERENCES HUB_USR(user_no)
                )""");
        } catch (SQLException e) {
            throw new RuntimeException("Schema init failed", e);
        }
    }

    @BeforeEach
    void clearTables() throws SQLException {
        try (var session = FACTORY.openSession(true)) {
            var conn = session.getConnection();
            conn.createStatement().execute("SET REFERENTIAL_INTEGRITY FALSE");
            try {
                conn.createStatement().execute("DELETE FROM PROXY_CONF");
                conn.createStatement().execute("DELETE FROM HOSTS_CONF");
                conn.createStatement().execute("DELETE FROM PROXY_VHOST");
                conn.createStatement().execute("DELETE FROM HOSTS_PFILE");
                conn.createStatement().execute("DELETE FROM HOSTS_UA");
                conn.createStatement().execute("DELETE FROM HOSTS_URL");
                conn.createStatement().execute("DELETE FROM HUB_CONF");
                conn.createStatement().execute("DELETE FROM HUB_API_TOKEN");
                conn.createStatement().execute("DELETE FROM HUB_AUDIT_LOG");
                conn.createStatement().execute("DELETE FROM HUB_WS_KEY");
                conn.createStatement().execute("DELETE FROM HUB_USR");
                conn.createStatement().execute("DELETE FROM HUB_TEAM");
            } finally {
                conn.createStatement().execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
        }
    }

    protected HubUser insertUser(String userId) {
        var now = LocalDateTime.now();
        var user = new HubUser();
        user.setUserId(userId);
        user.setPassword("hashed");
        user.setRole("usr");
        user.setWsNo(TEST_WS_NO);
        user.setCreateAt(now);
        user.setUpdatedAt(now);
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.insert(user);
            mapper.selfReferenceAudit(user.getUserNo());
            user.setCreatedBy(user.getUserNo());
            user.setUpdatedBy(user.getUserNo());
        }
        return user;
    }

    /** The workspace's non-login system account (role wss) that inherits orphaned 'public' rows. */
    protected HubUser insertWsSystem(Long wsNo) {
        var now = LocalDateTime.now();
        var wsSystem = new HubUser();
        wsSystem.setUserId("__wss_" + wsNo + "_" + newId().substring(0, 6));
        wsSystem.setPassword(PasswordUtil.hash(newId()));
        wsSystem.setRole("wss");
        wsSystem.setWsNo(wsNo);
        wsSystem.setCreateAt(now);
        wsSystem.setUpdatedAt(now);
        try (var session = FACTORY.openSession(true)) {
            var mapper = session.getMapper(HubUserMapper.class);
            mapper.insert(wsSystem);
            mapper.selfReferenceAudit(wsSystem.getUserNo());
        }
        return wsSystem;
    }

    protected String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
