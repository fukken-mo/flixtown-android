#!/usr/bin/env bash
# Real-network startup check: a normal (non-demo) build of the app, signed in through the real
# login form, loading its catalog over real HTTP from mock_flix.py on the host (10.0.2.2:8790).
# Each scenario is "<renewal>:<catalog>" (see mock_flix.py): renewal none|ok|500|timeout|unreachable,
# catalog ok|slow|fail1|fail3|down|signout|cached. It proves Movies and TV Shows actually fill after a
# fresh sign-in, that a failed catalog is retried quietly (10 s, 20 s, 40 s, then every 2 min) until
# it arrives, with no duplicate downloads, no repeated popups, no retries with saved titles or after
# signing out, and that the renewal service (working or broken) never disturbs the catalog.
# Usage: real_startup.sh <apk> <label> <scenarios...>   Results: qa/real-startup/<label>/report.txt
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

MOCK=http://localhost:8790
vod(){ curl -s $MOCK/__stats | python3 -c 'import json,sys;print(json.load(sys.stdin)["vod"])'; }
retries(){ adb logcat -d -s FlixTown:I | grep -c "Catalog retry"; }
updated(){ adb logcat -d -s FlixTown:I | grep -q "Refresh finished: updated"; }
wait_updated(){ local t=0; while [ $t -lt "$1" ]; do updated && return 0; sleep 2; t=$((t+2)); done; updated; }
app_pid(){ adb shell pidof $PKG | tr -d '\r'; }
sign_in(){
  adb shell am start -W -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null
  wait_for "remote_sign_in" 20 || note "INFO  login screen slow"
  focus_to DPAD_DOWN "Sign in with remote" 4 >/dev/null; key DPAD_CENTER; sleep 1.5
  # Fields are filled by tapping them (by resource id): exact on every Android version.
  tap_id username; adb shell input text 0012345678; sleep 1
  tap_id password; adb shell input text 0000111122223333; sleep 1
  check "[$SC] form filled (username field holds only the username)" 'dump; grep -q "text=\"0012345678\"" "$OUT/ui.xml"'
  START=$(date +%s); key ENTER
  wait_activity HomeActivity 40 && ok "[$SC] sign-in accepted, Home opened" || bad "[$SC] Home not opened ($(resumed))"
}
library(){   # Movies and TV Shows really populated
  check "[$SC] Home shows titles" 'has "Mock Movie\|Mock Series"'
  shot "$SC-home"
  menu "Movies"; local n=$(count_titles "Mock Movie")
  check "[$SC] Movies populated ($n posters on screen)" '[ "$n" -ge 4 ]'
  shot "$SC-movies"
  menu "TV Shows"; n=$(count_titles "Mock Series")
  check "[$SC] TV Shows populated ($n posters on screen)" '[ "$n" -ge 4 ]'
  shot "$SC-series"
}
continue_past_message(){   # the one "couldn't reach" message after the first failure: choose Continue
  if wait_for "couldn.t reach Flix Town" 30; then ok "[$SC] first failure: the usual message, once"; shot "$SC-message"
    focus_to DPAD_RIGHT "Continue" 3 >/dev/null; key DPAD_CENTER; sleep 1
  else note "INFO  [$SC] message already gone (a retry succeeded first)"; fi
}

