#!/usr/bin/env bash
# Settings › Account (Status / Expiration / Connections) on an Android TV emulator (QA build, offline
# demo server shaped like the Flix Town player_api user_info). The demo account is changed while the
# app stays open by writing files/demo_account.json with run-as (QA build is debuggable).
# Results: qa/account-page/report.txt and qa/account-page/screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=${QA_OUT:-qa/account-page}; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }

adb install -r "$APK" > /dev/null || exit 1
adb shell settings put system screen_off_timeout 1800000 || true
shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; echo "shot $1"; }
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
r=ET.parse(sys.argv[1]).getroot()
for n in r.iter('node'):
    if n.get('focused')=='true':
        for c in n.iter('node'):
            v=c.get('text') or c.get('content-desc')
            if v: print(v); sys.exit()
        print(''); sys.exit()
EOF
}
focus_to(){ local k=$1 want=$2 n=${3:-6}; for i in $(seq 1 $n); do focused | grep -q -- "$want" && return 0; key "$k"; done; focused | grep -q -- "$want"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; note "      at: $(resumed | grep -o '[A-Za-z]*Activity' | tail -1) card='$(card)' detail='$(detail)'"; fi; }
prefs(){ adb shell run-as $PKG cat shared_prefs/flix.xml 2>/dev/null; }
pref(){ prefs | python3 -c 'import sys,re
x=sys.stdin.read();k=sys.argv[1]
m=re.search(r"name=\""+k+r"\"(?: value=\"([^\"]*)\"\s*/>|>([^<]*)</string>|\s*/>)",x)
print("" if not m else (m.group(1) or m.group(2) or ""))' "$1"; }
# The demo server's account, changed while the app stays open.
server(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; note "      server now: $1"; }
# The Account card's spoken summary: "Subscription. Status Active. Expiration 12/31/2027. Connections 3."
card(){ dump; python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    d=n.get('content-desc') or ''
    if d.startswith('Subscription.'): print(d); break
EOF
}
detail(){ grep -o 'Signed in as [^"]*' "$OUT/ui.xml" | head -1; }
open_settings(){ key DPAD_LEFT; focus_to DPAD_UP "Search" 8 >/dev/null; focus_to DPAD_DOWN "Settings" 8 >/dev/null; key DPAD_CENTER; sleep 3; }
leave_settings(){ key DPAD_LEFT; focus_to DPAD_UP "Home" 8 >/dev/null; key DPAD_CENTER; sleep 2; }
# Opening Settings asks the server again; a check under 10 s old is not repeated, so wait past that.
reopen_settings(){ leave_settings; sleep 11; open_settings; }
to_card(){ focus_to DPAD_RIGHT "Subscription" 2 >/dev/null || focus_to DPAD_UP "Subscription" 6 >/dev/null; }
crashes(){ adb logcat -d -b crash | grep -c "FATAL EXCEPTION.*" ; }

adb logcat -c; adb logcat -b crash -c 2>/dev/null || true

# ---------------- 1 connection, active, dated (sign-in through QR) ----------------
launch --ez demo_reset true --ei demo_connections 1
wait_activity HomeActivity 45 && sleep 6
check "signed-in TV reached Home" 'in_activity HomeActivity'
open_settings
c="$(card)"; note "      card: $c"
check "1 connection: Status Active, Expiration 12/31/2027 (panel time), Connections 1" '[ "$c" = "Subscription. Status Active. Expiration 12/31/2027. Connections 1." ]'
check "the detail line still names the account and when it was checked" 'detail | grep -q "Signed in as 0048213977  ·  Updated"'
check "no Plan is shown (the backend has no plan-duration field)" '! grep -qw "Plan" "$OUT/ui.xml"'
check "connections saved from max_connections" '[ "$(pref account_max_connections)" = "1" ]'
shot 01-active-1-connection

# ---------------- connection count changes in OnePanel: 3 ----------------
server '{"connections":3}'
reopen_settings
c="$(card)"; note "      card: $c"
check "3 connections shown after reopening Settings (no sign-out)" '[ "$c" = "Subscription. Status Active. Expiration 12/31/2027. Connections 3." ]'
shot 02-active-3-connections

# ---------------- 10 connections and a new expiration date ----------------
server '{"connections":10,"exp":"1893499200"}'
reopen_settings
c="$(card)"; note "      card: $c"
check "10 connections and the changed expiration (01/01/2030)" '[ "$c" = "Subscription. Status Active. Expiration 01/01/2030. Connections 10." ]'
shot 03-active-10-connections-new-date

# ---------------- Never Expire (exp_date null) ----------------
server '{"connections":10,"exp":"never"}'
reopen_settings
c="$(card)"; note "      card: $c"
check "Never Expire shows Expiration: Never" '[ "$c" = "Subscription. Status Active. Expiration Never. Connections 10." ]'
check "Never Expire saved as no date (not a guessed one)" '[ -z "$(pref account_exp_date)" ]'
check "Never Expire is not treated as expired" 'in_activity HomeActivity && [ "$(pref expired)" != "true" ]'
shot 04-never-expire

# ---------------- network failure: last valid details stay ----------------
server '{"offline":true}'
reopen_settings
c="$(card)"; d="$(detail)"; note "      card: $c"; note "      detail: $d"
check "server unreachable: the last valid details stay (Active, Never, 10)" '[ "$c" = "Subscription. Status Active. Expiration Never. Connections 10." ]'
check "server unreachable: a subtle note says the refresh failed" 'echo "$d" | grep -q "Couldn.t refresh. Showing details from"'
check "server unreachable: the account is not marked expired and the app stays on Home" 'in_activity HomeActivity && [ "$(pref expired)" != "true" ] && [ "$(pref account_status)" = "Active" ]'
shot 05-network-failure
# Select "Check now" (the card is focused when Settings opens) while still offline.
to_card; key DPAD_CENTER; sleep 3
c="$(card)"
check "Check now while offline: no crash, details unchanged" 'in_activity HomeActivity && [ "$c" = "Subscription. Status Active. Expiration Never. Connections 10." ]'
# Back online: Check now picks the change up at once.
server '{"connections":3,"exp":"never"}'
to_card; key DPAD_CENTER; sleep 3
c="$(card)"; d="$(detail)"
check "Check now when back online: Connections 3 and the failure note is gone" '[ "$c" = "Subscription. Status Active. Expiration Never. Connections 3." ] && echo "$d" | grep -q "Updated"'
shot 06-check-now-back-online

# ---------------- app reopen (cold start): refreshed before Settings is opened ----------------
server '{"connections":1,"exp":"1830254400"}'
launch
wait_activity HomeActivity 45 && sleep 6
check "cold start refreshed the saved account (1 connection, dated) without signing in again" '[ "$(pref account_max_connections)" = "1" ] && [ "$(pref account_exp_date)" = "1830254400" ]'
open_settings
c="$(card)"; note "      card: $c"
check "after reopening the app Settings shows the new details" '[ "$c" = "Subscription. Status Active. Expiration 12/31/2027. Connections 1." ]'
shot 07-after-app-reopen

# ---------------- back from the background (normal refresh) ----------------
server '{"connections":3,"exp":"1830254400"}'
key HOME; sleep 62                     # the automatic refresh is skipped within a minute of the last one
adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null
wait_activity HomeActivity 30; sleep 6
check "returning from the background refreshed the account (3 connections)" '[ "$(pref account_max_connections)" = "3" ]'

# ---------------- expired in OnePanel ----------------
leave_settings 2>/dev/null; sleep 11
server '{"expired":true,"exp":"1759968000"}'
open_settings
wait_for "Your subscription has ended" 15 && ok "expired: opening Settings moves to the renewal screen" || bad "expired account stayed on $(resumed)"
check "expired: status saved as Expired from the server" '[ "$(pref account_status)" = "Expired" ]'
shot 08-expired-renewal

check "no crash during the whole run" '[ "$(crashes)" = "0" ]'
adb shell "run-as $PKG rm -f files/demo_account.json"
note ""; note "account page: $PASS passed, $FAIL failed"
