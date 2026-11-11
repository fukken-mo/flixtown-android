# Nimer TV — Android TV app (`com.nimertv.app`)

Android TV app for Live TV, Movies and Series. Customers sign in with a username and
password only; the Xtream server address, holiday splash screens, announcements,
maintenance mode and app updates all come from the Nimer TV panel.

This project is self-contained in `nimertv/` and does not share code or build files with
Flix Town in the repository root.

## Build

Requirements: JDK 17+, Android SDK platform 35 (Android Studio installs both).

```bash
cd nimertv
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # tests + screenshots in app/build/screens/
```

Open the `nimertv` folder (not the repository root) in Android Studio.

### Release build and signing

A release APK is signed only when a key is supplied. Create `nimertv/keystore.properties`
(ignored by git — never commit it or the keystore):

```properties
storeFile=/absolute/path/to/nimertv-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

or set `NIMER_KEYSTORE_FILE`, `NIMER_KEYSTORE_PASSWORD`, `NIMER_KEY_ALIAS`,
`NIMER_KEY_PASSWORD`. Then:

```bash
./gradlew :app:assembleRelease -PnimerVersionCode=4 -PnimerVersionName=3.0.1
```

Without a key the release APK is unsigned and cannot be installed.

**The signing key is the app's identity.** Android only installs an update signed with the
same key as the installed app. Keep the keystore and its passwords backed up in at least
two safe places; if it is lost, existing customers can never be updated and must uninstall
and reinstall. If an earlier Nimer TV build is already on customer devices, releases must
be signed with that same key.

GitHub Actions (`.github/workflows/build-nimertv.yml`) runs the tests and builds the debug
APK on every push that touches `nimertv/`. If the repository secrets
`NIMER_KEYSTORE_BASE64` (the keystore, Base64-encoded), `NIMER_KEYSTORE_PASSWORD`,
`NIMER_KEY_ALIAS` and `NIMER_KEY_PASSWORD` exist, it also builds the signed release APK.

## Publishing an update

1. Raise `nimerVersionCode` (and `nimerVersionName`) — in `gradle.properties` or with `-P`.
   The version code must be higher than every build already published.
2. Build the release APK with the same signing key as before.
3. Upload it over the existing file so the download URL stays the same.
4. Publish the new version in the panel (APK update management) so the configuration API
   announces the new `version_code`, the file's `sha256` and whether it is mandatory.

The app decides from the panel's `version_code` only — never from the file name or date.
It checks at startup, when the app returns to the foreground (at most every 15 minutes)
and on the panel's `refresh_minutes` interval while open. It then downloads the APK,
shows progress, verifies it and opens Android's installer. Android always asks the
customer to confirm; silent installation is not possible for sideloaded apps.

Before installing, the app verifies: HTTPS download, SHA-256 from the panel, package name,
version code (must be newer than installed and at least the announced version) and that
the signing certificate matches the installed app (and the panel's `signer_sha256` when
present).

A mandatory update cannot be skipped: the customer can install, retry or exit. Two
situations deliberately do not lock the app, because no installable update exists: the
panel announces a version without a checksum, or the file on the server is older than the
announced version. Both show an explanation with a Continue button; fix the panel entry.

## Panel API contract

Endpoint: `https://panelsandapps.com/panels/nimertv2027/api/v1/config.php`
(`PanelRepository.CONFIG_URL`). No panel changes are required.

Verified against the live endpoint (November 2026):

| Field | Use |
| --- | --- |
| `api_version` = 1 | Anything else is rejected and the cached configuration is kept |
| `refresh_minutes` | Background refresh interval |
| `app.name`, `app.support.*`, `app.login_message` | Sign-in, maintenance and account screens |
| `app.maintenance.enabled` / `.message` | Maintenance screen |
| `servers[] { name, url, priority }` | Xtream DNS list, primary first, then backups |
| `splash.default`, `splash.schedule[] { id, start_ms, end_ms, image_url, image_sha256 }` | Holiday splash |
| `ETag` / `If-None-Match` | Revalidation (HTTP 304) |

All six splash images published by the panel were downloaded and matched their
`image_sha256`.

