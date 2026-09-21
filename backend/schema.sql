-- Flix Town control/activation backend schema.
-- Run once against a fresh MySQL/MariaDB database (utf8mb4).

CREATE TABLE IF NOT EXISTS app_settings (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    app_name VARCHAR(100) NOT NULL DEFAULT 'Flix Town',
    xtream_base_url VARCHAR(255) NOT NULL,
    maintenance_mode TINYINT(1) NOT NULL DEFAULT 0,
    maintenance_message VARCHAR(500) NULL,
    logo_url VARCHAR(500) NULL,
    intro_enabled TINYINT(1) NOT NULL DEFAULT 0,
    intro_video_url VARCHAR(500) NULL,
    min_app_version_code INT UNSIGNED NOT NULL DEFAULT 1,
    latest_app_version_code INT UNSIGNED NOT NULL DEFAULT 1,
    force_update TINYINT(1) NOT NULL DEFAULT 0,
    update_url VARCHAR(500) NULL,
    cashapp_username VARCHAR(100) NOT NULL DEFAULT '$streamtownofficial',
    cashapp_url VARCHAR(255) NOT NULL DEFAULT 'https://cash.app/$streamtownofficial',
    tmdb_enabled TINYINT(1) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Exactly one settings row. Replace the xtream_base_url with the real panel
-- URL before going live; this is only a starting value.
INSERT INTO app_settings (id, xtream_base_url)
    VALUES (1, 'http://streamtown.live:8080')
    ON DUPLICATE KEY UPDATE id = id;

CREATE TABLE IF NOT EXISTS renewal_prices (
    duration_months TINYINT UNSIGNED NOT NULL PRIMARY KEY,
    price DECIMAL(8,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- No rows seeded: real prices are admin-entered, never invented here. See
-- DEPLOYMENT.md. The renewal API itself ships in a later milestone.

CREATE TABLE IF NOT EXISTS announcements (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message VARCHAR(500) NOT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS devices (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    installation_id CHAR(36) NOT NULL,
    device_model VARCHAR(100) NULL,
    device_token_hash CHAR(64) NOT NULL,
    xtream_username VARCHAR(190) NULL,
    status ENUM('active', 'revoked') NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_installation (installation_id),
    UNIQUE KEY uniq_device_token_hash (device_token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pairings (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    pairing_id CHAR(36) NOT NULL,
    public_code CHAR(6) NOT NULL,
    poll_token_hash CHAR(64) NOT NULL,
    installation_id CHAR(36) NOT NULL,
    device_model VARCHAR(100) NULL,
    status ENUM('pending', 'completed', 'expired') NOT NULL DEFAULT 'pending',
    encrypted_credentials TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    UNIQUE KEY uniq_pairing_id (pairing_id),
    UNIQUE KEY uniq_public_code (public_code),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Not yet wired to an API endpoint (renewal milestone); table exists now so
-- that milestone ships without a schema migration.
CREATE TABLE IF NOT EXISTS renewal_requests (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    installation_id CHAR(36) NULL,
    xtream_username VARCHAR(190) NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    duration_months TINYINT UNSIGNED NOT NULL,
    price DECIMAL(8,2) NOT NULL,
    status ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reserved for the admin panel milestone.
CREATE TABLE IF NOT EXISTS admin_users (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    installation_id CHAR(36) NULL,
    ip_address VARCHAR(45) NOT NULL,
    detail VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_event_type (event_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rate_limits (
    bucket VARCHAR(64) NOT NULL,
    identifier VARCHAR(190) NOT NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    window_started_at INT UNSIGNED NOT NULL,
    PRIMARY KEY (bucket, identifier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
