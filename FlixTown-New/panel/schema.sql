CREATE TABLE IF NOT EXISTS settings (
  name VARCHAR(80) PRIMARY KEY,
  value TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pairings (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code CHAR(8) NOT NULL UNIQUE,
  device_hash CHAR(64) NOT NULL,
  created_ip_hash CHAR(64) NOT NULL,
  attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
  status ENUM('pending','approved','redeemed') NOT NULL DEFAULT 'pending',
  credentials MEDIUMTEXT NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  INDEX (expires_at),
  INDEX (created_ip_hash,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS renewal_requests (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(128) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  plan ENUM('1m','3m','6m','12m') NOT NULL,
  status ENUM('pending','handled') NOT NULL DEFAULT 'pending',
  ip_hash CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  handled_at DATETIME NULL,
  INDEX (username,created_at),
  INDEX (status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tmdb_cache (
  cache_key CHAR(64) PRIMARY KEY,
  payload MEDIUMTEXT NOT NULL,
  expires_at DATETIME NOT NULL,
  INDEX (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO settings (name,value) VALUES
('app_name','Flix Town'),
('xtream_url','http://streamtown.live:8080'),
('intro_enabled','0'),
('intro_url',''),
('logo_url',''),
('tmdb_key',''),
('cashapp_url','https://cash.app/$streamtownofficial'),
('price_1m','15.00'),
('price_3m','40.00'),
('price_6m','75.00'),
('price_12m','130.00'),
('announcement',''),
('update_version_code','0'),
('update_apk_url',''),
('update_notes',''),
('update_required','0'),
('maintenance','0');
