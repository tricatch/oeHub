package tricatch.oe.hub.config;

import org.h2.api.ErrorCode;
import org.h2.tools.RunScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * {@code -Doe.migrate=<name>}: applies one bundled schema migration
 * ({@code src/main/resources/db/migration/<name>.sql}) to an EXISTING database and exits, instead
 * of starting the app. Needed when a build changes a table that a deployed database already has -
 * DatabaseConfig only ever runs {@code CREATE ... IF NOT EXISTS}, which never touches an existing
 * table. Scripts must be idempotent (IF NOT EXISTS everywhere), so running one twice is harmless.
 *
 * <p>Takes an H2 {@code BACKUP} first (data/backups/oeHub-premigrate-*.zip). Connects without
 * AUTO_SERVER, so it fails fast with "database in use" while the app is still running instead of
 * changing the schema underneath it.
 */
public final class DbMigration {

    private static final Logger logger = LoggerFactory.getLogger(DbMigration.class);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DbMigration() {}

    /** @return the process exit code (0 = applied) */
    public static int run(String name) {
        if (!NAME.matcher(name).matches()) {
            logger.error("Invalid migration name '{}'.", name);
            return 1;
        }
        var resource = "/db/migration/" + name + ".sql";
        String script;
        try (var in = DbMigration.class.getResourceAsStream(resource)) {
            if (in == null) {
                logger.error("Unknown migration '{}': {} is not in this build.", name, resource);
                return 1;
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Failed to read migration {}.", resource, e);
            return 1;
        }

        var dbPath = AppHome.dbFilePath().toAbsolutePath();
        var dbFile = Path.of(dbPath + ".mv.db");
        if (!Files.exists(dbFile)) {
            logger.error("No database at {} (oe.mode={}) - nothing to migrate.", dbFile, System.getProperty("oe.mode", "self-hosted"));
            return 1;
        }

        var url = "jdbc:h2:file:" + dbPath.toString().replace("\\", "/");
        try (var conn = DriverManager.getConnection(url, "sa", DatabaseConfig.loadOrCreateDbPassword(AppHome.dbDataDir()))) {
            var backup = backup(conn, name);
            logger.info("Pre-migration backup: {}", backup);
            RunScript.execute(conn, new StringReader(script));
            logger.info("Migration '{}' applied to {}.", name, dbFile);
            return 0;
        } catch (SQLException e) {
            if (e.getErrorCode() == ErrorCode.DATABASE_ALREADY_OPEN_1) {
                logger.error("The database is in use - stop the running oeHub first, then run the migration again.");
            } else {
                logger.error("Migration '{}' failed.", name, e);
            }
            return 1;
        } catch (IOException e) {
            logger.error("Migration '{}' failed.", name, e);
            return 1;
        }
    }

    private static Path backup(Connection conn, String name) throws SQLException, IOException {
        var backupDir = AppHome.dbDataDir().resolve("backups");
        Files.createDirectories(backupDir);
        var zipFile = backupDir.resolve("oeHub-premigrate-" + name + "-" + LocalDateTime.now().format(TS_FMT) + ".zip");
        try (var stmt = conn.createStatement()) {
            // Server-generated path only (never user input) - escaped defensively anyway.
            stmt.execute("BACKUP TO '" + zipFile.toString().replace("'", "''") + "'");
        }
        AppHome.restrictToOwner(zipFile);
        return zipFile;
    }
}
