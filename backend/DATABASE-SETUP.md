# Database setup

Run `schema.sql` once against a fresh MySQL 8+ / MariaDB 10.3+ database
(utf8mb4). It is written with `CREATE TABLE IF NOT EXISTS`, so re-running it
is harmless on a fresh database. If you're upgrading a database created by an
earlier version of this backend, see the commented migration block at the
bottom of `schema.sql` instead of re-running the whole file.

## Tables created

| Table | Purpose |
|---|---|
| `app_settings` | Single row (id=1). Everything `GET /api/v1/config.php` serves: app name, `xtream_base_url`, maintenance mode, intro video, update policy, Cash App info, TMDB flag. |
| `renewal_prices` | Duration (months) → price. Seeded empty — enter real prices yourself before the renewal milestone ships; nothing invents a price. |
| `announcements` | Optional messages surfaced in config responses. |
| `devices` | One row per paired/logged-in installation. `device_token_hash` is a SHA-256 hash — the plaintext token is only ever shown once, to the device, at pairing/login time. |
| `pairings` | Transient QR-pairing state: pending → completed → acked, then reaped by the cleanup cron. `encrypted_credentials` and `issued_device_token_encrypted` are libsodium secretbox ciphertext, never plaintext. |
| `renewal_requests` | Schema-ready for a future milestone; no endpoint writes to it yet. |
| `admin_users` | Schema-ready for a future admin panel; no login flow exists yet. |
| `audit_log` | Append-only event log (pairing started/completed/acked, login attempts) with IP and installation ID, no credentials. |
| `rate_limits` | Backing store for the fixed-window rate limiter used by every endpoint that accepts credentials. |

## After importing: set your Xtream server

The schema seeds `app_settings.xtream_base_url` with
`http://streamtown.live:8080`. To point at a different Xtream panel later
(no app rebuild required):

```sql
UPDATE app_settings SET xtream_base_url = 'http://your-panel:port' WHERE id = 1;
```

## Setting renewal prices (when you're ready to use them)

```sql
INSERT INTO renewal_prices (duration_months, price) VALUES
  (1, 0.00), (3, 0.00), (6, 0.00), (12, 0.00)
ON DUPLICATE KEY UPDATE price = VALUES(price);
```

Replace `0.00` with real prices — never leave placeholder prices live.

## Why pairing rows aren't deleted immediately on ACK

A pairing row moves `pending` → `completed` (phone submitted valid Xtream
credentials) → `acked` (TV successfully acknowledged and received its
permanent device token). It is kept in the `acked` state — not deleted — for
up to an hour so that if the TV's network drops right after a successful ACK
and it retries the same request, the backend can hand back the *same* device
token instead of minting a second one. `tools/cleanup_pairings.php`, run
hourly via cron, deletes rows that have finished this grace window, plus any
abandoned `pending`/`expired` rows older than a day.
