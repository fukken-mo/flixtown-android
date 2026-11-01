# Profiles Preview

## Source and scope

Branch `codex/profiles`, based on `0c90433` (foundation), which includes the green
watched badges and one-time notice fix. Production package, release version defaults,
permanent signing, panel configuration and live services remain unchanged.

## Implemented in the Preview

- Full TV picker, custom name entry, all 28 supplied avatars, editing and deletion.
- Up to six account-owned profiles, Unicode names up to 24 code points.
- Immutable default ID retains existing local/remote history and the old endpoint.
- New UUID profiles start with empty Continue Watching, My List and watched records.
- Names/avatars and each profile's progress synchronize across TVs using the new
  authenticated profile service. A different account cannot select a known UUID.
- Profile metadata uses revisions/CAS; stale edits get a refresh message. Deleted
  IDs are tombstoned and cannot be recreated. Profile mutations and non-default
  progress writes share an account lock, so a deleted profile rejects late writes.
- Default sync, named-profile sync and metadata reuse an in-memory account session
  so switching profiles does not repeatedly consume the login rate limit.
- Separate fixed-profile preferences and journals. An old HTTP response is rejected
  on switching. Progress is never routed through a mutable current-profile view.
- A best-effort final upload on switching reads/saves on the worker and uploads
  without processing replies. Pending operations remain durable and replay safely.
- Metadata replies only apply over their foreground activity. No profile changes
  are imported underneath Details or playback. Switching rebuilds Home adapters.
- An unavailable/missing profile endpoint leaves existing/default playback intact.
  Saved profiles can be selected offline. Editing requires the service.
- Notifications remain account-wide, once per account per installation. Changing
  profile does not reset notice acknowledgements. Requests/reports/renewal are
  still account-wide. Playback preferences remain device-wide in this Preview.

## Kids mode is NOT implemented

The avatar picker does not imply parental filtering. No Kids toggle is offered.
The server rejects kids=true. Approved content policy, server-side access control,
parental PINs and all-route filtering must be built and tested separately before
Kids profiles can be enabled. Do not infer safety from Animation, star ratings,
an avatar or a title's name. The real provider's certification metadata has not
been verified. This Preview must not be advertised as having parental controls.

## Backend patch

New `tv-profile-sync.php` handles metadata and named-profile progress. The old
`tv-account-sync.php` is unchanged. A separate endpoint prevents an old server from
silently treating named-profile uploads as default/account uploads.
The endpoint also verifies the matching library protocol/signature before
advertising profiles or accepting named-profile uploads. Partial installations
return unavailable and cannot silently write into the default history.
Patch files and installation instructions: `panel/onepanel-account-sync/INSTALL-PROFILES.md`.
`sql/profiles.sql` adds two metadata tables, leaving existing progress intact.
No config replacement, cron or server avatar uploads. Use staging before live.

## Tests

- 173 JVM tests, including ProfilePrefs isolation/listener checks.
- PHP validation, existing account-sync DB suite and real HTTP endpoint tests on
  MySQL 8.0 and MariaDB 10.11 (fake identity provider, disposable databases).
- 19 device tests on API28 720p and API31 1080p: existing sync tests, watched-card
  test and five new profile tests. Named-profile cross-TV sync, default isolation,
  rename history retention, stale reply on switching, real editor/avatar selection
  and D-pad focus. These use offline fake services; they do not prove live latency.
- Android source at `601e784` passed all JVM tests and 19 device tests on both
  setups. The final panel partial-installation guard and screenshot timing update
  passed on final source `227c571` in run 41362186270. All five jobs succeeded.
  Final totals: 173 JVM tests; 19 device tests on each setup; 14 PHP validation,
  53 sync database and 43 HTTP checks on each database (110 per database).
  Picker and editor screenshots were inspected at both resolutions.

## Remaining acceptance checks

Real TV layout/keyboard, live panel patch with two personal test devices, profile
create/rename/avatar/delete across devices, mixed old/new apps, process kill during
switch/save, offline changes/reconnect, and playback smoothness on hardware.
Profiles refresh on a fresh launch or when opening the picker, not continuously
while video plays. A deleted profile cached on an offline TV can still browse its
locally cached adult history until metadata refreshes; its server writes are denied.
Neither a secure Kids boundary nor remote-device revocation is claimed.

No production APK, release version bump, merge or publishing is authorized by this
Preview workflow. Claude can review the exact diff; no direct Claude session has
been established from this chat.

## Preview deliverables

- APK artifact: https://github.com/fukken-mo/flixtown-android/actions/runs/41362186270/artifacts/13106086447
- Actual package: `com.myflixtown.tv.native.preview`, version
  `3.8.20-profiles-preview` / 10040, minSdk 23. Test key, separate from production.
- APK SHA-256: `9aaadad8d2e011418d79c12260a8fefcae15b8b4bb59d1670d410ad455a867a3`
  (8,645,694 bytes). Bundled avatar count checked: 28.
- Backend ZIP: `deliverables/FlixTown-Profiles-Backend-PREVIEW.zip`
  SHA-256 `3c16d8c6df42c1408639e89e4be585b71de489d06282781a7e4adc59a0d0b9e4`.
  Its five entries match the reviewed sources byte-for-byte. No config or secrets.
- Independent review handoff: `docs/PROFILES-CLAUDE-REVIEW.md`.

Failure history: the first complete emulator run stopped in a UI test that
asserted avatar bounds before Android laid out the editor, with assertions escaping
on the main thread. The helper now waits for layout and propagates failures back to
the runner. A first screenshot capture also caught the old compositor frame; a short
wait on the test thread fixed capture timing. The partial-installation HTTP test
initially edited a library inside a warm PHP worker; a separate cold worker now
models the older installed library and confirms unavailable responses without writes.

Not verified on real customer hardware or live production services. Large system
fonts, real process kills and profile-metadata database restores still need acceptance
coverage. Progress restore handling is tested, including the named-profile reset
response; that does not prove profile-catalog recovery after a database restore.
