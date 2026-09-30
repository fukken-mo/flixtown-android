#!/usr/bin/env bash
# Drives the QA build on a 1080p Android TV emulator with remote key events.
# Screenshots are written on the device (fast) and pulled at the end. After key steps the
# focused view is written to qa/focus.txt, so focus movement is checked, not just looked at.
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
# Both use the TV launcher's own intent. Android only brings an existing task back when the intent
# matches the one that started it, exactly as when the viewer picks Flix Town on the home screen.
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am start -S -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
resume(){ adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null; }
# The focused view: class, flags, bounds and id from the live hierarchy (" .F" = focused).
focus(){ { echo "== $1"; adb shell dumpsys activity top | grep -E ' [.R]F[.S][.H]' | grep -v 'DecorView' | head -3; } >> qa/focus.txt; }

# --- Sign in (clean install) ---
launch --ez demo_reset true
shot 01-login-qr 4
sleep 16;                                   shot 02-first-launch-loading 0.1
sleep 4;                                    shot 03-home 1.5;                focus home

# --- Movies from the menu (the reported D-pad bug) ---
key DPAD_LEFT;                              shot 04-menu-open 0.5
key DPAD_DOWN;                              shot 05-menu-movies-focus 0.3
key DPAD_CENTER;                            shot 06-movies-first-poster 1.2;  focus movies-enter
key DPAD_RIGHT;                             shot 07-grid-right 0.4;           focus grid-right
key DPAD_DOWN;                              shot 08-grid-down 0.6;            focus grid-down
key DPAD_LEFT;                              shot 09-grid-left 0.4;            focus grid-left
key DPAD_UP;                                shot 10-grid-up 0.6;              focus grid-up
key DPAD_UP;                                shot 11-up-to-sort 0.6;           focus up-to-sort

# --- Sort -> grid ---
key DPAD_CENTER;                            shot 12-sort-picker 0.8
key DPAD_DOWN DPAD_CENTER;                  shot 13-sorted-title 1;           focus after-sort
key DPAD_DOWN;                              shot 14-sort-to-grid 0.6;         focus sort-to-grid

# --- Categories -> grid ---
key DPAD_UP DPAD_RIGHT;                     shot 15-category-chip 0.4;        focus category-chip
key DPAD_CENTER;                            shot 16-category-picker 0.8
key DPAD_DOWN DPAD_DOWN DPAD_CENTER;        shot 17-category-selected 1;      focus after-category
key DPAD_DOWN;                              shot 18-category-to-grid 0.6;     focus category-to-grid
key DPAD_RIGHT DPAD_RIGHT DPAD_DOWN;        shot 19-grid-moved 0.6;           focus grid-moved

# --- Details and back to the same poster ---
key DPAD_CENTER;                            shot 20-details 2.5
key BACK;                                   shot 21-back-same-poster 1.2;     focus back-from-details

# --- Left from the first column opens the menu; TV Shows from the menu ---
key DPAD_LEFT DPAD_LEFT DPAD_LEFT;          shot 22-left-opens-menu 0.6
key DPAD_DOWN;                              shot 23-menu-tv-focus 0.3
key DPAD_CENTER;                            shot 24-tv-first-poster 1.2;      focus tv-enter
key DPAD_DOWN DPAD_RIGHT;                   shot 25-tv-moved 0.6;             focus tv-moved
key DPAD_UP;                                shot 26-tv-up 0.6;                focus tv-up

# --- Back from the background: new titles appear, focus and position stay ---
# (Over a minute after the launch refresh; the demo server has one new movie from its second download.)
key DPAD_LEFT DPAD_LEFT;                    shot 27-menu-from-tv 0.5
key DPAD_UP DPAD_CENTER;                    sleep 1.5
key DPAD_RIGHT DPAD_RIGHT;                  shot 28-movies-before-background 0.6; focus before-background
adb shell input keyevent KEYCODE_HOME;      sleep 4
shot 29-launcher 0.2
resume;                                     shot 30-returned 0.6;             focus returned
sleep 3;                                    shot 31-after-refresh 0.3;        focus after-refresh
key DPAD_LEFT DPAD_LEFT DPAD_LEFT;          shot 32-new-title-first 0.6;      focus new-title-first

# --- Settings (menu is on Movies: Series, My List, Settings) ---
key DPAD_LEFT;                              shot 33-menu-from-movies 0.5
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; shot 34-settings 1.2;          focus settings-enter
key DPAD_DOWN DPAD_DOWN DPAD_DOWN;          shot 35-settings-autoplay 0.6;    focus settings-autoplay
key DPAD_CENTER;                            shot 36-autoplay-off 0.5
key DPAD_DOWN DPAD_CENTER;                  shot 37-audio-picker 0.8
key DPAD_DOWN DPAD_DOWN DPAD_CENTER;        shot 38-audio-spanish 0.8
key DPAD_DOWN DPAD_CENTER;                  shot 39-subtitles-on 0.5
key DPAD_DOWN DPAD_CENTER;                  shot 40-subtitle-language-picker 0.8
key DPAD_DOWN DPAD_CENTER;                  shot 41-subtitles-english 0.8
key DPAD_DOWN;                              shot 42-settings-lower 0.6;       focus settings-lower
key DPAD_CENTER;                            shot 43-update-check 3
key DPAD_CENTER;                            shot 44-update-dialog-closed 0.6
key DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP; shot 45-image-cache-row 0.5
key DPAD_CENTER;                            shot 46-image-cache-cleared 1.5
key DPAD_UP DPAD_CENTER;                    shot 47-refresh-checking 0.4
sleep 3;                                    shot 48-refresh-done 0.2
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 49-sign-out-row 0.6
key DPAD_CENTER;                            shot 50-sign-out-confirm 0.6
key DPAD_CENTER;                            shot 51-sign-out-cancelled 0.6;   focus sign-out-cancelled

# --- Preferences reach the player (Spanish audio, English subtitles, autoplay off) ---
key BACK;                                   shot 52-home-from-settings 1.2
key DPAD_DOWN DPAD_CENTER;                  shot 53-details-for-playback 2.5
key DPAD_CENTER;                            sleep 14
key DPAD_UP;                                shot 54-player-preferences 0.8
key BACK BACK;                              shot 55-back-from-player 1.2
key BACK;                                   shot 56-home-after-player 1.2

# --- Fresh launch with cached titles (no long loading screen) ---
launch;                                     shot 57-launch-loading 0.2
sleep 1.5;                                  shot 58-launch-home 0.3;          focus launch-home

# --- Sign out for real (menu opens on Home: Movies, TV Shows, My List, Settings) ---
key DPAD_LEFT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 1.2
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN
shot 59-sign-out-row 0.4;                   focus sign-out-row
key DPAD_CENTER DPAD_RIGHT;                 shot 60-sign-out-focus 0.4
key DPAD_CENTER;                            shot 61-signed-out-login 3

# --- Signing in again to an expired account shows renewal ---
launch --ez demo_expired true;              sleep 28
shot 62-renewal 0.3

adb pull /sdcard/qa/. "$OUT/" > /dev/null
adb logcat -d -b crash > qa/crash-log.txt || true
adb logcat -d | grep -E "FlixTown|AndroidRuntime|FATAL" | tail -n 400 > qa/app-log.txt || true
ls "$OUT" | wc -l
