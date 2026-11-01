Review the Flix Town profiles upgrade on `codex/profiles` against
`b53a5b70f7d32e7346bc7acbd31a7a8fabc07221`.

The application is native Java Android Views and Media3, not Compose. Preserve
production package `com.myflixtown.tv.native`, permanent signing, version defaults,
all existing panel endpoints and authentication, subtitles/seeking, playback,
requests, reports, notifications, renewal and updater verification.

This is a Preview implementation. Do not merge, publish, deploy, bump versions or
trigger a production build as part of the review. No live service was changed.
Read `docs/PROFILES-UPGRADE.md` and `panel/onepanel-account-sync/INSTALL-PROFILES.md`.

Requirements implemented:
- Customer-entered Unicode profile names and 28 bundled premade avatars, six
  profiles maximum. Editing a name/avatar retains the stable history ID.
- Existing default profile retains old local/remote history and old app protocol.
- New profiles have isolated Continue Watching, watched state and My List, with
  cross-device sync. Metadata is account-owned and uses revision conflicts.
- Server deletion tombstones and shared locking prevent stale writes from
  recreating profiles. Default profile cannot be deleted.
- Separate endpoint for named-profile progress prevents old servers from silently
  treating it as default history. No configuration changes or secrets in the patch.
- Sessions are shared across profile switches in memory, with captured account,
  provider scope, expiry and sign-out generation checks before cache lookup.
- Profile preference objects bind to a fixed profile. Old replies are invalidated
  on switching. Final best-effort uploads do not apply replies after detaching.
- Choosing a profile rebuilds the activity stack, preventing an old player from
  reopening underneath a different profile. Metadata never applies under a paused
  Home, Details or the player.

Kids mode is NOT implemented or offered. The server rejects kids=true. Avatar
selection and naming a profile Kids do not filter content. Do not describe this
as parental controls. Age metadata, approved content policies and parental PINs
need a separate implementation and review.

Review account/profile isolation, session reuse races, journal durability and
first-import behavior, old/new-client compatibility, profile deletion and stale
responses, offline failure paths, default-history preservation, and worker/main
thread boundaries. Inspect D-pad flow and layout screenshots at 720p and 1080p.
Check that names and avatar choices persist across devices without sharing the
currently selected profile between TVs.

CI includes JVM tests, asset checks, backend tests on MySQL 8.0/MariaDB 10.11,
and TV instrumentation tests with offline fake services. A passing emulator run
is not a real hardware, production-load or live-provider test. Remaining acceptance
checks are listed in the upgrade document. Distinguish actual issues from test
helper faults and report each finding with a concrete trigger and file reference.
