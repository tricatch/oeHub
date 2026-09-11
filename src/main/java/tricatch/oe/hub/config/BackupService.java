package tricatch.oe.hub.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.model.HubConf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules periodic online H2 database backups using H2's native {@code BACKUP TO} SQL command
 * (engine-coordinated, safe to run against a live database - unlike a naive OS-level file copy,
 * which can tear/corrupt the MVStore file under concurrent writes). Alongside each backup zip, a
 * copy of the {@code .h2-password} credential file (see {@link DatabaseConfig#loadOrCreateDbPassword})
 * is also stored, since that file is not part of H2's own backup and a restore that loses it would
 * generate a fresh random password unable to open the restored (differently-credentialed) database.
 */
public class BackupService {

    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);

    public static final String CONF_KEY_INTERVAL_HOURS = "backup.interval.hours";
    public static final String CONF_KEY_LAST_BACKUP_AT = "backup.last.at";
    public static final int DEFAULT_INTERVAL_HOURS = 6;
    public static final int MIN_INTERVAL_HOURS = 1;
    public static final int MAX_INTERVAL_HOURS = 24;
    private static final int RETENTION_DAYS = 14;

    private static final String BACKUP_PREFIX = "oeHub-backup-";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static SqlSessionFactory sqlSessionFactory;
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> scheduledFuture;

    private BackupService() {
    }

    public static synchronized void init(SqlSessionFactory factory) {
        sqlSessionFactory = factory;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "backup-scheduler");
            t.setDaemon(true);
            return t;
        });
        schedule(readIntervalHours());
    }

    /** Safe to call any time after {@link #init}; no-op if called before init (shouldn't happen in prod). */
    public static synchronized void reschedule(int hours) {
        if (scheduler == null) {
            return;
        }
        if (hours < MIN_INTERVAL_HOURS || hours > MAX_INTERVAL_HOURS) {
            hours = DEFAULT_INTERVAL_HOURS;
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        schedule(hours);
    }

    private static void schedule(int hours) {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        // Initial delay == period == the interval, so a fresh install doesn't immediately write a
        // backup file at startup, but a later interval change (reschedule) still reschedules cleanly.
        scheduledFuture = scheduler.scheduleAtFixedRate(
                BackupService::runBackupSafely, hours, hours, TimeUnit.HOURS);
        logger.info("Scheduled H2 database backup every {} hour(s).", hours);
    }

    private static int readIntervalHours() {
        try {
            var conf = withSession(session ->
                    session.getMapper(HubConfMapper.class).findByConfKey(CONF_KEY_INTERVAL_HOURS));
            if (conf == null || conf.getConfVal() == null) {
                return DEFAULT_INTERVAL_HOURS;
            }
            int hours = Integer.parseInt(conf.getConfVal().trim());
            if (hours < MIN_INTERVAL_HOURS || hours > MAX_INTERVAL_HOURS) {
                return DEFAULT_INTERVAL_HOURS;
            }
            return hours;
        } catch (Exception e) {
            logger.warn("Failed to read backup interval from HUB_CONF; using default ({}h). {}",
                    DEFAULT_INTERVAL_HOURS, e.getMessage());
            return DEFAULT_INTERVAL_HOURS;
        }
    }

    /** Public wrapper around {@link #readIntervalHours()} for the settings page model. */
    public static int getIntervalHours() {
        return readIntervalHours();
    }

    /** Raw stored ISO string, or null if never backed up. Display formatting is the caller's job. */
    public static String getLastBackupAtDisplay() {
        try {
            var conf = withSession(session ->
                    session.getMapper(HubConfMapper.class).findByConfKey(CONF_KEY_LAST_BACKUP_AT));
            return conf != null ? conf.getConfVal() : null;
        } catch (Exception e) {
            logger.warn("Failed to read last backup timestamp from HUB_CONF.", e);
            return null;
        }
    }

    private static void runBackupSafely() {
        try {
            performBackup();
        } catch (Exception e) {
            logger.error("Scheduled H2 database backup failed.", e);
        }
    }

    static void performBackup() throws Exception {
        var dataDir = AppHome.oeHubDir().resolve("data");
        var backupDir = dataDir.resolve("backups");
        Files.createDirectories(backupDir);

        var ts = LocalDateTime.now().format(TS_FMT);
        var zipFile = backupDir.resolve(BACKUP_PREFIX + ts + ".zip");
        var pwBackupFile = backupDir.resolve(BACKUP_PREFIX + ts + ".h2-password");

        try (var session = sqlSessionFactory.openSession(true)) {
            var conn = session.getConnection();
            // Server-generated path only (never user input) - escaped defensively anyway.
            var escapedPath = zipFile.toString().replace("'", "''");
            try (var stmt = conn.createStatement()) {
                stmt.execute("BACKUP TO '" + escapedPath + "'");
            }
        }
        AppHome.restrictToOwner(zipFile);

        var pwFile = dataDir.resolve(".h2-password");
        if (Files.exists(pwFile)) {
            Files.copy(pwFile, pwBackupFile, StandardCopyOption.REPLACE_EXISTING);
            AppHome.restrictToOwner(pwBackupFile);
        }

        try {
            var now = LocalDateTime.now();
            var conf = new HubConf();
            conf.setConfKey(CONF_KEY_LAST_BACKUP_AT);
            conf.setConfVal(now.toString());
            conf.setCreatedBy(0L);
            conf.setUpdatedBy(0L);
            conf.setCreateAt(now);
            conf.setUpdatedAt(now);
            try (var session = sqlSessionFactory.openSession(true)) {
                session.getMapper(HubConfMapper.class).upsert(conf);
            }
        } catch (Exception e) {
            logger.warn("Backup succeeded but failed to record last-backup timestamp in HUB_CONF.", e);
        }

        cleanupOldBackups(backupDir);

        logger.info("H2 database backup completed: {}", zipFile);
    }

    private static void cleanupOldBackups(Path backupDir) {
        var cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        try (var files = Files.list(backupDir)) {
            files
                .filter(p -> p.getFileName().toString().startsWith(BACKUP_PREFIX))
                .filter(p -> p.getFileName().toString().endsWith(".zip"))
                .forEach(zip -> {
                    try {
                        if (Files.getLastModifiedTime(zip).toInstant().isBefore(cutoff)) {
                            Files.deleteIfExists(zip);
                            var pw = backupDir.resolve(
                                    stripExtension(zip.getFileName().toString()) + ".h2-password");
                            Files.deleteIfExists(pw);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to clean up old backup file {}", zip, e);
                    }
                });
        } catch (Exception e) {
            logger.warn("Failed to list backup directory {} for retention cleanup.", backupDir, e);
        }
    }

    private static String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(0, idx) : fileName;
    }

    private interface SessionFn<T> {
        T apply(org.apache.ibatis.session.SqlSession session);
    }

    private static <T> T withSession(SessionFn<T> fn) {
        try (var session = sqlSessionFactory.openSession()) {
            return fn.apply(session);
        }
    }
}
