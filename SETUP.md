# Flix Town Android — setup

## Requirements

- Android Studio (Ladybug/2024.2 or newer) or command-line Gradle
- JDK 17
- Android SDK with `platform-36` and `build-tools` installed (Android
  Studio's SDK Manager will prompt for this on first sync)

## Configuration already baked in

- Control/activation backend: `https://flixtown.panelsandapps.com`
  (`app/src/main/java/com/flixtown/tv/core/Constants.kt` —
  `BackendConstants.CONTROL_HOST`)
- Initial Xtream server: `http://streamtown.live:8080` (this lives in the
  **backend** database, not the app — see `backend/DATABASE-SETUP.md`; the
  app always fetches the current Xtream URL from
  `GET /api/v1/config.php` at startup)

If the control domain ever changes again, `BackendConstants.CONTROL_HOST` is
the only place in the app that needs editing, and the app must be rebuilt.
The Xtream server URL never needs an app rebuild — it's backend-configurable.

## Build

```
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

For a release build you'll need your own signing config (none is included —
see `app/proguard-rules.pro` for the R8 rules already in place).

## Project layout

```
app/src/main/java/com/flixtown/tv/
  core/          constants, shared OkHttp client, log redaction, installation ID
  security/      AndroidKeyStore AES/GCM credential storage
  data/          config/Xtream/backend repositories + models
  ui/theme/      black/red/white TV theme (tv-material)
  ui/components/ shared focus-aware TV components (buttons, text field, QR generator)
  ui/login/      QR pairing + manual login screens and view models
  ui/screens/    startup/maintenance/update-required/renewal/home-placeholder screens
  ui/startup/    startup routing view model
  MainActivity.kt, AppGraph.kt, FlixTownApp.kt
```

## What's implemented vs. not (this milestone)

Implemented: startup routing, remote config with offline-safe caching, QR
pairing, manual login, AndroidKeyStore secure storage, installation UUID,
TV-only manifest/banner, black/red/white Compose UI with tv-material focus
handling.

Not implemented yet (by design — later milestones): movie/series catalog,
home screen, player, subtitles, renewal payment flow, TMDB metadata.

See `BUILD-STATUS.md` for the actual build/test result of this package.
