# Remote navigation specification

Reference: user-provided Nio Player recording, September 25, 2026. Reimplement independently with Android TV focusable views. The reference APK bundles AndroidX Leanback and RecyclerView, but does not expose unprotected navigation source.

## Screen behavior

- Home uses a narrow icon rail on the left, a full-width backdrop and title summary at the top, and horizontally scrolling poster rows below. The rail opens only when focused or invoked with LEFT from the first content item.
- HOME, SEARCH, MOVIES, SERIES, FAVORITES and SETTINGS are the app destinations. Do not show Live TV, Catch Up, Sports Guide, or provider selection.
- UP/DOWN switches between content rows while preserving the closest poster column. LEFT/RIGHT changes posters within a row. Moving from the first poster to LEFT focuses the rail, and RIGHT returns to the last focused poster.
- Keep a stable focused item ID and row scroll position when a title opens and when BACK returns. Focus should always be visible after catalog refresh.
- Focus enlarges the selected poster and raises it above adjacent items, with a clear solid red highlight. Do not recreate a full screen or decode a new large backdrop for every rapid focus move: debounce backdrop changes and cancel stale image requests.
- Series details open with the title, Watch Now, Trailer, season tabs and episode cards. DOWN reaches season and episodes; LEFT/RIGHT moves among episodes. BACK unwinds the focus path predictably.
- Player D-pad LEFT/RIGHT seeks ten seconds, UP/OK reveals controls, subtitles remain reachable by D-pad, and BACK closes controls before leaving playback. Playback keeps hardware decoding where supported, with a device-specific software fallback if the stream requires it.

## Performance acceptance checks

- Cold launch should show the cached shell without waiting for catalog network calls. Refresh metadata on every opening and swap lists by stable IDs after parsing off the UI thread.
- No jank during fast D-pad presses across rows, opening and closing details, or scrolling seasons on target Chromecast, Google TV and onn devices.
- Measure startup, frame timing, memory, focus restoration, subtitles, and playback against actual devices before a release APK is called final.
