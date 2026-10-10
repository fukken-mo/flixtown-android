#!/usr/bin/env bash
# In-app renewal with Cash App Pay on an Android TV emulator (QA build, offline demo server that
# answers like the OnePanel renewal API in panel/onepanel-tv-renewal). The demo account and the
# payment's progress are changed while the app stays open by writing files/demo_account.json.
# Results: $QA_OUT (default qa/renewal-flow)/report.txt and screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=${QA_OUT:-qa/renewal-flow}; SHOTS=$OUT/screens
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
check(){ if eval "$2"; then ok "$1"; else bad "$1"; note "      at: $(resumed | grep -o '[A-Za-z]*Activity' | tail -1) focus='$(focused)'"; fi; }
prefs(){ adb shell run-as $PKG cat shared_prefs/flix.xml 2>/dev/null; }
pref(){ prefs | python3 -c 'import sys,re
x=sys.stdin.read();k=sys.argv[1]
m=re.search(r"name=\""+k+r"\"(?: value=\"([^\"]*)\"\s*/>|>([^<]*)</string>|\s*/>)",x)
print("" if not m else (m.group(1) or m.group(2) or ""))' "$1"; }
server(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; note "      server now: $1"; }
card(){ dump; python3 - "$OUT/ui.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    d=n.get('content-desc') or ''
    if d.startswith('Subscription.'): print(d); break
PY
}
detail(){ focused; }
open_settings(){ key DPAD_LEFT; focus_to DPAD_UP "Search" 8 >/dev/null; focus_to DPAD_DOWN "Settings" 8 >/dev/null; key DPAD_CENTER; sleep 3; }
crashes(){ adb logcat -d -b crash | grep -c "FATAL EXCEPTION.*" ; }
qa(){ adb logcat -d -s FlixTownQA:I | grep " renewal " | tail -1; }
desc(){ grep -q "content-desc=\"$1\"" "$OUT/ui.xml"; }
text(){ grep -q "text=\"$1" "$OUT/ui.xml"; }
# Date (MM/dd/yyyy, panel timezone) for a Unix time, as the app shows it.
day(){ python3 -c 'import sys,datetime,zoneinfo;print(datetime.datetime.fromtimestamp(int(sys.argv[1]),zoneinfo.ZoneInfo("America/Denver")).strftime("%m/%d/%Y"))' "$1"; }
ACC='"connections":2,"plan":3,"exp":"1759968000"'

adb logcat -c; adb logcat -b crash -c 2>/dev/null || true

# ---------------- set up: signed in, then the line expires (OnePanel) ----------------
launch --ez demo_reset true
wait_activity HomeActivity 45 && sleep 6
server "{\"expired\":true,$ACC}"
launch
wait_for "Your subscription has ended" 45 && ok "expired account opens the renewal screen" || bad "renewal screen not shown ($(resumed))"
sleep 3; dump
check "it is the in-app renewal (not the old Cash App request form)" '! text "Send renewal request" && in_activity RenewalActivity'
check "account summary: Current plan 3 Months" 'desc "Current plan 3 Months"'
check "account summary: Expired on 10/08/2025" 'desc "Expired on 10/08/2025"'
check "account summary: Devices 2 (max_connections shown as Devices)" 'desc "Devices 2"'
check "plans and prices from the renewal service: 1/3/6/12 months, \$15/\$40/\$75/\$135" 'desc "1 Month \$15" && desc "3 Months \$40" && desc "6 Months \$75" && desc "12 Months \$135"'
check "no device-count choice offered (shown as account information only)" '! grep -qi "add device\|more devices\|devices:" "$OUT/ui.xml"'
check "customer wording never says Connection(s)" '! grep -qi "connection" "$OUT/ui.xml"'
check "no OnePanel or Square branding, no web page" '! grep -qi "onepanel\|square\|webview\|http" "$OUT/ui.xml"'
check "focus starts on the current plan (3 Months)" '[ "$(focused)" = "3 Months \$40" ]'
check "Pay button shows the selected plan's price (\$40)" 'text "Pay with Cash App Pay  ·  \$40"'
shot 01-renewal-expired

# ---------------- D-pad ----------------
key DPAD_RIGHT
check "Right: 6 Months, Pay shows \$75" '[ "$(focused)" = "6 Months \$75" ] && text "Pay with Cash App Pay  ·  \$75"'
key DPAD_RIGHT
check "Right: 12 Months \$135" '[ "$(focused)" = "12 Months \$135" ]'
key DPAD_RIGHT
check "Right at the last plan stays there" '[ "$(focused)" = "12 Months \$135" ]'
key DPAD_LEFT DPAD_LEFT DPAD_LEFT
check "Left x3: 1 Month, Pay shows \$15" '[ "$(focused)" = "1 Month \$15" ] && text "Pay with Cash App Pay  ·  \$15"'
key DPAD_LEFT
check "Left at the first plan stays there" '[ "$(focused)" = "1 Month \$15" ]'
key DPAD_DOWN
check "Down: Pay with Cash App Pay" 'focused | grep -q "Pay with Cash App Pay"'
key DPAD_UP
check "Up: back to the chosen plan (1 Month)" '[ "$(focused)" = "1 Month \$15" ]'
key DPAD_DOWN DPAD_DOWN
check "Down from Pay: Check again" '[ "$(focused)" = "Check again" ]'
key DPAD_RIGHT
check "Right: Use a different account" '[ "$(focused)" = "Use a different account" ]'
key DPAD_UP
check "Up: Pay" 'focused | grep -q "Pay with Cash App Pay"'
shot 02-pay-focused

# ---------------- pay: waiting, then cancel with Back ----------------
adb logcat -c
key DPAD_CENTER
wait_for "Scan to pay with Cash App Pay" 10 && ok "Pay opens the Cash App Pay code on the TV" || bad "payment code not shown"
check "the code is for the chosen plan: 1 Month · \$15.00" 'text "1 Month  ·  \$15.00"'
check "state: Waiting for payment, with a countdown" 'grep -q "Waiting for payment" "$OUT/ui.xml" && text "Code expires in"'
check "QR encodes the checkout link from the service (no browser opened)" 'qa | grep -q "state=waiting qr=https://square.link/u/demo1" && in_activity RenewalActivity'
check "focus on Cancel" '[ "$(focused)" = "Cancel" ]'
shot 03-cash-app-pay-code
key BACK; sleep 2
check "Back cancels the unpaid code: Payment cancelled, focus back on the plan" 'has "Payment cancelled" && [ "$(focused)" = "1 Month \$15" ]'
shot 04-cancelled

# ---------------- processing, then renewed ----------------
key DPAD_DOWN DPAD_CENTER
wait_for "Waiting for payment" 10
server "{\"expired\":true,$ACC,\"pay\":\"processing\"}"
wait_for "Payment processing" 12 && ok "payment approved on the phone: Payment processing" || bad "processing state not shown"
check "processing is never shown as a failure, and has nothing to cancel" '! grep -qi "fail\|go through" "$OUT/ui.xml" && ! text "Cancel"'
key BACK; sleep 1
check "Back does not leave a payment that is being confirmed" 'has "Payment processing"'
shot 05-processing
server "{\"expired\":true,$ACC,\"pay\":\"renewed\"}"
want="$(day $(( $(date +%s) + 30*86400 )))"
wait_for "Renewal Successful" 12 && ok "renewal confirmed: Renewal Successful" || bad "success not shown"
check "success shows the new expiration ($want)" 'has "now expires on $want"'
shot 06-renewal-successful
sleep 8
check "the code closes by itself and the renewal screen shows the new expiry" '! has "Renewal Successful" && desc "Expires on $want" && in_activity RenewalActivity'
check "heading: You are all set; message: Renewal successful" 'has "re all set" && has "Renewal successful. Your new expiration date is $want"'
check "account refreshed from the backend: Active, not expired" '[ "$(pref account_status)" = "Active" ] && [ "$(pref expired)" != "true" ]'
check "focus on Start watching" '[ "$(focused)" = "Start watching" ]'
shot 07-back-on-renewal-screen
key DPAD_CENTER
wait_activity HomeActivity 15 && ok "Start watching opens Flix Town" || bad "did not reach Home ($(resumed))"
sleep 5

# ---------------- renew early from Settings: failed, expired code, needs assistance ----------------
server "{$ACC,\"pay\":\"failed\"}"
open_settings; dump
check "Settings lists Renew subscription and says Devices" 'has "Renew subscription" && card | grep -q "Devices 2"'
focus_to DPAD_DOWN "Renew subscription" 4 >/dev/null; key DPAD_CENTER
wait_for "Renew your subscription" 10 && ok "Settings › Renew subscription opens the renewal screen" || bad "early renewal not opened ($(resumed))"
check "active account: Expires on (not Expired on), Back instead of sign out" 'desc "Expires on $want" && text "Back"'
shot 08-renew-early
key DPAD_DOWN DPAD_CENTER
wait_for "payment didn.*go through" 12 && ok "declined payment: The payment didn't go through" || bad "failed state not shown"
check "failed: Try again focused, Close next to it" '[ "$(focused)" = "Try again" ] && { key DPAD_RIGHT; [ "$(focused)" = "Close" ]; }'
shot 09-failed
server "{$ACC,\"pay\":\"waiting\"}"
key DPAD_LEFT DPAD_CENTER
wait_for "Waiting for payment" 10 && ok "Try again makes a new code" || bad "retry did not make a new code"
server "{$ACC,\"pay\":\"expired\"}"
wait_for "This code has expired" 12 && ok "unpaid code timed out: This code has expired (no payment taken)" || bad "expired code state not shown"
shot 10-code-expired
server "{$ACC,\"pay\":\"waiting\"}"
key DPAD_CENTER; wait_for "Waiting for payment" 10
server "{$ACC,\"pay\":\"needs_assistance\"}"
wait_for "finish your renewal" 12 && ok "paid but renewal needs a person: We'll finish your renewal" || bad "assistance state not shown"
check "assistance: tells the customer not to pay again, gives a reference" 'has "pay again" && has "mention reference"'
shot 11-needs-assistance
key DPAD_CENTER; sleep 1
check "Close returns to the renewal screen" 'has "Renew your subscription" && ! has "finish your renewal"'
key BACK; sleep 2
check "Back on the renewal screen returns to Settings" 'in_activity HomeActivity && has "Renew subscription"'

# ---------------- service problems never touch the account ----------------
server "{$ACC,\"renewal_offline\":true}"
focus_to DPAD_DOWN "Renew subscription" 4 >/dev/null; key DPAD_CENTER
wait_for "load your plans" 12 && ok "renewal service unreachable: friendly message" || bad "offline message not shown"
check "offline: Try again offered, account untouched" 'text "Try again" && [ "$(pref account_status)" = "Active" ] && [ "$(pref expired)" != "true" ]'
shot 12-service-offline
server "{$ACC,\"payments_off\":true}"
key DPAD_CENTER
wait_for "available right now" 12 && ok "payments switched off on the server: no Pay button, clear message" || bad "payments-off message not shown"
key BACK; sleep 2

# ---------------- Never Expire: nothing to renew ----------------
server '{"connections":2,"plan":0,"exp":"never"}'
launch
wait_activity HomeActivity 45 && sleep 6
open_settings
focus_to DPAD_DOWN "Renew subscription" 4 >/dev/null; key DPAD_CENTER
wait_for "never expires" 12 && ok "Never Expire: Your plan never expires, nothing to pay" || bad "never-expire message not shown"
check "Never Expire: no plans, no Current plan when unknown, Expires on Never" '! desc "1 Month \$15" && ! grep -q "Current plan" "$OUT/ui.xml" && desc "Expires on Never"'
shot 13-never-expire

check "no crash during the whole run" '[ "$(crashes)" = "0" ]'
adb shell "run-as $PKG rm -f files/demo_account.json"
note ""; note "renewal flow: $PASS passed, $FAIL failed"
