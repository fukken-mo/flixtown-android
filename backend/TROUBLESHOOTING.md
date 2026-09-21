# Troubleshooting

## `secrets.php is missing` (HTTP 500 on every endpoint)

You haven't copied `secrets.example.php` to `secrets.php` yet, or it's not
where `includes/db.php` expects it (one directory above `includes/`). See
CPANEL-INSTALL-GUIDE.md steps 5 and 8.

## `/api/v1/config.php` returns 500

Check your PHP error log (cPanel → Errors, or `storage/logs` if you've
configured PHP to log there). Most likely causes: wrong DB credentials in
`secrets.php`, or `schema.sql` was never imported (`app_settings` table
missing/empty).

## `/pair` shows "HTTPS is required to activate Flix Town."

The site isn't being served over HTTPS, or your host's proxy doesn't set
`X-Forwarded-Proto` and PHP can't tell. Confirm SSL is issued and active
(CPANEL-INSTALL-GUIDE.md step 2) and that you're visiting `https://`, not
`http://`.

## Pairing code always says "invalid or expired"

- Codes expire after 10 minutes — generate a fresh one on the TV.
- Confirm the TV and the backend agree on the time (a wildly wrong server
  clock breaks `expires_at` comparisons). Check with `date` over SSH.
- Confirm `pair/start.php` on the TV side is hitting
  `https://flixtown.panelsandapps.com/api/v1/pair/start.php` and not a stale
  cached DNS/host entry.

## "That username or password was not accepted" on `/pair`

This means the backend successfully reached your Xtream server and it
rejected the credentials — not a backend bug. Verify the credentials work
directly against the Xtream panel, and confirm `app_settings.xtream_base_url`
in the database points at the right server (DATABASE-SETUP.md).

## TV never finishes pairing after `/pair` shows success

- Check that `pair/status.php` and `pair/ack.php` are reachable from the
  TV's network (some IPTV boxes sit behind restrictive DNS/firewalls).
- Check `audit_log` for `pair_complete` (phone side succeeded) followed by
  `pair_ack` or `pair_ack_replay` (TV side succeeded). If `pair_complete`
  exists but no ack ever shows up, the TV isn't reaching `pair/ack.php` —
  a network/firewall issue on the TV's side, not the backend.

## Manual login always fails even with correct credentials

`auth/register.php` re-validates against Xtream itself (it doesn't trust the
app's own check) — same causes as the `/pair` credential failure above.
Also check the `auth_register_ip` / `auth_register_install` rate limits
haven't been tripped (15 attempts / 5 minutes) if you've been testing
repeatedly.

## `curl -I https://flixtown.panelsandapps.com/secrets.php` returns 200

Stop — this means `secrets.php` ended up inside `public_html`. Move it out
immediately (CPANEL-INSTALL-GUIDE.md step 5) and rotate the database
password and libsodium key, since they were briefly exposed.

## Cron cleanup isn't running

- Check `storage/logs/cleanup.log` for output/errors.
- Confirm the cron line uses the correct absolute path to both `php` and
  `cleanup_pairings.php` (paths differ per hosting account — copy the exact
  path cPanel shows for your account, not a guess).
- The script exits immediately with `PHP_SAPI !== 'cli'` if it's ever hit
  over HTTP by mistake — that's intentional, not a bug.

## Where to look for more detail

Every endpoint calls `error_log()` on unexpected failures with a
`[endpoint-name]` prefix (e.g. `[pair/ack]`, `[auth/register]`) — check your
PHP error log for that prefix first when something returns a generic 500.
