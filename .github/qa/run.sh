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
focus(){ echo "$1: $(adb shell dumpsys window | grep -m1 mCurrentFocus)" >> qa/focus.txt; }

# --- Login (clean install) ---
launch --ez demo_reset true
shot 01-login-qr 4
key DPAD_CENTER;                            shot 02-login-remote
key DPAD_DOWN DPAD_DOWN;                    shot 03-login-remote-signin-focus
key BACK;                                   shot 04-login-back-to-qr
sleep 12;                                   shot 05-first-launch-loading 0.1
sleep 6;                                    shot 06-home 0.5

# --- Home ---
key DPAD_DOWN;                              shot 07-home-first-row-focus
key DPAD_RIGHT DPAD_RIGHT;                  shot 08-home-row-right
key DPAD_DOWN;                              shot 09-home-second-row
key DPAD_DOWN;                              shot 10-home-third-row
key BACK;                                   shot 11-home-back-to-hero
key BACK;                                   shot 12-exit-dialog-stay-focused
key DPAD_RIGHT;                             shot 13-exit-dialog-exit-focused
key DPAD_LEFT DPAD_CENTER;                  shot 14-exit-stay
key DPAD_LEFT;                              shot 15-menu-open
key DPAD_DOWN;                              shot 16-menu-movies-focus
key DPAD_CENTER;                            shot 17-movies-grid-first-poster 1.5

# --- Movies grid ---
key DPAD_RIGHT DPAD_RIGHT DPAD_DOWN;        shot 18-grid-focus-row2
key DPAD_UP DPAD_UP;                        shot 19-grid-filters-focus
key DPAD_CENTER;                            shot 20-sort-picker
key DPAD_DOWN DPAD_CENTER;                  shot 21-sorted-title
key DPAD_RIGHT DPAD_CENTER;                 shot 22-category-picker
key DPAD_DOWN DPAD_CENTER;                  shot 23-category-action
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT; shot 24-grid-scrolled-header
key DPAD_CENTER;                            shot 25-movie-details 3
key DPAD_DOWN;                              shot 26-details-cast-focus
key DPAD_RIGHT DPAD_RIGHT;                  shot 27-details-cast-right
key DPAD_DOWN;                              shot 28-details-more-like-this
key DPAD_RIGHT;                             shot 29-details-more-right
key DPAD_UP DPAD_UP;                        shot 30-details-back-to-actions
key BACK;                                   shot 31-back-to-grid-same-poster 1.2
key BACK;                                   shot 32-back-to-home

# --- Player ---
key DPAD_DOWN DPAD_CENTER;                  shot 33-home-row-details 3
key DPAD_CENTER;                            shot 34-player-starting 2
sleep 12;                                   shot 35-player-playing-clean 0.1
key DPAD_UP;                                shot 36-player-controls 0.3
key DPAD_RIGHT;                             shot 37-player-audio-focus 0.3
key DPAD_CENTER;                            shot 38-audio-menu 0.6
key BACK;                                   shot 39-audio-menu-closed 0.3
key DPAD_RIGHT DPAD_CENTER;                 shot 40-subtitles-menu 0.6
key DPAD_DOWN DPAD_DOWN DPAD_CENTER;        shot 41-subtitle-selected 1.5
sleep 4;                                    shot 42-controls-auto-hidden 0.1
key DPAD_RIGHT;                             shot 43-seek-plus-10 0.1
key DPAD_RIGHT DPAD_RIGHT;                  shot 44-seek-plus-30 0.1
sleep 4.5;                                  shot 45-after-seek-hidden 0.1
key DPAD_CENTER;                            shot 46-ok-pauses 0.3
sleep 4;                                    shot 47-paused-overlay-hidden-after-3s 0.1
key DPAD_CENTER;                            shot 48-ok-resumes 1
key DPAD_UP;                                shot 49-controls-again 0.3
key BACK;                                   shot 50-back-hides-controls 0.3
key BACK;                                   shot 51-back-leaves-player 1.5
key DPAD_CENTER;                            shot 52-resume-choice 3
key DPAD_RIGHT;                             shot 53-resume-start-over-focus 0.3
key DPAD_LEFT DPAD_CENTER;                  shot 54-continued 5
key BACK;                                   shot 55-back-in-details 1.5
key BACK;                                   shot 56-home-continue-row 1.5

# --- TV show ---
key BACK;                                   shot 57-home-hero-before-menu 0.5
key DPAD_LEFT DPAD_DOWN DPAD_DOWN;          shot 58-menu-tv-shows-focus
key DPAD_CENTER;                            shot 59-tv-grid 1.5
key DPAD_CENTER;                            shot 60-series-details 3
key DPAD_DOWN;                              shot 61-season-button-focus
key DPAD_CENTER;                            shot 62-season-picker
key DPAD_DOWN DPAD_CENTER;                  shot 63-season-2
key DPAD_DOWN DPAD_RIGHT;                   shot 64-episode-focus
key DPAD_DOWN;                              shot 65-series-cast
key DPAD_UP DPAD_CENTER;                    shot 66-episode-player 10
key BACK;                                   shot 67-back-to-series 1.5
key BACK BACK;                              shot 68-back-home 1

# --- Search and Settings ---
key DPAD_LEFT DPAD_UP;                      shot 69-menu-search-focus
key DPAD_CENTER;                            shot 70-search-no-keyboard
key DPAD_LEFT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 71-menu-settings-focus
key DPAD_CENTER;                            shot 72-settings

# --- Fresh launch with cached titles: loading screen, then Home ---
launch;                                     shot 73-launch-loading 0.2
sleep 3;                                    shot 74-launch-home 0.1
# --- Offline with saved titles: goes straight to saved titles ---
launch --ez demo_offline true;              shot 75-offline-with-cache 4
# --- Offline with nothing saved: offline message, never stuck ---
launch --ez demo_offline true --ez demo_clear_cache true; shot 76-offline-no-cache 5
key DPAD_RIGHT DPAD_CENTER;                 shot 77-offline-continue 1
# --- Slow server with nothing saved ---
launch --ez demo_slow true --ez demo_clear_cache true; shot 78-slow-server-waiting 4
sleep 13;                                   shot 79-slow-server-message 0.2
sleep 6;                                    shot 80-slow-server-finished 0.2

# --- Expired account ---
launch --ez demo_expired true;              shot 81-renewal 6
key DPAD_UP DPAD_UP;                        shot 82-renewal-plans-focus
key DPAD_CENTER;                            shot 83-renewal-plan-selected 1.5
key BACK;                                   shot 84-renewal-back 1

adb pull /sdcard/qa/. "$OUT/" > /dev/null
adb logcat -d -b crash > qa/crash-log.txt || true
adb logcat -d | grep -E "FlixTown|AndroidRuntime|FATAL" | tail -n 400 > qa/app-log.txt || true
if command -v convert >/dev/null; then for f in "$OUT"/*.png; do convert "$f" -quality 82 "${f%.png}.jpg" && rm "$f"; done; fi
ls "$OUT" | wc -l
