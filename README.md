# Flix Town native TV preview

This source is the traditional Android Views and Java build on the `codex/flix-compose-tv` branch. The folder name predates the native rebuild; the app no longer includes Compose UI code. It targets Chromecast with Google TV and installs separately as `com.myflixtown.tv.native`.

The launcher opens QR activation first, with remote sign-in behind a smaller button. The home page uses a fixed icon rail, an overlay menu, a hero, and horizontal RecyclerView rows for Latest Movies and Latest TV Shows. Movies and Series use a six-column grid with category and sort pickers. The Media3 player includes D-pad seeking, play/pause, track selection, progress saving, and next episode playback. Renewal, cast, and details are carried from the earlier Java app; these screens still need visual QA on actual TV hardware.

Build with JDK 17 and `./gradlew assembleDebug`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow also uploads that APK on every push to the branch.

The app reads `https://panelsandapps.com/panels/flixtown2027/api/` through `BuildConfig.PANEL_URL`. The panel update feed must publish an APK with this new package name and a version code above `10001`. This source contains no server credentials or signing key. GitHub's debug signing is for testing, not production distribution.
