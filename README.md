# Flix Town native TV

Java, XML Views, RecyclerView/Leanback grids and Media3, built for Chromecast with Google TV.
The app installs as `com.myflixtown.tv.native` and reads the panel at `BuildConfig.PANEL_URL`.

## Builds

- `./gradlew assembleDebug` – normal build (`app/build/outputs/apk/debug/app-debug.apk`).
- `./gradlew assembleDebug -PflixPreview=true` – test copy named **Flix Town Preview**
  (`com.myflixtown.tv.native.preview`). It installs next to the working app instead of replacing it,
  needs its own sign-in, and skips panel update prompts.
- GitHub Actions → **Build Flix Town APK** → *Run workflow* (tick *preview* for the test copy).

## Emulator QA

`./gradlew assembleQa` builds **Flix Town QA** (`.qa`) with an offline demo catalog
(`DemoData.java`); it never contacts the panel or Xtream server. GitHub Actions →
**TV emulator QA** runs it on a 1920×1080 Android TV emulator, drives every screen with D-pad key
events (`.github/qa/run.sh`) and commits the screenshots to `qa/screenshots/`.

## Panel settings used by the app

`config.php`: `xtream_url`, `intro_enabled`, `intro_url` (https), `cashapp_url` (https),
`plans` (`1m`, `3m`, `6m`, `12m` prices), `update_version_code`, `update_apk_url`, `update_notes`,
`update_required`. Renewal requests go to `renewal-request.php`; an account reopens only when the
Xtream account API reports `Active`.
