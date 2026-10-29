package tricatch.oe.hub.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import tricatch.oe.hub.mapper.HubConfMapper;
import tricatch.oe.hub.model.HubConf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises BackupService against a real, isolated {@code -Doe.home} directory so
 * DatabaseConfig.buildSqlSessionFactory() runs its normal schema init and creates a real
 * .h2-password file, the same way a live install would.
 */
class BackupServiceTest {

    private String originalHome;

    @BeforeEach
    void saveHomeProperty() {
        originalHome = System.getProperty("oe.home");
    }

    @AfterEach
    void restoreHomeProperty() {
        if (originalHome != null) {
            System.setProperty("oe.home", originalHome);
        } else {
            System.clearProperty("oe.home");
        }
    }

    @Test
    void performBackup_writesZipAndPasswordCopy_andRecordsLastBackupTimestamp(@TempDir Path homeDir) throws Exception {
        System.setProperty("oe.home", homeDir.toString());
        var factory = DatabaseConfig.buildSqlSessionFactory();
        BackupService.init(factory);

        var originalPasswordContent = Files.readString(homeDir.resolve("data").resolve(".h2-password"),
                StandardCharsets.UTF_8);

        BackupService.performBackup();

        var backupDir = homeDir.resolve("data").resolve("backups");
        assertThat(Files.isDirectory(backupDir)).isTrue();

        List<Path> zips;
        try (var files = Files.list(backupDir)) {
            zips = files.filter(p -> p.getFileName().toString().matches("oeHub-backup-.*\\.zip"))
                    .collect(Collectors.toList());
        }
        assertThat(zips).hasSize(1);
        var zipFile = zips.get(0);
        assertThat(Files.size(zipFile)).isGreaterThan(0);
        try (var zf = new ZipFile(zipFile.toFile())) {
            assertThat(zf.entries().hasMoreElements()).isTrue();
        }

        var baseName = zipFile.getFileName().toString().replace(".zip", "");
        var pwBackup = backupDir.resolve(baseName + ".h2-password");
        assertThat(Files.exists(pwBackup)).isTrue();
        assertThat(Files.readString(pwBackup, StandardCharsets.UTF_8)).isEqualTo(originalPasswordContent);

        try (var session = factory.openSession()) {
            var conf = session.getMapper(HubConfMapper.class).findByConfKey(BackupService.CONF_KEY_LAST_BACKUP_AT);
            assertThat(conf).isNotNull();
            assertThat(conf.getConfVal()).isNotBlank();
            // Must parse cleanly as the LocalDateTime.now().toString() format performBackup writes.
            LocalDateTime.parse(conf.getConfVal());
        }
    }

    @Test
    void getIntervalHours_outOfRangeStoredValue_fallsBackToDefault(@TempDir Path homeDir) {
        System.setProperty("oe.home", homeDir.toString());
        var factory = DatabaseConfig.buildSqlSessionFactory();
        BackupService.init(factory);

        writeIntervalConf(factory, "99");
        assertThat(BackupService.getIntervalHours()).isEqualTo(BackupService.DEFAULT_INTERVAL_HOURS);

        writeIntervalConf(factory, "not-a-number");
        assertThat(BackupService.getIntervalHours()).isEqualTo(BackupService.DEFAULT_INTERVAL_HOURS);
    }

    @Test
    void getIntervalHours_validStoredValue_isHonored(@TempDir Path homeDir) {
        System.setProperty("oe.home", homeDir.toString());
        var factory = DatabaseConfig.buildSqlSessionFactory();
        BackupService.init(factory);

        writeIntervalConf(factory, "3");
        assertThat(BackupService.getIntervalHours()).isEqualTo(3);
    }

    @Test
    void reschedule_clampsOutOfRangeValues_withoutThrowing(@TempDir Path homeDir) {
        System.setProperty("oe.home", homeDir.toString());
        var factory = DatabaseConfig.buildSqlSessionFactory();
        BackupService.init(factory);

        BackupService.reschedule(0);
        BackupService.reschedule(100);
        BackupService.reschedule(-5);
        BackupService.reschedule(12);
        // No exception thrown is the assertion here - reschedule() only drives the scheduler,
        // it does not persist to HUB_CONF (SettingsController does that separately).
    }

    private void writeIntervalConf(org.apache.ibatis.session.SqlSessionFactory factory, String value) {
        try (var session = factory.openSession(true)) {
            var now = LocalDateTime.now();
            var conf = new HubConf();
            conf.setConfKey(BackupService.CONF_KEY_INTERVAL_HOURS);
            conf.setConfVal(value);
            conf.setCreatedBy(0L);
            conf.setUpdatedBy(0L);
            conf.setCreateAt(now);
            conf.setUpdatedAt(now);
            session.getMapper(HubConfMapper.class).upsert(conf);
        }
    }
}
