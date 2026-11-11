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

Nimer TV has one permanent production signing key (created 11 November 2026 for the first
release). Its public certificate fingerprint is pinned in `release-signer.sha256`:

```
SHA-256  11:AF:C2:34:D4:73:65:AE:11:E0:85:E0:3F:F1:51:CB:82:EC:A6:6B:68:B8:84:99:5A:59:6C:E7:A5:34:B5:E1
```

The keystore and its passwords are **not** in this repository and must never be added to
it, to a ZIP of the source, or to any chat or email.

To build a release locally, create `nimertv/keystore.properties` (ignored by git):

```properties
storeFile=/absolute/path/to/nimertv-release.jks
storePassword=...
keyAlias=nimertv
keyPassword=...
```

or set `NIMER_KEYSTORE_FILE`, `NIMER_KEYSTORE_PASSWORD`, `NIMER_KEY_ALIAS`,
`NIMER_KEY_PASSWORD`. Then:

```bash
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk NimerTV27.apk
./verify-release.sh NimerTV27.apk
```

`verify-release.sh` fails unless the APK is `com.nimertv.app`, is not debuggable and is
signed by exactly the pinned production certificate. It prints the version code and the
file's SHA-256 for the panel. Without a key the release APK is unsigned and cannot be
installed.

GitHub Actions (`.github/workflows/build-nimertv.yml`) builds the debug APK and runs the
tests on every push that touches `nimertv/`. When the repository secrets
`NIMER_KEYSTORE_BASE64`, `NIMER_KEYSTORE_PASSWORD`, `NIMER_KEY_ALIAS` and
`NIMER_KEY_PASSWORD` exist, it also builds `NimerTV27.apk`, runs `verify-release.sh` on it
and uploads it as the `NimerTV27-release` artifact.

### Backing up the signing key

Android only installs an update signed with the same key as the installed app. If the
keystore or its password is lost, no customer can ever be updated again: every customer
would have to uninstall and reinstall. If it leaks, someone else can sign updates.

- Keep the keystore file in at least two places you control, e.g. an encrypted USB drive
  stored offline and a password manager's secure file attachment.
- Keep the passwords in a password manager, not in the same plain folder as the keystore.
- Never store either in GitHub (other than as Actions secrets), cPanel, the panel's web
  root, a shared drive link, email or chat.
- After the first backup, check it: `keytool -list -v -keystore nimertv-release.jks` with
  the password must show the fingerprint above.

## Publishing a release

Permanent file name and address (never change them):

```
NimerTV27.apk
https://panelsandapps.com/downloads/myapps/nimertv/NimerTV27.apk
```

Every release keeps the package `com.nimertv.app` and the production certificate, and has
a higher `versionCode` than every build published before.

| Release | versionCode | versionName |
| --- | --- | --- |
| First production release | 3 | 3.0.0 |

1. For an update, raise `nimerVersionCode` and `nimerVersionName` in `gradle.properties`
   and add a row to the table above. (Skip for the first release.)
2. Build the release APK and run `./verify-release.sh NimerTV27.apk`.
3. Install that exact file on a real Android TV and test it (see "Not yet verified on
   hardware"). For an update, install it over the previous release to confirm Android
   accepts it.
4. Upload it to the server as `NimerTV27.apk`, replacing the existing file.
5. In the panel's APK update management, publish the version: the new version code, the
   download address above, and the `file SHA256` printed by `verify-release.sh`. The
   certificate fingerprint above (without colons, lowercase) goes in the signer field if
   the panel has one.

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

A debug build and a release build are signed with different keys. Uninstall any debug
build from a device before installing `NimerTV27.apk` on it.

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
