#!/usr/bin/env bash
# Drives the QA build on a 1080p Android TV emulator with remote key events.
# Screenshots are written on the device (fast) and pulled at the end.
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=qa/screenshots
rm -rf qa && mkdir -p "$OUT"
{ adb shell wm size; adb shell wm density; adb shell getprop ro.build.version.release; } > qa/device.txt
adb install -r "$APK" || exit 1
adb shell settings put system screen_off_timeout 1800000 || true
adb shell rm -rf /sdcard/qa; adb shell mkdir -p /sdcard/qa
adb logcat -c

shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/qa/$1.png"; echo "shot $1"; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
launch(){ adb shell am start -S -W -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }

# --- Sign in (clean install) ---
launch --ez demo_reset true
shot 01-login-qr 4
sleep 16;                                   shot 02-first-launch-loading 0.1
sleep 4;                                    shot 03-home-hero 1.5

# --- Hero ---
sleep 11;                                   shot 04-hero-rotated 0.2
key DPAD_RIGHT;                             shot 05-hero-more-info-focus 0.3
key DPAD_LEFT;                              shot 06-hero-watch-focus 0.3

# --- Sections (first run: no Continue Watching yet) ---
key DPAD_DOWN;                              shot 07-trending-focus 0.9
key DPAD_RIGHT DPAD_RIGHT;                  shot 08-trending-third 0.9
key DPAD_DOWN;                              shot 09-latest-movies 0.9
key DPAD_RIGHT;                             shot 10-latest-movies-right 0.9
key DPAD_DOWN;                              shot 11-latest-tv 0.9
key DPAD_DOWN;                              shot 12-genres 0.9
key DPAD_UP;                                shot 13-back-up-to-tv-row 0.6
key BACK;                                   shot 14-back-to-hero 1.2
key DPAD_LEFT;                              shot 15-nav-expanded 0.6
key DPAD_DOWN;                              shot 16-nav-movies-focus 0.4
key DPAD_RIGHT;                             shot 17-nav-closed 0.6
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 18-genres-again 0.6
key DPAD_RIGHT DPAD_CENTER;                 shot 19-genre-opens-grid 1.5
key BACK;                                   shot 20-back-home 1.2

# --- Play a movie so Continue Watching appears ---
key DPAD_DOWN DPAD_CENTER;                  shot 21-details-from-trending 3
key DPAD_CENTER;                            sleep 20
key BACK;                                   shot 22-back-in-details 1.5
key BACK;                                   shot 23-home-after-player 1.2
key BACK;                                   shot 24-hero-with-continue 1.5
key DPAD_DOWN;                              shot 25-continue-watching-focus 1
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 26-lower-sections 0.9
key DPAD_DOWN;                              shot 27-because-you-watched 0.9
key BACK;                                   shot 28-hero-again 1.2
key DPAD_DOWN DPAD_CENTER;                  shot 29-continue-opens-player 3
key BACK;                                   shot 30-back-from-player 1.5

# --- TV show from Latest TV Shows ---
key DPAD_DOWN DPAD_DOWN DPAD_DOWN;          shot 31-latest-tv-focus 0.9
key DPAD_CENTER;                            shot 32-series-details 3
key BACK;                                   shot 33-back-to-row-same-card 1.2

# --- Search, Movies grid, Settings, Exit ---
key BACK DPAD_LEFT DPAD_UP DPAD_CENTER;     shot 34-search 1
key DPAD_LEFT DPAD_DOWN DPAD_DOWN DPAD_CENTER; shot 35-movies-grid 1.5
key DPAD_LEFT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; shot 36-settings 1
key BACK;                                   shot 37-home-from-settings 1
key BACK;                                   shot 38-exit-dialog 0.6
key DPAD_CENTER;                            shot 39-stayed 0.6

# --- Fresh launch with cached titles, then expired account ---
launch;                                     shot 40-launch-loading 0.2
sleep 3;                                    shot 41-launch-home 0.3
launch --ez demo_expired true;              shot 42-renewal 6

adb pull /sdcard/qa/. "$OUT/" > /dev/null
adb logcat -d -b crash > qa/crash-log.txt || true
adb logcat -d | grep -E "FlixTown|AndroidRuntime|FATAL" | tail -n 400 > qa/app-log.txt || true
ls "$OUT" | wc -l
