# Account sync patch, NOT DEPLOYED

This is an optional new API alongside existing OnePanel requests/reports. It does not change their files, users, Seerr, catalog or media. Never install into the FastAPI content server.

Review and back up OnePanel files and database first. No deployment is performed by CI.

1. Import `sql/account-sync.sql` into OnePanel's existing MySQL/MariaDB database. It adds only `tv_sync_accounts`, `tv_sync_records`, `tv_sync_rate`. Run with a backup; never replace the database.
2. Copy `api/tv-account-sync.php` to `public_html/panels/onepanel/api/tv-account-sync.php` and `includes/account_sync.php` to `public_html/panels/onepanel/includes/account_sync.php`.
3. Existing `includes/bootstrap.php` and `includes/tv_requests.php` must be present. Uses the existing Flix adapter login and active-account validation, never Seerr admin access. No dependency on reports being enabled.
4. Add `progress_sync` in OnePanel's `services` config: `['enabled' => true]`. The secret defaults to existing `services.seerr.customer_session_secret`, but HMAC purpose is strictly separate. An optional private `session_secret` of at least 32 bytes can override it. Never put it in the APK.
5. In the EXISTING Flix Town `api/config.php` response add only the key `progress_sync_api_url` with `https://panelsandapps.com/panels/onepanel/api/tv-account-sync.php`. Keep every existing response field and update module. Do not replace config.php with an older bundled copy. Do this only when authorized to enable the feature.

PHP 7.4+ with PDO MySQL, existing Requests adapter prerequisites. No cron required. Normal permissions: files 644. Endpoint uses POST JSON only, HTTPS, no redirects, short purpose-bound sessions. It verifies active accounts on every exchange. Response headers prohibit HTTP caching. Credentials are used only for session issuance and never stored by this module.

Disable safely: remove/empty the app config URL (new config fetch) or set `services.progress_sync.enabled` false. Old clients with a cached URL simply receive 503 and retain local data. Do not delete sync records or tombstones as routine cleanup. Keep SQL tables during rollback; do not roll back the working production app. Expired rate buckets may be removed with `DELETE FROM tv_sync_rate WHERE window_start < UNIX_TIMESTAMP() - 86400` during normal maintenance. Keep account backups private.

Do NOT publish the Preview as production. The production version/signing defaults are unchanged. Feature is inactive unless the new URL is configured.
