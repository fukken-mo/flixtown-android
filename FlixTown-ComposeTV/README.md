# Flix Town Compose TV starter

Fresh Kotlin + Compose for TV project. It reads the existing Flix Town panel configuration, supports direct Xtream credentials or QR pairing, refreshes movie and series lists on opening, and loads season/episode lists in Details. The player accepts HTTP(S) Xtream streams through its intent.

This remains a preview. The side menu has Home, Movies, Series, Search, Watchlist, and Settings. Movies and Series use full catalog grids with Xtream categories and sorting. Continue Watching, subtitles, renewal, intro, panel-driven updates, and polished TV playback controls are not integrated yet. QR login needs a live panel, and panel endpoints must use HTTPS.

Open this directory as an Android Studio project or build with `./gradlew assembleDebug` and find the APK in `app/build/outputs/apk/debug/`.

The debug APK has CI-generated debug signing and is for testing. A production update flow requires a stable release signing key.
