Flix Town Playback Reports - candidate feature, not deployed

Architecture
TV app -> your OnePanel api/tv-playback-reports.php -> existing Flix account/catalog adapter.
No Seerr calls, no media replacement, no Radarr/Sonarr actions, no playback changes server-side.
Uses existing includes/tv_requests.php authentication/catalog helpers. Does not modify that file.
The report session has a separate playback_reports purpose, lifetime 15 minutes. A media-request
session cannot authorize reports and a report session cannot authorize media requests.

Customer flow
Report control next to Audio/Subtitles; Report a problem on playback error card.
Choose one issue, confirm Send report, receive acknowledgement, return to player.
Playback pauses during the flow and returns to its previous play/pause state. Back cancels.
No free-text input. Current media/episode IDs and playback position are frozen when opening.
Server resolves title, episode title and season/episode numbers from the customer's catalog.
Trailers are excluded. A report error never blocks login, startup or normal playback.

Reported fields
Authenticated customer username (admin-only), local catalog movie/series/episode IDs, issue,
playback milliseconds, app version, Android API, device make/model, selected audio/video format
and the Media3 error-code name if present. No stream URLs, passwords, tokens, raw exceptions,
IP address, full catalog payload or Seerr key stored in report rows. Device/format diagnostics
are customer-supplied context, not proof of a file defect.

Deduplication and limits
Same customer + title/episode + issue: one report per rolling 24 hours.
Request UUID is idempotent across network timeout/retry. Separate customers can report the
same issue, with admin-visible repeat/customer counts. A named DB lock serializes duplicates.
Up to 10 new reports/customer/hour; additional login and per-IP limits.
No offline queue and no automatic retries. A manual retry keeps the same UUID.

Backend installation - review and back up first
1. Apply sql/migrations/2026-10-18-playback-reports.sql to the existing OnePanel database.
   Additive: two new tables only. No existing data, tables or customer records are changed.
2. Upload api/tv-playback-reports.php, includes/playback_reports.php and playback-reports.php
   under the existing public_html/panels/onepanel/ directory.
3. Back up includes/layout.php. The supplied version adds one Playback Reports navigation link
   to the exact layout in your uploaded onepanel(4).zip. If the live layout changed since then,
   add that one entry manually rather than overwriting newer changes.
4. Add this section INSIDE the existing services array in config/config.php:

   'playback_reports' => [
     'enabled' => true,
   ],

   It reuses the existing server-side services.seerr.customer_session_secret solely as HMAC
   signing material, with a different token purpose. Seerr API credentials are not used.
   An optional separate session_secret (32+ random characters, server-side only) may be set in
   playback_reports instead. Do not replace your existing config or its credentials.
5. In the Flix Town APP CONTROL panel's api/config.php result array, add:

   'reports_api_url' => 'https://panelsandapps.com/panels/onepanel/api/tv-playback-reports.php',

6. Test with the separate Preview APK. Old production versions ignore this new config field.
   Do not publish a production APK or change signing/versioning until the candidate is approved.

Admin page
OnePanel -> Playback Reports. New, Investigating, Resolved and All views; 25 rows/page.
Every report shows recurrence across customers. Status changes require admin login and CSRF.
Changing a report status does not alter the media file or notify clients automatically.
Retain reports only as long as needed for support. No new cron is required to function.
For large deployments, add a separately reviewed retention policy for old reports and expired
rate rows; this patch intentionally does not delete reports or schedule cleanup jobs.

Rollback / disable
Remove reports_api_url from the app control config and set playback_reports.enabled=false.
New app sessions will hide reporting when they receive the updated config. Existing calls fail
with a generic unavailable response. Existing playback/login/Requests/renewal continue normally.
Restore backed-up layout if removing the nav link. Keep report tables for support history.
Do not drop any existing OnePanel tables.

Testing
Tests use fake Flix accounts/catalog and disposable *_test databases, never live requests.
Run tests/test.php with REPORTS_TEST_DSN, REPORTS_TEST_USER and REPORTS_TEST_PASSWORD configured.
Repository tests reference the adjacent onepanel-tv-requests helper. Do not run tests on production.
See GitHub Playback reports candidate workflow for actual results; do not assume tests passed.
Remaining live checks: deploy on a test copy, validate config/auth, report one movie and one episode,
check the current episode after autoplay, paused/playing state, Back/focus, error-screen reporting,
network failure and OnePanel status management on a TV remote.
