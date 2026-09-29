#!/usr/bin/env bash
# Drives the QA build on a 1080p Android TV emulator with remote key events.
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=qa/screenshots
rm -rf qa && mkdir -p "$OUT"
adb shell wm size > qa/device.txt; adb shell wm density >> qa/device.txt; adb shell getprop ro.build.version.release >> qa/device.txt
adb install -r "$APK" || exit 1
adb logcat -c
# Keep the screen awake during the run.
adb shell settings put system screen_off_timeout 1800000 || true

shot(){ sleep "${2:-1.2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.8; done; }
focus(){ adb shell dumpsys window | grep -m1 mCurrentFocus >> qa/focus.txt; }
launch(){ adb shell am start -S -W -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }

# --- Login (clean install) ---
launch --ez demo_reset true
shot 01-login-qr 4
key DPAD_CENTER;                 shot 02-login-remote
key DPAD_DOWN DPAD_DOWN;         shot 03-login-remote-signin-focus
key BACK;                        shot 04-login-back-to-qr
# The demo panel approves the QR code after five polls (~15 s).
sleep 14;                        shot 05-after-approval-loading 0.2
sleep 5;                         shot 06-home 1

# --- Home ---
key DPAD_DOWN;                   shot 07-home-first-row-focus
key DPAD_RIGHT DPAD_RIGHT;       shot 08-home-row-right
key DPAD_DOWN;                   shot 09-home-second-row
key DPAD_DOWN;                   shot 10-home-third-row
key BACK;                        shot 11-home-back-to-hero
key BACK;                        shot 12-exit-dialog
key DPAD_CENTER;                 shot 13-exit-stay
key DPAD_LEFT;                   shot 14-menu-open
key DPAD_DOWN DPAD_DOWN;         shot 15-menu-tv-shows-focus
key DPAD_UP DPAD_CENTER;         shot 16-movies-grid 2

# --- Movies grid ---
key DPAD_RIGHT DPAD_RIGHT DPAD_DOWN; shot 17-grid-focus-names
key DPAD_UP DPAD_UP;             shot 18-grid-filters-focus
key DPAD_CENTER;                 shot 19-sort-picker
key DPAD_DOWN DPAD_CENTER;       shot 20-sorted-title
key DPAD_RIGHT DPAD_CENTER;      shot 21-category-picker
key DPAD_DOWN DPAD_CENTER;       shot 22-category-action
key DPAD_DOWN DPAD_DOWN DPAD_RIGHT; shot 23-grid-position
key DPAD_CENTER;                 shot 24-movie-details 3
key DPAD_DOWN;                   shot 25-details-cast-focus
key DPAD_RIGHT DPAD_RIGHT;       shot 26-details-cast-right
key DPAD_DOWN;                   shot 27-details-more-like-this
key DPAD_RIGHT;                  shot 28-details-more-right
key BACK;                        shot 29-back-to-grid-same-poster 1.5
key BACK;                        shot 30-back-to-home

# --- Player ---
key DPAD_DOWN DPAD_CENTER;       shot 31-home-row-details 3
key DPAD_CENTER;                 shot 32-player-starting 3
sleep 12;                        shot 33-player-playing-clean 0.5
key DPAD_UP;                     shot 34-player-controls 0.8
key DPAD_RIGHT;                  shot 35-player-audio-focus 0.5
key DPAD_CENTER;                 shot 36-audio-menu 1
key BACK;                        shot 37-audio-menu-closed 0.5
key DPAD_RIGHT DPAD_CENTER;      shot 38-subtitles-menu 1
key DPAD_DOWN DPAD_CENTER;       shot 39-subtitle-selected 2
sleep 4;                         shot 40-controls-auto-hidden 0.2
key DPAD_RIGHT;                  shot 41-seek-plus-10 0.3
key DPAD_RIGHT DPAD_RIGHT;       shot 42-seek-plus-30 0.3
sleep 5;                         shot 43-after-seek-hidden 0.2
key DPAD_CENTER;                 shot 44-paused-with-controls 0.5
key BACK;                        shot 45-back-hides-controls 0.5
key DPAD_CENTER;                 shot 46-resumed 1
sleep 3; key BACK;               shot 47-back-leaves-player 2
key DPAD_CENTER;                 shot 48-resume-choice 3
key DPAD_CENTER;                 shot 49-continued 4
key BACK BACK;                   shot 50-back-in-details 1
key BACK;                        shot 51-home-continue-row 1.5

# --- TV show ---
key DPAD_LEFT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; shot 52-tv-grid 2
key DPAD_CENTER;                 shot 53-series-details 3
key DPAD_DOWN;                   shot 54-season-button-focus
key DPAD_CENTER;                 shot 55-season-picker 1
key DPAD_DOWN DPAD_CENTER;       shot 56-season-2
key DPAD_DOWN DPAD_RIGHT;        shot 57-episode-focus
key BACK BACK;                   shot 58-back-home

# --- Search and Settings ---
key DPAD_LEFT DPAD_UP DPAD_UP DPAD_CENTER; shot 59-search
key DPAD_LEFT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; shot 60-settings

# --- Fresh launch with cached titles (loading screen, then Home) ---
launch;                          shot 61-launch-loading 0.3
sleep 3;                         shot 62-launch-home 0.2

# --- Expired account ---
launch --ez demo_expired true;   shot 63-renewal 6
key DPAD_UP DPAD_UP;             shot 64-renewal-plans-focus
key DPAD_CENTER;                 shot 65-renewal-plan-selected 1.5

adb logcat -d -b crash > qa/crash-log.txt || true
adb logcat -d | grep -E "FlixTown|AndroidRuntime" | tail -n 400 > qa/app-log.txt || true
# Smaller files for the repository: 1080p JPEGs.
if command -v convert >/dev/null; then for f in "$OUT"/*.png; do convert "$f" -quality 82 "${f%.png}.jpg" && rm "$f"; done; fi
ls -la "$OUT"
