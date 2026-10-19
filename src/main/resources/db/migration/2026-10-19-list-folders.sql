-- One-level folders for the oeHosts / oeProxy sidebar lists.
-- Brings a database created before this change up to the DDL in DatabaseConfig: HOSTS_FOLDER,
-- PROXY_FOLDER, and folder_id (+ FK, index) on HOSTS_PFILE / PROXY_VHOST. Existing rows get
-- folder_id = NULL (root); no data changes.
-- Idempotent: every statement is IF NOT EXISTS, so running it again (or on a database that the
-- new build already created) changes nothing. Keep it in step with DatabaseConfig until deployed.
-- Run with the app stopped: java -Doe.migrate=2026-10-19-list-folders -jar oeHub.jar

CREATE TABLE IF NOT EXISTS HOSTS_FOLDER (
    folder_id   VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_no     BIGINT       NOT NULL,
    folder_name VARCHAR(128) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    collapsed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  BIGINT       NOT NULL,
    updated_by  BIGINT       NULL,
    create_at   TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_hosts_folder_user_name UNIQUE (user_no, folder_name)
);
CREATE TABLE IF NOT EXISTS PROXY_FOLDER (
    folder_id   VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_no     BIGINT       NOT NULL,
    folder_name VARCHAR(128) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    collapsed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  BIGINT       NOT NULL,
    updated_by  BIGINT       NULL,
    create_at   TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_proxy_folder_user_name UNIQUE (user_no, folder_name)
);

-- BEFORE created_by keeps the column order identical to a freshly created database.
ALTER TABLE HOSTS_PFILE ADD COLUMN IF NOT EXISTS folder_id VARCHAR(32) NULL BEFORE created_by;
ALTER TABLE PROXY_VHOST ADD COLUMN IF NOT EXISTS folder_id VARCHAR(32) NULL BEFORE created_by;

ALTER TABLE HOSTS_PFILE ADD CONSTRAINT IF NOT EXISTS fk_hosts_pfile_folder
    FOREIGN KEY (folder_id) REFERENCES HOSTS_FOLDER(folder_id) ON DELETE SET NULL;
ALTER TABLE PROXY_VHOST ADD CONSTRAINT IF NOT EXISTS fk_proxy_vhost_folder
    FOREIGN KEY (folder_id) REFERENCES PROXY_FOLDER(folder_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_hosts_pfile_folder ON HOSTS_PFILE(folder_id);
CREATE INDEX IF NOT EXISTS idx_proxy_vhost_folder ON PROXY_VHOST(folder_id);
