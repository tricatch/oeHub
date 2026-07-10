package tricatch.oe.hub.config;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.mapper.HubUserMapper;
import tricatch.oe.hosts.mapper.HostsConfMapper;
import tricatch.oe.hosts.mapper.HostsProfMapper;
import tricatch.oe.hosts.mapper.HostsUaMapper;
import tricatch.oe.hosts.mapper.HostsUrlMapper;
import tricatch.oe.proxy.mapper.ProxyConfMapper;
import tricatch.oe.proxy.mapper.ProxyVhostMapper;

import java.nio.file.Path;

public class DatabaseConfig {

    public static SqlSessionFactory buildSqlSessionFactory() {
        var dbPath = AppHome.oeHubDir().resolve("data").resolve("oeHub-h2");

        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + dbPath + ";AUTO_SERVER=TRUE");
        ds.setUser("sa");
        ds.setPassword("oeHub");

        var env = new Environment("default", new JdbcTransactionFactory(), ds);
        var config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(HubUserMapper.class);
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

    private static void initSchema(SqlSessionFactory factory) {
        try (var session = factory.openSession(true)) {
            var conn = session.getConnection();

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_USR (
                    user_no       BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id       VARCHAR(64) NOT NULL UNIQUE,
                    password      VARCHAR(128) NOT NULL,
                    role          VARCHAR(16) NOT NULL,
                    create_at     TIMESTAMP NOT NULL,
                    updated_at    TIMESTAMP NOT NULL,
                    last_login_at TIMESTAMP NULL
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HUB_CONF (
                    conf_key   VARCHAR(128) PRIMARY KEY,
                    conf_val   CLOB         NOT NULL,
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
                    updated_at        TIMESTAMP      NOT NULL,
                    CONSTRAINT uq_hosts_pfile_user_profile UNIQUE (user_no, hosts_profile),
                    CONSTRAINT fk_hosts_pfile_parent FOREIGN KEY (parent_id) REFERENCES HOSTS_PFILE(hosts_id)
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_CONF (
                    user_no     BIGINT,
                    conf_key    VARCHAR(128)   NOT NULL,
                    conf_val    CLOB,
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
                    create_at   TIMESTAMP    NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS HOSTS_URL (
                    url_id      VARCHAR(32)  NOT NULL PRIMARY KEY,
                    url_name    VARCHAR(128) NOT NULL,
                    url_value   VARCHAR(512) NOT NULL,
                    sort_order  INT          NOT NULL DEFAULT 0,
                    user_no     BIGINT       NULL,
                    create_at   TIMESTAMP    NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )
                """);
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
                    updated_at        TIMESTAMP      NOT NULL,
                    CONSTRAINT uq_proxy_vhost_user_profile UNIQUE (user_no, vhost_profile),
                    CONSTRAINT fk_proxy_vhost_parent FOREIGN KEY (parent_id) REFERENCES PROXY_VHOST(vhost_id)
                )
                """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS PROXY_CONF (
                    user_no     BIGINT,
                    conf_key    VARCHAR(128)   NOT NULL,
                    conf_val    CLOB,
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
