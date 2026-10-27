# Account sync patch, NOT DEPLOYED

This is an optional new API alongside existing OnePanel requests/reports. It does not change their files, users, Seerr, catalog or media. Never install into the FastAPI content server.

Review and back up OnePanel files and database first. No deployment is performed by CI.

1. Import `sql/account-sync.sql` into OnePanel's existing MySQL/MariaDB database. It adds only `tv_sync_accounts`, `tv_sync_records`, `tv_sync_rate`. Run with a backup; never replace the database.
2. Copy `api/tv-account-sync.php` to `public_html/panels/onepanel/api/tv-account-sync.php` and `includes/account_sync.php` to `public_html/panels/onepanel/includes/account_sync.php`.
3. Existing `includes/bootstrap.php` and `includes/tv_requests.php` must be present. Uses the existing Flix adapter login and active-account validation, never Seerr admin access. No dependency on reports being enabled.
4. Add `progress_sync` in OnePanel's `services` config: `['enabled' => true]`. The secret defaults to existing `services.seerr.customer_session_secret`, but HMAC purpose is strictly separate. An optional private `session_secret` of at least 32 bytes can override it. Never put it in the APK.
5. In the EXISTING Flix Town `api/config.php` response add only the key `progress_sync_api_url` with `https://panelsandapps.com/panels/onepanel/api/tv-account-sync.php`. Keep every existing response field and update module. Do not replace config.php with an older bundled copy. Do this only when authorized to enable the feature.

Limits are per account where possible, because many customers can share one public address (carrier NAT, a proxy or CDN in front of OnePanel): new sessions 20 per account per 15 minutes; failed sign-ins 30 per address and 100 per username per 15 minutes (only failures count); exchanges 180 per account; 6,000 requests per address as a flood ceiling. The address is PHP's REMOTE_ADDR. If OnePanel sits behind a proxy or CDN, REMOTE_ADDR is the proxy's: then failed sign-ins from all customers share one bucket. Check what REMOTE_ADDR shows before enabling.

Optional: `'max_records' => 10000` in `services.progress_sync` (records per account, removals included). A full account still updates its existing records; only new records are refused, one by one.

Database restores: the module detects them. Revisions are timestamps and never reused, and each TV checks the newest record it read. After a restore from a backup, TVs re-read their history and upload their newer copies again, so customers' progress is not rolled back. Restore the three tables together.

PHP 7.4+ with PDO MySQL, existing Requests adapter prerequisites. No cron required. Normal permissions: files 644. Endpoint uses POST JSON only, HTTPS, no redirects, short purpose-bound sessions. It verifies active accounts on every exchange. Response headers prohibit HTTP caching. Credentials are used only for session issuance and never stored by this module.

Disable safely: remove/empty the app config URL (new config fetch) or set `services.progress_sync.enabled` false. Old clients with a cached URL simply receive 503 and retain local data. Do not delete sync records or tombstones as routine cleanup. Keep SQL tables during rollback; do not roll back the working production app. Expired rate buckets may be removed with `DELETE FROM tv_sync_rate WHERE window_start < UNIX_TIMESTAMP() - 86400` during normal maintenance. Keep account backups private.

Do NOT publish the Preview as production. The production version/signing defaults are unchanged. Feature is inactive unless the new URL is configured.
