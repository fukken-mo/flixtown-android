#!/usr/bin/env bash
# Real-network startup check: a normal (non-demo) build of the app, signed in through the real
# login form, loading its catalog over real HTTP from mock_flix.py on the host (10.0.2.2:8790).
# For each renewal mode (none, ok, 500, timeout, unreachable) it proves Movies and TV Shows
# actually fill after a fresh sign-in, and that opening the renewal screen never disturbs them.
# Usage: real_startup.sh <apk> <label> <modes...>     Results: qa/real-startup/<label>/report.txt
set -u
APK=$1; LABEL=$2; shift 2; MODES="$*"
PKG=com.myflixtown.tv.native
OUT=qa/real-startup/$LABEL; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
MODEFILE=${MODEFILE:-/tmp/flix-mode}
shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
dump(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }
has(){ dump; grep -q -- "$1" "$OUT/ui.xml"; }
wait_for(){ local t=0; while [ $t -lt "$2" ]; do has "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
resumed(){ adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -1; }
in_activity(){ resumed | grep -q "$1"; }
wait_activity(){ local t=0; while [ $t -lt "$2" ]; do in_activity "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
focused(){ dump; python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    if n.get('focused')=='true':
        for c in n.iter('node'):
            v=c.get('text') or c.get('content-desc')
            if v: print(v); sys.exit()
        print(''); sys.exit()
EOF
}
focus_to(){ local k=$1 want=$2 n=${3:-6}; for i in $(seq 1 $n); do focused | grep -q -- "$want" && return 0; key "$k"; done; focused | grep -q -- "$want"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; note "      at: $(resumed | grep -o '[A-Za-z]*Activity' | tail -1) focus='$(focused)'"; fi; }
menu(){ key DPAD_LEFT; focus_to DPAD_UP "Search" 8 >/dev/null; focus_to DPAD_DOWN "$1" 8 >/dev/null; key DPAD_CENTER; sleep 3; }
count_titles(){ dump; grep -o "content-desc=\"$1 [0-9]*\"" "$OUT/ui.xml" | sort -u | wc -l; }
# Taps the view with this resource id (exact on every Android version).
tap_id(){ dump; local xy; xy="$(python3 -c '
import sys, re, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if n.get("resource-id","").endswith(":id/"+sys.argv[2]):
        x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds"))); print((x1+x2)//2,(y1+y2)//2); break
' "$OUT/ui.xml" "$1")"; [ -n "$xy" ] && adb shell input tap $xy; sleep 1
  return 0; }
crashes(){ adb logcat -d -b crash 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -c "$PKG" ; }

adb install -r "$APK" > /dev/null || { note "FAIL  install"; exit 1; }
adb shell settings put system screen_off_timeout 1800000 || true
for MODE in $MODES; do
  note ""; note "== $LABEL · renewal API: $MODE"
  echo "$MODE" > "$MODEFILE"
  adb shell pm clear $PKG > /dev/null; adb logcat -c; adb logcat -b crash -c 2>/dev/null || true
  adb shell am start -W -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null
  wait_for "remote_sign_in" 20 || note "INFO  login screen slow"
  focus_to DPAD_DOWN "Sign in with remote" 4 >/dev/null; key DPAD_CENTER; sleep 1.5
  # Fields are filled by tapping them (by resource id): exact on every Android version.
  tap_id username; adb shell input text 0012345678; sleep 1
  tap_id password; adb shell input text 0000111122223333; sleep 1
  check "[$MODE] form filled (username field holds only the username)" 'dump; grep -q "text=\"0012345678\"" "$OUT/ui.xml"'
  start=$(date +%s)
  key ENTER
  wait_activity HomeActivity 40 && ok "[$MODE] sign-in accepted, Home opened" || bad "[$MODE] Home not opened ($(resumed))"
  t=0; while [ $t -lt 60 ]; do adb logcat -d -s FlixTown:I | grep -q "Refresh finished" && break; sleep 1; t=$((t+1)); done
  fin="$(adb logcat -d -s FlixTown:I | grep "Refresh finished" | tail -1)"
  note "      startup refresh: ${fin:-none after 60 s} ($(( $(date +%s) - start )) s after sign-in)"
  check "[$MODE] startup refresh finished: updated" 'echo "$fin" | grep -q "updated"'
  sleep 3
  check "[$MODE] no 'taking longer than usual' message" '! has "taking longer than usual"'
  check "[$MODE] Home shows titles" 'has "Mock Movie\|Mock Series"'
  shot "$MODE-01-home"
  menu "Movies"; n=$(count_titles "Mock Movie")
  check "[$MODE] Movies populated ($n posters on screen)" '[ "$n" -ge 4 ]'
  shot "$MODE-02-movies"
  menu "TV Shows"; n=$(count_titles "Mock Series")
  check "[$MODE] TV Shows populated ($n posters on screen)" '[ "$n" -ge 4 ]'
  shot "$MODE-03-series"
  if [ "$MODE" != none ]; then
    menu "Settings"
    if has "Renew subscription"; then
      focus_to DPAD_DOWN "Renew subscription" 4 >/dev/null; key DPAD_CENTER
      wait_activity RenewalActivity 10
      # The service is broken on purpose: the screen must say so (after its own timeout), never crash.
      wait_for "Try again\|available right now\|couldn.t load\|load your plans" 45 && ok "[$MODE] renewal screen reports the broken service" || bad "[$MODE] renewal screen gave no answer"
      shot "$MODE-04-renewal"
      key BACK; sleep 2
      menu "Movies"; n=$(count_titles "Mock Movie")
      check "[$MODE] Movies still there after the renewal screen ($n)" '[ "$n" -ge 4 ]'
    else
      note "INFO  [$MODE] no Renew subscription row (this build has no in-app renewal)"
    fi
  fi
  check "[$MODE] no crash" '[ "$(crashes)" = "0" ]'
done
note ""; note "$LABEL: $PASS passed, $FAIL failed"
