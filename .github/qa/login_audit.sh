#!/usr/bin/env bash
# Focused check for the production audit fixes (QA build, offline demo server):
#  1. an expired QR code is replaced by a fresh one and pairing still completes;
#  2. signing in with a DIFFERENT account removes the previous account's Continue Watching / progress;
#     signing back in to the SAME account keeps them (the existing behaviour).
# Results: qa/login-audit/report.txt and screens.
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=${QA_OUT:-qa/login-audit}; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am force-stop $PKG; adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
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
prefs(){ adb shell "run-as $PKG cat shared_prefs/flix.xml" 2>/dev/null; }
progress_keys(){ prefs | grep -cE 'name="(continue_ids|resume_[a-z_]*)' ; }
continue_ids(){ prefs | grep -o 'name="continue_ids">[^<]*' | sed 's/.*>//'; }
signed_in_as(){ key DPAD_LEFT; focus_to DPAD_UP "Search" 8 >/dev/null; focus_to DPAD_DOWN "Settings" 8 >/dev/null; key DPAD_CENTER; sleep 2
  dump; grep -o 'Signed in as [^. "]*' "$OUT/ui.xml" | head -1; }
sign_out(){ signed_in_as > /dev/null; focus_to DPAD_DOWN "Sign out" 12 > /dev/null || note "INFO  Sign out row not focused: $(focused)"
  key DPAD_CENTER; sleep 1; focus_to DPAD_RIGHT "Sign out" 2 > /dev/null; key DPAD_CENTER; wait_activity LoginActivity 15; }
remote_sign_in(){ focus_to DPAD_DOWN "Sign in with remote" 4 > /dev/null; key DPAD_CENTER; sleep 1
  key DPAD_CENTER; sleep 1; adb shell input text "$1"; sleep 0.5; key ENTER; sleep 0.8; adb shell input text "$2"; sleep 0.5; key ENTER
  wait_activity HomeActivity 40; sleep 6; }

adb install -r "$APK" > /dev/null || { bad "install"; exit 1; }
adb shell settings put system screen_off_timeout 1800000 || true

# ---------------- 1. Expired QR code ----------------
launch --ez demo_reset true --ez demo_qr_expire true
wait_for "FT4K2Q" 20 && ok "QR: first code shown (FT4K2Q)" || bad "QR: first code not shown"
shot 01-first-code 0.2
wait_for "FT7N3R" 30 && ok "QR: the expired code was replaced by a fresh one (FT7N3R)" || bad "QR: no fresh code after expiry ($(resumed))"
shot 02-fresh-code 0.2
wait_activity HomeActivity 45 && ok "QR: approval of the fresh code opens Home" || bad "QR: fresh code did not reach Home ($(resumed))"
sleep 6; who="$(signed_in_as)"
check "QR: signed in as the approved account ($who)" '[ "$who" = "Signed in as 0048213977" ]'
key BACK; sleep 1

# ---------------- 2. Account switch ----------------
launch --ez demo_continue true; wait_activity HomeActivity 45; sleep 6   # A: progress for a show and a movie
a_ids="$(continue_ids)"; note "INFO  account A Continue Watching: $a_ids"
check "account A has Continue Watching progress" '[ -n "$a_ids" ] && [ "$(progress_keys)" -ge 2 ]'
sign_out
check "after signing out, the login screen is shown" 'in_activity LoginActivity'
check "signing out alone keeps this TV's progress (as before)" '[ "$(continue_ids)" = "$a_ids" ]'
remote_sign_in 0012345678 0000123456789012
check "account B signed in (Home)" 'in_activity HomeActivity'
shot 03-home-account-b 0.3
check "account B does not get account A's Continue Watching or progress" '[ -z "$(continue_ids)" ] && [ "$(progress_keys)" = 0 ]'
check "account B's Home shows no Continue Watching row" '! has "Continue Watching"'
who="$(signed_in_as)"; check "Settings shows account B ($who)" '[ "$who" = "Signed in as 0012345678" ]'
key BACK; sleep 1
# Same account again: its own progress stays.
launch --ez demo_continue true; wait_activity HomeActivity 45; sleep 6
b_ids="$(continue_ids)"
sign_out
remote_sign_in 0012345678 0000123456789012
check "signing back in to the same account keeps its progress ($b_ids)" '[ -n "$b_ids" ] && [ "$(continue_ids)" = "$b_ids" ]'
crashes="$(adb logcat -d -b crash | grep -A1 'FATAL EXCEPTION' | grep -c "Process: $PKG")"
check "no Flix Town crash" '[ "$crashes" = 0 ]'
note ""; note "login audit: $PASS passed, $FAIL failed"