adb install -r "$APK" > /dev/null || { note "FAIL  install"; exit 1; }
adb shell settings put system screen_off_timeout 1800000 || true
for SC in $MODES; do
  RENEWAL=${SC%%:*}; CATALOG=${SC#*:}; [ "$CATALOG" = "$SC" ] && CATALOG=ok
  note ""; note "== $LABEL · renewal API: $RENEWAL · catalog: $CATALOG"
  m=$CATALOG; [ "$m" = signout ] && m=down; [ "$m" = cached ] && m=ok
  echo "$RENEWAL $m" > "$MODEFILE"; curl -s $MOCK/__reset > /dev/null
  adb shell pm clear $PKG > /dev/null; adb logcat -c; adb logcat -b crash -c 2>/dev/null || true
  sign_in
  case $CATALOG in
  ok)
    wait_updated 60; note "      catalog arrived $(( $(date +%s) - START )) s after sign-in"
    check "[$SC] startup refresh finished: updated" 'updated'
    sleep 3
    check "[$SC] no 'taking longer than usual' message" '! has "taking longer than usual"'
    library
    check "[$SC] exactly one catalog download, no retries" '[ "$(vod)" = 1 ] && [ "$(retries)" = 0 ]';;
  slow)
    wait_for "taking longer than usual" 30 && ok "[$SC] slow server (>15 s): the usual 'taking longer' message" || bad "[$SC] slow message not shown"
    shot "$SC-slow-message"
    wait_updated 60; note "      catalog arrived $(( $(date +%s) - START )) s after sign-in (no key pressed)"
    check "[$SC] catalog arrived and the loading screen closed by itself" 'updated && sleep 2 && ! has "taking longer than usual"'
    library
    check "[$SC] one catalog download, no retries (a slow answer is not a failure)" '[ "$(vod)" = 1 ] && [ "$(retries)" = 0 ]';;
  fail1|fail3)
    n=${CATALOG#fail}; pid=$(app_pid)
    continue_past_message
    wait_updated 150; note "      catalog arrived $(( $(date +%s) - START )) s after sign-in"
    check "[$SC] retried quietly and the catalog arrived" 'updated'
    check "[$SC] $n retries, $((n+1)) catalog downloads in total (no duplicates)" '[ "$(retries)" = "$n" ] && [ "$(vod)" = "$((n+1))" ]'
    sleep 2
    check "[$SC] no repeated popups" '! has "couldn.t reach Flix Town" && ! has "taking longer than usual"'
    check "[$SC] same app process (no restart needed)" '[ "$(app_pid)" = "$pid" ]'
    library;;
  down)
    continue_past_message; t0=$(date +%s)
    sleep 200
    v=$(vod); r=$(retries); note "      catalog unavailable for $(( $(date +%s) - t0 )) s after Continue: $v downloads, $r retries scheduled"
    check "[$SC] retries at ~10/20/40 s then every 2 min: 4-6 downloads in 200 s (no storm)" '[ "$v" -ge 4 ] && [ "$v" -le 6 ]'
    check "[$SC] no repeated popups while retrying" '! has "couldn.t reach Flix Town"'
    shot "$SC-still-down"
    menu "Settings"
    check "[$SC] Home and Settings stay usable while retrying" 'has "Sign out" && in_activity HomeActivity'
    menu "Home"
    echo "$RENEWAL ok" > "$MODEFILE"; note "      server back"
    wait_updated 150; note "      catalog arrived $(( $(date +%s) - t0 )) s after Continue"
    check "[$SC] the catalog arrives on the next retry once the server is back" 'updated'
    sleep 2; library;;
  signout)
    continue_past_message; sleep 14
    menu "Settings"; focus_to DPAD_DOWN "Sign out" 16 >/dev/null; key DPAD_CENTER; sleep 1
    focus_to DPAD_RIGHT "Sign out" 2 >/dev/null; key DPAD_CENTER
    wait_activity LoginActivity 10 && ok "[$SC] signed out" || bad "[$SC] sign-out did not reach the sign-in screen"
    before=$(vod); sleep 80; after=$(vod)
    check "[$SC] no catalog retries after signing out ($before -> $after downloads in 80 s)" '[ "$before" = "$after" ]';;
  cached)
    wait_updated 60; check "[$SC] first sign-in loaded the catalog (now saved on the TV)" 'updated'
    echo "$RENEWAL down" > "$MODEFILE"; curl -s $MOCK/__reset > /dev/null
    adb shell am force-stop $PKG; adb logcat -c
    adb shell am start -W -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null
    wait_activity HomeActivity 40; sleep 45
    check "[$SC] saved titles shown although the server is down" 'has "Mock Movie\|Mock Series"'
    check "[$SC] with saved titles: no retries (one normal refresh attempt only)" '[ "$(retries)" = 0 ] && [ "$(vod)" -le 1 ]'
    library;;
  esac
  if [ "$RENEWAL" != none ]; then
    menu "Settings"
    if has "Renew subscription"; then
      focus_to DPAD_DOWN "Renew subscription" 4 >/dev/null; key DPAD_CENTER
      wait_activity RenewalActivity 10
      # The service is broken on purpose: the screen must say so (after its own timeout), never crash.
      wait_for "Try again\|available right now\|couldn.t load\|load your plans" 45 && ok "[$SC] renewal screen reports the broken service" || bad "[$SC] renewal screen gave no answer"
      shot "$SC-renewal"
      key BACK; sleep 2
      menu "Movies"; n=$(count_titles "Mock Movie")
      check "[$SC] Movies still there after the renewal screen ($n)" '[ "$n" -ge 4 ]'
      check "[$SC] the renewal screen caused no catalog download" '[ "$(vod)" = 1 ]'
    else
      bad "[$SC] no Renew subscription row although renewal_api_url is set"
    fi
  fi
  check "[$SC] no crash" '[ "$(crashes)" = "0" ]'
done
note ""; note "$LABEL: $PASS passed, $FAIL failed"
