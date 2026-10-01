# Flix Town native TV

Java, XML Views, RecyclerView/Leanback grids and Media3, built for Chromecast with Google TV.
The app installs as `com.myflixtown.tv.native` and reads the panel at `BuildConfig.PANEL_URL`.

## Builds

- `./gradlew assembleDebug` – normal build (`app/build/outputs/apk/debug/app-debug.apk`).
- `./gradlew assembleDebug -PflixPreview=true` – test copy named **Flix Town Preview**
  (`com.myflixtown.tv.native.preview`). It installs next to the working app instead of replacing it,
  needs its own sign-in, and is signed with the fixed test key in `signing/` (test apps only), so
  each Preview build installs over the previous one.
- `-PflixVersionCode=… -PflixVersionName=…` build the same source with another version.
- GitHub Actions → **Build Flix Town APK** → *Run workflow* (tick *preview* for the test copy).

## Emulator QA

`./gradlew assembleQa` builds **Flix Town QA** (`.qa`) with an offline demo catalog
(`DemoData.java`); it never contacts the panel or Xtream server (only its update check talks to a
test panel on the host). GitHub Actions →
**TV emulator QA** runs it on a 1920×1080 Android TV emulator, drives every screen with D-pad key
events (`.github/qa/run.sh`) and commits the screenshots to `qa/screenshots/`.

## Panel settings used by the app

`config.php`: `xtream_url`, `intro_enabled`, `intro_url` (https), `cashapp_url` (https),
`plans` (`1m`, `3m`, `6m`, `12m` prices). Renewal requests go to `renewal-request.php`; an account reopens only when the
Xtream account API reports `Active`.

## App updates

`panel/` is a drop-in module for the panel's `api/` folder (`panel/README-UPDATES.txt` has the
upload steps). `app-update-admin.php` takes one APK link and reads package ID, versionCode,
versionName, SHA-256 and signing certificate from the APK itself; `app-update.php` serves them.
The app (`AppUpdates.java`, `UpdateInfo.java`) checks after launch and from Settings, downloads
without caches, verifies checksum, package, version and signing key, then opens Android's
installer.

Release builds are signed with the permanent key from the repository secrets
`FLIXTOWN_RELEASE_KEYSTORE_BASE64`, `FLIXTOWN_RELEASE_KEYSTORE_PASSWORD`,
`FLIXTOWN_RELEASE_KEY_ALIAS` and `FLIXTOWN_RELEASE_KEY_PASSWORD` (never stored in the repository).
GitHub Actions → **App update flow** builds three versions, checks the panel against `aapt2`
and `apksigner`, and updates the app through the panel on a TV emulator (`qa/update-flow/`).
