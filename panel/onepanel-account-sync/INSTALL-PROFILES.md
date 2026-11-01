# Profiles Preview backend patch

Not installed or enabled by this repository. Test against a staging copy first.
This patch is for OnePanel, NOT the Flix Town 2027 panel directory.
Existing account sync must already be installed and working.

1. Back up the OnePanel database and `includes/account_sync.php` outside public_html.
2. In phpMyAdmin, choose the same OnePanel database used by account sync. Import
   `sql/profiles.sql`. It adds `tv_profile_accounts` and `tv_profiles` only. It
   does not modify existing progress, customers, requests, reports or payments.
3. Upload the new `includes/profiles.php` to OnePanel's includes folder.
4. Replace OnePanel's `includes/account_sync.php` with this patch's version.
   Its original three-argument/default behavior is retained for older apps.
5. Upload new `api/tv-profile-sync.php` to OnePanel's api folder.

No configuration replacement, secrets, cron job or avatar uploads are needed.
The app derives the new HTTPS endpoint next to its configured `tv-account-sync.php`.
The avatars are bundled in the APK. Do not upload AI/EPS files to cPanel.

Default profile keeps the original progress namespace and old endpoint. Named
profiles use separate server history namespaces through the new endpoint. Both
endpoints use the existing account login/session checks. Usernames and passwords
are never saved in profile records. Profiles are account-owned, not device-owned.

Kids mode is intentionally NOT offered in this patch. It rejects `kids=true`.
No content certifications or parental PIN behavior have been verified or enabled.

For rollback, select the default profile in the app before removing the new
endpoint or returning to an older app. Older apps only see the default history.
Keep the new database tables and take a backup; do not drop them during rollback.
Named-profile histories remain saved but are not understood by old apps.
Deletion hides a profile and retains its ID tombstone so stale TVs cannot restore
it. It does not permanently erase its history from database backups or TV storage.
