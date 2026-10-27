#!/usr/bin/env bash
# Workflow: .github/workflows/notice-relogin.yml (QA build, offline demo server; nothing is published).
# Resolved playback-report notices across sign-out and sign-in:
#   Dismiss -> sign out -> the same account signs in again (remote and QR): the notice stays dismissed;
#   another account on the TV still gets its own notice (nothing inherited) and its sign-in does not
#   undo the first account's Dismiss; Later keeps a notice for the next visit; a new resolution appears.
# Results: $OUT/report.txt
set -u
PKG=com.myflixtown.tv.native.qa
APK=${APK:-app/build/outputs/apk/qa/app-qa.apk}
OUT=${QA_OUT:-qa/notice-relogin}; rm -rf "$OUT"; mkdir -p "$OUT/screens"
REPORT="$OUT/report.txt"; : > "$REPORT"
PASS=0; FAIL=0
note(){ echo "$*" | tee -a "$REPORT"; }
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
shot(){ adb shell screencap -p /sdcard/n.png; adb pull /sdcard/n.png "$OUT/screens/$1.png" > /dev/null; }
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
press(){ key DPAD_LEFT DPAD_LEFT DPAD_LEFT; focus_to DPAD_RIGHT "$1" 4 && key DPAD_CENTER; }
demo_live(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; }
# What the TV keeps about notices (for the report; values only, no credentials)
acks(){ adb shell "run-as $PKG sh -c 'cat shared_prefs/flix.xml shared_prefs/notice_acks_v1.xml 2>/dev/null'" | grep -oE 'name="(notice_seen_[^"]*|[0-9a-f]{8}[^"]*\|[^"]*)"|name="account_owner">[0-9a-f]{12}' | tr '\n' ' '; }
settings(){ key DPAD_LEFT; focus_to DPAD_UP "Search" 8 >/dev/null; focus_to DPAD_DOWN "Settings" 8 >/dev/null; key DPAD_CENTER; sleep 2; }
sign_out(){ settings; focus_to DPAD_DOWN "Sign out" 14 > /dev/null || note "INFO  Sign out row not focused: $(focused)"
  key DPAD_CENTER; sleep 1; focus_to DPAD_RIGHT "Sign out" 2 > /dev/null; key DPAD_CENTER; wait_activity LoginActivity 15; }
remote_sign_in(){ focus_to DPAD_DOWN "Sign in with remote" 4 > /dev/null; key DPAD_CENTER; sleep 1
  key DPAD_CENTER; sleep 1; adb shell input text "$1"; sleep 0.5; key ENTER; sleep 0.8; adb shell input text "$2"; sleep 0.5; key ENTER
  wait_activity HomeActivity 40; }
# Home's notice check runs 1.5 s after Home settles; allow for the catalog load on a slow emulator
notice_shown(){ wait_for "marked resolved" "${1:-25}"; }
notice_absent(){ sleep "${1:-14}"; ! has "marked resolved"; }
title_shown(){ grep -q -- "$1" "$OUT/ui.xml"; }

A_USER=0048213977; A_PASS=0000731946205518     # the demo QR account
B_USER=0012345678; B_PASS=0000123456789012
R42='{"id":"42","type":"movie","media_id":1000,"title":"Resolved forty-two"}'
R43='{"id":"43","type":"movie","media_id":1001,"title":"Resolved forty-three"}'
live(){ demo_live "{\"customer_notices\":true,\"notice_requests\":[],${2:+\"hold_qr\":true,}\"resolved_reports\":[$1]}"; }

adb install -r "$APK" > /dev/null || { bad "install"; exit 1; }
adb shell settings put system screen_off_timeout 1800000 || true
launch --ez demo_reset true
wait_activity HomeActivity 60 && ok "A signed in (QR) and Home opened" || bad "A did not reach Home ($(resumed))"

# 1. A dismisses report 42
live "$R42" hold; launch; wait_activity HomeActivity 40
notice_shown && ok "A: resolved report 42 is shown" || bad "A: report 42 not shown"; shot 01-a-notice
press Dismiss; sleep 2; ! has "marked resolved" && ok "A: Dismiss closes it" || bad "A: Dismiss did not close it"
note "INFO  after Dismiss: $(acks)"
launch; wait_activity HomeActivity 40
notice_absent && ok "A: still dismissed after restarting the app" || bad "A: shown again after restart"

# 2. Sign out, same account signs in with the remote
sign_out; note "INFO  after sign-out: $(acks)"
remote_sign_in $A_USER $A_PASS; note "INFO  after A signs in (remote): $(acks)"
in_activity HomeActivity && ok "A signed in again with the remote" || bad "A remote sign-in ($(resumed))"
notice_absent && ok "A: Dismiss survives sign-out and same-account sign-in (remote)" || { bad "A: dismissed notice returned after remote sign-in"; shot 02-a-returned; press Later; }

# 3. Sign out, same account signs in with the QR code
live "$R42"; sign_out; wait_activity HomeActivity 45; note "INFO  after A signs in (QR): $(acks)"
in_activity HomeActivity && ok "A signed in again with the QR code" || bad "A QR sign-in ($(resumed))"
notice_absent && ok "A: Dismiss survives sign-out and same-account sign-in (QR)" || { bad "A: dismissed notice returned after QR sign-in"; shot 03-a-returned-qr; press Later; }

# 4. Another account on this TV: gets its own notice, inherits nothing
live "$R42" hold; sign_out; remote_sign_in $B_USER $B_PASS; note "INFO  after B signs in: $(acks)"
in_activity HomeActivity && ok "B signed in with the remote" || bad "B sign-in ($(resumed))"
notice_shown && ok "B: report 42 is unread for B (A's Dismiss is not inherited)" || bad "B: inherited A's Dismiss"; shot 04-b-notice

# 5. Later keeps it for the next visit
press Later; sleep 2; ! has "marked resolved" && ok "B: Later closes it" || bad "B: Later did not close it"
launch; wait_activity HomeActivity 40
notice_shown && ok "B: after Later the notice comes back on the next visit" || bad "B: Later lost the notice"
press Dismiss; sleep 2

# 6. Back to A: B's sign-in did not undo A's Dismiss
sign_out; remote_sign_in $A_USER $A_PASS; note "INFO  after A signs in after B: $(acks)"
notice_absent && ok "A: Dismiss survives another account signing in on this TV" || { bad "A: dismissed notice returned after B used the TV"; press Later; }

# 7. A genuinely new resolution appears
live "$R42,$R43" hold; launch; wait_activity HomeActivity 40
notice_shown && { dump; title_shown "Resolved forty-three" && ok "A: a new resolution (report 43) is shown" || bad "A: wrong notice shown ($(grep -o 'Resolved forty-[a-z]*' "$OUT/ui.xml" | head -1))"; } || bad "A: new resolution not shown"
shot 05-a-new; press Dismiss; sleep 2
notice_absent 6 && ok "A: only the new resolution was shown (42 stays dismissed)" || bad "A: 42 shown again with 43"

crash="$(adb logcat -d -b crash | grep -c "Process: $PKG,")"; check "no Flix Town crash" '[ "$crash" = 0 ]'
note ""; note "notice re-login: $PASS passed, $FAIL failed"
