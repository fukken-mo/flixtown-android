CREATE TABLE IF NOT EXISTS tv_sync_accounts (
 account_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tv_sync_records (
 account_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 record_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT UNSIGNED NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload TEXT NOT NULL,
 PRIMARY KEY(account_id,record_key),
 KEY changes(account_id,revision)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tv_sync_rate (
 k CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 window_start BIGINT NOT NULL,
 hits INT UNSIGNED NOT NULL
) ENGINE=InnoDB;
