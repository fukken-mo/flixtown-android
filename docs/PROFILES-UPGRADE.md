# Profiles Preview

## Source and scope

Branch `codex/profiles`, based on `0c90433` (foundation), which includes the green
watched badges and one-time notice fix. Production package, release version defaults,
permanent signing, panel configuration and live services remain unchanged.

## Implemented, awaiting checks

- Full TV picker, custom name entry, all 28 supplied avatars, editing and deletion.
- Up to six account-owned profiles, Unicode names up to 24 code points.
- Immutable default ID retains existing local/remote history and the old endpoint.
- New UUID profiles start with empty Continue Watching, My List and watched records.
- Names/avatars and each profile's progress synchronize across TVs using the new
  authenticated profile service. A different account cannot select a known UUID.
- Profile metadata uses revisions/CAS; stale edits get a refresh message. Deleted
  IDs are tombstoned and cannot be recreated. Profile mutations and non-default
  progress writes share an account lock, so a deleted profile rejects late writes.
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
Patch files and installation instructions: `panel/onepanel-account-sync/INSTALL-PROFILES.md`.
`sql/profiles.sql` adds two metadata tables, leaving existing progress intact.
No config replacement, cron or server avatar uploads. Use staging before live.

## Tests

- Existing 168 JVM tests plus ProfilePrefs isolation/listener tests.
- PHP validation, existing account-sync DB suite and real HTTP endpoint tests on
  MySQL 8.0 and MariaDB 10.11 (fake identity provider, disposable databases).
- 18 device tests on API28 720p and API31 1080p: existing sync tests, watched-card
  test and four new profile tests. Named-profile cross-TV sync, default isolation,
  rename history retention, stale reply on switching, real editor/avatar selection
  and D-pad focus. These use offline fake services; they do not prove live latency.
- CI is the first Android compile and PHP execution for this implementation.
  Do not describe the results as passing until the jobs finish.

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
