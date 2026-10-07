#!/usr/bin/env bash
# Screensaver check on an Android TV emulator (QA build): with a 20 s screen timeout and the
# screensaver enabled, the TV must stay awake while Flix Town Home / Details / the player is in
# front, and must reach its own screensaver or sleep once Flix Town is in the background or closed.
# Uses Android's own state (dumpsys power, window flags), not screenshots alone.
# Results: qa/screen-awake/report.txt and qa/screen-awake/screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
OUT=${QA_OUT:-qa/screen-awake}; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
shot(){ sleep "${2:-0.5}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
resumed(){ adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -1; }
in_activity(){ resumed | grep -q "$1"; }
wait_activity(){ local t=0; while [ $t -lt "$2" ]; do in_activity "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
wakefulness(){ adb shell dumpsys power | grep -m1 -o "mWakefulness=[A-Za-z]*" | cut -d= -f2; }
# KEEP_SCREEN_ON on a Flix Town window: prints "on" or "off" for the given activity (any if empty).
flag(){ adb shell dumpsys window windows | python3 -c '
import sys,re
want=sys.argv[1]; cur=None; state="off"
for line in sys.stdin:
    m=re.search(r"Window\{\S+ \S+ (\S+)\}",line)
    if "Window #" in line: cur=m.group(1) if m else None
    elif cur and cur.startswith("'"$PKG"'/") and (not want or cur.endswith("."+want)) and "KEEP_SCREEN_ON" in line:
        state="on"     # flags may be on the mAttrs line or its fl= continuation line
print(state)' "${1:-}"; }
# Idles for $1 seconds without touching the remote, then reports the power state.
idle(){ sleep "$1"; wakefulness; }

# Remember and set the emulator's power settings (restored at the end).
old_timeout="$(adb shell settings get system screen_off_timeout | tr -d '\r')"
old_stay="$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')"
old_ss="$(adb shell settings get secure screensaver_enabled | tr -d '\r')"
old_sleep="$(adb shell settings get secure screensaver_activate_on_sleep | tr -d '\r')"
adb shell settings put global stay_on_while_plugged_in 0
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_activate_on_sleep 1
adb shell settings put system screen_off_timeout 20000
note "INFO  emulator: screen timeout 20 s, screensaver on, stay-awake-while-charging off"
IDLE=45

# 1. Idle browsing on Home
adb logcat -c
adb shell am force-stop $PKG
adb shell am start -W $LAUNCHER -f 0x10200000 -n "$PKG/com.flixtown.tv.LoginActivity" --ez demo_reset true > /dev/null
wait_activity HomeActivity 60 && ok "Home opened" || bad "Home did not open ($(resumed))"
sleep 6
check "Home: keep-screen-on flag set" '[ "$(flag HomeActivity)" = on ]'
w="$(idle $IDLE)"; [ "$w" = Awake ] && ok "Home: still awake after ${IDLE}s idle (timeout 20s)" || bad "Home: $w after ${IDLE}s idle"
shot 01-home-after-idle
key DPAD_LEFT DPAD_DOWN DPAD_CENTER; sleep 3                        # Movies tab (same window)
w="$(idle $IDLE)"; [ "$w" = Awake ] && ok "Movies tab: still awake after ${IDLE}s idle" || bad "Movies tab: $w"
shot 02-movies-after-idle

# 2. Details, playback, and returning from playback
key DPAD_RIGHT DPAD_CENTER
wait_activity DetailsActivity 10 && ok "Details opened" || bad "Details did not open ($(resumed))"
sleep 3
check "Details: keep-screen-on flag set" '[ "$(flag DetailsActivity)" = on ]'
w="$(idle $IDLE)"; [ "$w" = Awake ] && ok "Details: still awake after ${IDLE}s idle" || bad "Details: $w"
key DPAD_CENTER                                                       # Play
wait_activity PlayerActivity 15 && ok "player opened" || bad "player did not open ($(resumed))"
sleep 3
check "player: keep-screen-on flag set" '[ "$(flag PlayerActivity)" = on ]'
w="$(idle 30)"; [ "$w" = Awake ] && ok "player: still awake after 30s without remote input" || bad "player: $w"
shot 03-player-after-idle
for i in 1 2 3; do in_activity PlayerActivity || break; key BACK; sleep 1; done
wait_activity DetailsActivity 8 && ok "Back from the player returns to Details" || bad "after player: $(resumed)"
check "after playback: Details flag set again" '[ "$(flag DetailsActivity)" = on ]'
check "after playback: player window gone or flag cleared" '[ "$(flag PlayerActivity)" = off ]'
w="$(idle $IDLE)"; [ "$w" = Awake ] && ok "after playback, Details: still awake after ${IDLE}s idle" || bad "after playback, Details: $w"
key BACK
wait_activity HomeActivity 8 && ok "Back returns to Home" || bad "after Details: $(resumed)"
w="$(idle $IDLE)"; [ "$w" = Awake ] && ok "after playback, Home: still awake after ${IDLE}s idle" || bad "after playback, Home: $w"
shot 04-home-after-playback-idle

# 3. Background: the TV's own screensaver / sleep must take over
key HOME; sleep 3
check "backgrounded: Flix Town is not in front" '! in_activity "$PKG/"'
check "backgrounded: keep-screen-on cleared on every Flix Town window" '[ "$(flag)" = off ]'
w="$(idle $IDLE)"; [ "$w" != Awake ] && ok "backgrounded: TV reached its screensaver/sleep ($w)" || bad "backgrounded: still $w after ${IDLE}s"

# 4. Reopen from the launcher: flag back on, stays awake
adb shell input keyevent KEYCODE_WAKEUP; sleep 2
adb shell am start -W $LAUNCHER -f 0x10200000 -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null
wait_activity HomeActivity 30 && ok "reopened: Home in front" || bad "reopened: $(resumed)"
sleep 4
check "reopened: keep-screen-on flag set again" '[ "$(flag HomeActivity)" = on ]'
w="$(idle $IDLE)"; [ "$w" = Awake ] && ok "reopened: still awake after ${IDLE}s idle" || bad "reopened: $w"
shot 05-home-reopened-idle

# 5. Closed: same as background
adb shell am force-stop $PKG; sleep 2
w="$(idle $IDLE)"; [ "$w" != Awake ] && ok "closed: TV reached its screensaver/sleep ($w)" || bad "closed: still $w after ${IDLE}s"
adb shell input keyevent KEYCODE_WAKEUP

adb logcat -d -s FlixTownQA:I | grep "screen_awake" | sed 's/^/INFO  /' | tail -40 >> "$REPORT"
adb logcat -d | grep -E "FATAL EXCEPTION" -A 12 > "$OUT/crash-log.txt" || true
check "no crash during the run" '! grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt"'

adb shell settings put system screen_off_timeout "${old_timeout:-1800000}"
[ "$old_stay" = null ] || adb shell settings put global stay_on_while_plugged_in "$old_stay"
[ "$old_ss" = null ] || adb shell settings put secure screensaver_enabled "$old_ss"
[ "$old_sleep" = null ] || adb shell settings put secure screensaver_activate_on_sleep "$old_sleep"
note "screen awake: $PASS passed, $FAIL failed"
