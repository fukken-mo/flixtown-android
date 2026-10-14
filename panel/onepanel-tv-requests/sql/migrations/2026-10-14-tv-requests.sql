-- Flix Town TV media requests (customer side). Run once; nothing existing changes.
CREATE TABLE IF NOT EXISTS tv_media_requests (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(120) NOT NULL,
  seerr_request_id INT UNSIGNED NOT NULL,
  media_type VARCHAR(8) NOT NULL,
  tmdb_id INT UNSIGNED NOT NULL,
  seasons VARCHAR(255) NOT NULL DEFAULT '',
  title VARCHAR(200) NOT NULL DEFAULT '',
  year VARCHAR(4) NOT NULL DEFAULT '',
  poster VARCHAR(255) NOT NULL DEFAULT '',
  created_ts INT UNSIGNED NOT NULL,
  last_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  last_media_status TINYINT UNSIGNED NOT NULL DEFAULT 1,
  last_checked_ts INT UNSIGNED NOT NULL DEFAULT 0,
  UNIQUE KEY uq_tv_media_requests_seerr (seerr_request_id),
  INDEX idx_tv_media_requests_user (username, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tv_request_rate (
  k VARCHAR(120) NOT NULL PRIMARY KEY,
  window_start INT UNSIGNED NOT NULL,
  hits INT UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
