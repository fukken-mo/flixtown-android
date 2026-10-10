-- Flix Town TV app renewals (Cash App Pay through Square). Safe to run once; nothing existing changes.
CREATE TABLE IF NOT EXISTS tv_payments (
  id VARCHAR(40) NOT NULL PRIMARY KEY,
  username VARCHAR(120) NOT NULL,
  months INT UNSIGNED NOT NULL,
  amount_cents INT UNSIGNED NOT NULL,
  devices INT UNSIGNED NOT NULL DEFAULT 1,
  state VARCHAR(20) NOT NULL,
  square_link_id VARCHAR(80) NULL,
  square_order_id VARCHAR(80) NOT NULL,
  square_payment_id VARCHAR(80) NULL,
  checkout_url VARCHAR(500) NOT NULL,
  note VARCHAR(255) NULL,
  created_ts INT UNSIGNED NOT NULL,
  expires_ts INT UNSIGNED NOT NULL,
  updated_ts INT UNSIGNED NOT NULL,
  paid_ts INT UNSIGNED NULL,
  renewed_ts INT UNSIGNED NULL,
  new_expires_ts BIGINT NULL,
  INDEX idx_tv_payments_order (square_order_id),
  INDEX idx_tv_payments_user (username),
  INDEX idx_tv_payments_state (state, created_ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tv_api_rate (
  k VARCHAR(120) NOT NULL PRIMARY KEY,
  window_start INT UNSIGNED NOT NULL,
  hits INT UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
