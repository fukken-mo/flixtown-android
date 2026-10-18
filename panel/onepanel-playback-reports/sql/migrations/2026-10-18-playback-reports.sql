-- Additive only. Run once on the existing OnePanel MySQL/MariaDB database. Safe to re-run.
CREATE TABLE IF NOT EXISTS tv_playback_reports (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_key CHAR(64) CHARACTER SET ascii NOT NULL,
 dedupe_key CHAR(64) CHARACTER SET ascii NOT NULL,
 username VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 media_type VARCHAR(10) NOT NULL,
 media_id INT UNSIGNED NOT NULL,
 episode_id INT UNSIGNED NOT NULL DEFAULT 0,
 title VARCHAR(255) NOT NULL,
 episode_title VARCHAR(255) NOT NULL DEFAULT '',
 season_number INT UNSIGNED NOT NULL DEFAULT 0,
 episode_number INT UNSIGNED NOT NULL DEFAULT 0,
 issue VARCHAR(24) NOT NULL,
 position_ms BIGINT UNSIGNED NOT NULL,
 version_code INT UNSIGNED NOT NULL,
 version_name VARCHAR(40) NOT NULL DEFAULT '',
 android_api INT UNSIGNED NOT NULL,
 device VARCHAR(120) NOT NULL DEFAULT '',
 video_format VARCHAR(80) NOT NULL DEFAULT '',
 audio_format VARCHAR(80) NOT NULL DEFAULT '',
 error_code VARCHAR(80) NOT NULL DEFAULT '',
 status VARCHAR(20) NOT NULL DEFAULT 'new',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NULL,
 UNIQUE KEY tvr_request(request_key),
 KEY tvr_duplicate(dedupe_key,created_at),
 KEY tvr_status(status,created_at),
 KEY tvr_media(media_type,media_id,episode_id,issue)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tv_playback_report_rate (
 k CHAR(64) CHARACTER SET ascii NOT NULL PRIMARY KEY,
 window_start BIGINT NOT NULL,
 hits BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
