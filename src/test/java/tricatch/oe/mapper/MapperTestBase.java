package tricatch.oe.mapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hub.model.HubUser;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.mapper.HostsProfMapper;
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
        cfg.addMapper(HubConfMapper.class);
        cfg.addMapper(HostsProfMapper.class);
        cfg.addMapper(HostsConfMapper.class);
        cfg.addMapper(ProxyVhostMapper.class);
        cfg.addMapper(ProxyConfMapper.class);

        FACTORY = new SqlSessionFactoryBuilder().build(cfg);
        initSchema();

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
                CREATE TABLE IF NOT EXISTS HUB_USR (
                    user_no       BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id       VARCHAR(64)  NOT NULL UNIQUE,
                    password      VARCHAR(128) NOT NULL,
                    role          VARCHAR(16)  NOT NULL,
                    create_at     TIMESTAMP    NOT NULL,
                    updated_at    TIMESTAMP    NOT NULL,
                    last_login_at TIMESTAMP    NULL
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_CONF (
                    conf_key   VARCHAR(128) PRIMARY KEY,
                    conf_val   CLOB         NOT NULL,
                    updated_at TIMESTAMP    NOT NULL
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
                    updated_at    TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_hosts_pfile_user_profile UNIQUE (user_no, hosts_profile),
                    CONSTRAINT fk_hosts_pfile_parent FOREIGN KEY (parent_id) REFERENCES HOSTS_PFILE(hosts_id)
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_CONF (
                    user_no    BIGINT,
                    conf_key   VARCHAR(128) NOT NULL,
                    conf_val   CLOB,
                    updated_at TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_hosts_conf UNIQUE (user_no, conf_key),
                    CONSTRAINT fk_hosts_conf_user FOREIGN KEY (user_no) REFERENCES HUB_USR(user_no)
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
                    updated_at    TIMESTAMP    NOT NULL,
                    CONSTRAINT uq_proxy_vhost_user_profile UNIQUE (user_no, vhost_profile),
                    CONSTRAINT fk_proxy_vhost_parent FOREIGN KEY (parent_id) REFERENCES PROXY_VHOST(vhost_id)
                )""");
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS PROXY_CONF (
                    user_no    BIGINT,
                    conf_key   VARCHAR(128) NOT NULL,
                    conf_val   CLOB,
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
                conn.createStatement().execute("DELETE FROM HUB_CONF");
                conn.createStatement().execute("DELETE FROM HUB_USR");
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
        user.setCreateAt(now);
        user.setUpdatedAt(now);
        try (var session = FACTORY.openSession(true)) {
            session.getMapper(HubUserMapper.class).insert(user);
        }
        return user;
    }

    protected String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