Not observable on the live panel at the time (they were `null` / empty), so parsed with
the field names used by the Android client that shipped with the panel:

- `update { package, version_code, version_name, notes, mandatory,
  min_supported_version_code, apk_url, sha256, size, signer_sha256 }`
- `splash.active` (same shape as a schedule entry)
- `announcements[] { id, updated_at, title, message, popup, dismissible, priority, start_ms, end_ms }`

Check these three once against a real panel entry before relying on them.

The server address is never stored in the APK. Customer credentials are kept in
app-private storage and are sent only to the Xtream servers listed by the panel. No panel
administrator credentials exist in the app.

### Cleartext (HTTP) provider

The provider currently serves HTTP. Android's network security configuration can only
list hosts known at build time, and the provider host is delivered by the panel at run
time (streams and posters also come from other hosts and redirects). Cleartext is
therefore allowed in general and **forbidden for `panelsandapps.com`**; panel, splash and
update URLs are additionally refused in code unless they are HTTPS. Customer credentials
travel unencrypted to an HTTP provider — an HTTPS provider address in the panel removes
that exposure without an app update.

### Holiday splash

The device selects the artwork itself from the schedule, so holidays start and end on
time even offline, and expired artwork is never shown. Images are downloaded after each
panel sync (current, default and anything starting within 45 days), verified by SHA-256
and cached; files no longer referenced are deleted. Fallback order: holiday image → panel
default image → image bundled in the APK. Because the splash is drawn before the first
network request, a holiday first seen during a launch appears from the next launch.

## Remote control

| Where | Keys |
| --- | --- |
| Everywhere | D-pad to move, OK to select, Back to go back (confirmation before exit) |
| Lists and grids | Hold OK (or Menu) to add/remove a favorite; in Continue Watching it removes the entry |
| Text fields | OK opens the keyboard, Back closes it |
| Live player | Up/Down or CH+/CH- change channel, OK shows/hides the info bar, Left/Right choose Audio / Subtitles / Favorite |
| Movie and episode player | OK play/pause, Left/Right seek 10 s (hold to accelerate), Down opens Start over / Audio / Subtitles / Next episode |

## Tests

`./gradlew :app:testDebugUnitTest` runs 37 tests on the JVM:

- `PanelConfigTest` — the captured live panel response, validation, splash scheduling
  (start, end, switch-over, stale forced splash), update decisions, archive identity checks.
- `XtreamTest` — sign-in responses, stream URLs, tolerant parsing, series/episode shapes,
  Base64 EPG, Arabic text, resume and Continue Watching rules, search matching.
- `XtreamClientTest` — real HTTP against a local fake Xtream server: sign-in, wrong
  password, expired account, backup-DNS failover at sign-in and mid-session, all servers down.
- `UiScreensTest` — renders the real screens at 1080p with Robolectric and drives them with
  D-pad key events (home → movies → details → favorite → back → live → series), exit
  confirmation, sign-in, maintenance and the mandatory-update gate. Screenshots are written
  to `app/build/screens/`. The posters in those screenshots are synthetic test images.

## Not yet verified on hardware

Everything above ran on a build machine without an Android device or emulator. Before a
public release, test on a real Android TV device:

- Sign-in and playback against the real provider with a customer account (live, movie,
  episode; `m3u8` and `ts`; `mkv`/`mp4` containers; audio and subtitle switching).
- Resume after leaving a movie or episode, and automatic next episode.
- The on-screen keyboard on the sign-in and search fields.
- The full update path: install build N, publish build N+1 in the panel, confirm download,
  verification, Android's install prompt, a cancelled install, and a mandatory update.
- The minified release build (R8) — the tests exercise the debug build.

## Known limitations

- No catch-up/timeshift, no multi-profile, no parental PIN, no external player.
- EPG uses the provider's short guide for the selected channel; there is no full grid guide.
- "Featured" is the best provider-rated titles among recent additions and is hidden when
  the provider supplies no ratings. No ratings or artwork are invented.
- Credentials are stored in app-private storage (not hardware-encrypted).
- Playback formats depend on the device's decoders; unsupported codecs show an error
  instead of playing.
