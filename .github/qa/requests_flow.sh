#!/usr/bin/env bash
# Requests (Seerr through OnePanel) on an Android TV emulator: QA build with the offline demo server,
# which answers like panel/onepanel-tv-requests. D-pad only: menu entry, search, results grid with
# paging, details, season picker, request, duplicates, My Requests, Back/focus, and the
# "temporarily unavailable" state. Results: $QA_OUT (default qa/requests-flow)/report.txt + screens.
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=${QA_OUT:-qa/requests-flow}; SHOTS=$OUT/screens
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
# Only Flix Town's own process counts (the API 28 TV image's Live TV app crashes on its own; kept in crash.txt).
crashes(){ adb logcat -d -b crash | grep -A1 "FATAL EXCEPTION" | grep -c "Process: com.flixtown.tv" ; }
# The on-screen keyboard takes the D-pad while it is up; a viewer closes it with Back (or its search key).
kb_shown(){ sleep 0.7; adb shell dumpsys input_method | grep -q "mInputShown=true"; }
close_kb(){ kb_shown && { key BACK; sleep 1; }; return 0; }
qa(){ adb logcat -d -s FlixTownQA:I | grep " requests " ; }
desc(){ grep -q "content-desc=\"$1" "$OUT/ui.xml"; }
text(){ grep -q "text=\"$1" "$OUT/ui.xml"; }
menu_to(){ key DPAD_LEFT; focus_to DPAD_UP "Search" 8 >/dev/null; focus_to DPAD_DOWN "$1" 9 >/dev/null; }
# Class of the focused view (the search box is the only EditText on the screen).
focused_class(){ dump; python3 -c '
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter("node"):
    if n.get("focused")=="true": print(n.get("class","")); break
' "$OUT/ui.xml"; }
in_field(){ focused_class | grep -q EditText; }
# Grid columns, from the cards sharing the first card's top edge.
span(){ dump; python3 -c '
import sys, re, xml.etree.ElementTree as ET
tops=[int(re.findall(r"\d+",n.get("bounds"))[1]) for n in ET.parse(sys.argv[1]).getroot().iter("node")
      if re.search(r"\. (Movie|TV Show)(, \d{4})?\. ", n.get("content-desc") or "")]
print(sum(1 for t in tops if t==tops[0]) if tops else 1)
' "$OUT/ui.xml"; }
# The focused card (artwork and its three lines of text) lies wholly below the header and on screen.
card_visible(){ dump; python3 -c '
import sys, re, xml.etree.ElementTree as ET
nodes=list(ET.parse(sys.argv[1]).getroot().iter("node"))
b=lambda n: [int(x) for x in re.findall(r"\d+", n.get("bounds"))]
f=[n for n in nodes if n.get("focused")=="true" and re.search(r"\. (Movie|TV Show)(, \d{4})?\. ", n.get("content-desc") or "")]
top=max([b(n)[3] for n in nodes if n.get("text") in ("Search","My Requests")] or [0])
height=max(b(n)[3] for n in nodes)
ok = bool(f) and b(f[0])[1] >= top and b(f[0])[3] <= height and b(f[0])[3]-b(f[0])[1] > 0
print("card", b(f[0]) if f else None, "header bottom", top, "screen", height, file=sys.stderr)
sys.exit(0 if ok else 1)
' "$OUT/ui.xml"; }
# No card (poster or text) is drawn over the header after scrolling.
header_clear(){ dump; python3 -c '
import sys, re, xml.etree.ElementTree as ET
nodes=list(ET.parse(sys.argv[1]).getroot().iter("node"))
b=lambda n: [int(x) for x in re.findall(r"\d+", n.get("bounds"))]
top=max([b(n)[3] for n in nodes if n.get("text") in ("Search","My Requests")] or [0])
cards=[n for n in nodes if re.search(r"\. (Movie|TV Show)(, \d{4})?\. ", n.get("content-desc") or "")]
over=[(n.get("content-desc"), b(n)) for n in cards for d in [n]+list(n.iter("node")) if b(d)[1] < top and b(d)[3] > b(d)[1]]
print("over header:", over[:3], file=sys.stderr)
sys.exit(1 if over else 0)
' "$OUT/ui.xml"; }
# Focus result number N (0-based): back to the search box, then down and across.
go_card(){ local n=$1 s; for i in $(seq 1 8); do in_field && break; key DPAD_UP; done
  sleep 0.5; close_kb; key DPAD_DOWN; sleep 0.5; in_field && { close_kb; key DPAD_DOWN; }; sleep 0.5; s=$(span)
  for i in $(seq 1 $((n / s))); do key DPAD_DOWN; done
  for i in $(seq 1 $((n % s))); do key DPAD_RIGHT; done; sleep 0.8
  # When a title is given, make sure the move landed on it (the grid may still have been scrolling).
  if [ -n "$2" ] && ! focused | grep -q -- "$2"; then sleep 1; go_card "$n"; fi; }

adb logcat -c; adb logcat -b crash -c 2>/dev/null || true
launch --ez demo_reset true
wait_activity HomeActivity 45 && sleep 6
check "no Requests call during sign-in and Home loading" '[ -z "$(qa)" ]'

# ---------------- the menu entry ----------------
menu_to "Requests"
dump
order="$(python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
names=["Search","Home","Movies","TV Shows","My List","Requests","Settings"]
seen=[n.get('content-desc') for n in ET.parse(sys.argv[1]).getroot().iter('node') if n.get('content-desc') in names]
print("|".join(seen))
EOF
)"
check "side menu: Requests between My List and Settings ($order)" '[ "$order" = "Search|Home|Movies|TV Shows|My List|Requests|Settings" ]'
check "Requests focused in the menu" 'focused | grep -q "^Requests$"'
shot 01-menu-requests
key DPAD_CENTER
wait_activity RequestsActivity 10 && ok "Requests opens its own screen" || bad "Requests screen not opened ($(resumed))"
sleep 1; dump
check "Search is the main focus: the search box is focused" 'in_field'
check "tabs Search and My Requests" 'text "Search" && text "My Requests"'

# ---------------- search, results and paging ----------------
adb shell input text "alpha"; sleep 3
check "typing searches after a short pause (debounced, one search)" '[ "$(qa | grep -c "search q=alpha page=1")" = 1 ]'
dump
check "results: Movie, year and state on the cards" 'desc "Alpha Movie. Movie, 1990. Requestable"'
check "states: Available, Pending approval, Approved" 'desc "Alpha Title 1. Movie, 1991. Available" && desc "Alpha Title 2. Movie, 1992. Pending approval" && desc "Alpha Title 3. Movie, 1993. Approved · processing"'
kb_shown || { key DPAD_CENTER; sleep 1.5; }   # OK on the search box opens the keyboard
check "the on-screen keyboard is open on the search box" 'kb_shown'
key BACK; sleep 1
check "Back closes the keyboard first and stays on Requests" 'in_activity RequestsActivity && in_field && ! kb_shown'
key ENTER; sleep 1.5
check "the keyboard's Search action moves focus to the first result" 'focused | grep -q "Alpha Movie"'
key DPAD_UP; sleep 0.5; close_kb
check "Up from the first result returns to the search box" 'in_field'
key DPAD_DOWN; sleep 1
check "Down from the search box: first result" 'focused | grep -q "Alpha Movie"'
check "four posters across, like the rest of the app" '[ "$(span)" = 4 ]'
check "first card and its text fully visible" 'card_visible'
shot 02-search-results
key DPAD_DOWN; sleep 1; dump
check "states: Declined" 'desc "Alpha Title 4. Movie, 1994. Declined"'
check "series shown as Partly available (never fully available)" 'desc "Alpha Show. TV Show, 2015. Partly available"'
check "a movie already in this TV's catalog shows In Flix Town" 'grep -q "In Flix Town" "$OUT/ui.xml"'
key DPAD_UP; sleep 1
check "Up returns to the first row" 'focused | grep -q "Alpha Movie"'
for i in 1 2 3 4; do key DPAD_DOWN; sleep 0.8; check "scrolling down (row $((i+1))): focused card and its text fully visible" 'card_visible'
  check "scrolling down (row $((i+1))): nothing drawn over the header" 'header_clear'; done
shot 02b-search-scrolled; sleep 1.5
check "scrolling near the end loads page 2 (30 results)" 'qa | grep -q "search q=alpha page=2 results=30"'
for i in $(seq 1 8); do in_field && break; key DPAD_UP; done; close_kb
check "Up from the first row returns to the search box" 'in_field'

# ---------------- movie details and request ----------------
go_card 0 "Alpha Movie"; key DPAD_CENTER; sleep 2
dump
check "details: title, synopsis, Requestable, Request button focused" 'text "Alpha Movie" && grep -q "A demo synopsis for Alpha Movie" "$OUT/ui.xml" && [ "$(focused)" = "Request" ]'
shot 03-details-movie
key DPAD_CENTER; sleep 2; dump
check "Request: Pending approval, told where to find it" 'grep -q "Pending approval" "$OUT/ui.xml" && grep -q "My Requests" "$OUT/ui.xml" && qa | grep -q "submit movie:8100 seasons= ok=true"'
shot 04-requested
key BACK; sleep 1
check "Back closes details and returns to the same card" 'in_activity RequestsActivity && focused | grep -q "Alpha Movie"'
check "the card now says Pending approval" 'focused | grep -q "Alpha Movie. Movie, 1990. Pending approval"'
key DPAD_CENTER; sleep 2; dump
check "reopened: no Request button (already requested)" '! text "Request\"" && grep -q "already been requested" "$OUT/ui.xml"'
key BACK; sleep 1

# declined and available: nothing to request
go_card 4 "Alpha Title 4"; key DPAD_CENTER; sleep 2; dump
check "declined: no Request button, explained" '! text "Request\"" && grep -q "declined" "$OUT/ui.xml"'
key BACK; sleep 1
go_card 1 "Alpha Title 1"; key DPAD_CENTER; sleep 2; dump
check "available: no Request button" '! text "Request\"" && grep -q "already available" "$OUT/ui.xml"'
key BACK; sleep 1

# already in Flix Town: Watch instead of Request
go_card 6 "In Flix Town"; check "card 7 is the title already in Flix Town" 'focused | grep -q "In Flix Town"'; key DPAD_CENTER; sleep 2; dump
check "in Flix Town: Watch in Flix Town, no Request" 'text "Watch in Flix Town" && ! text "Request\""'
shot 05-in-flix-town
key DPAD_CENTER; wait_activity DetailsActivity 8 && ok "Watch opens the Flix Town title" || bad "Watch did not open details"
key BACK; sleep 2
check "Back from the title returns to Requests" 'in_activity RequestsActivity'
key BACK; sleep 1

# ---------------- series: season picker ----------------
go_card 5 "Alpha Show"; key DPAD_CENTER; sleep 2; dump
check "series details: Choose seasons" '[ "$(focused)" = "Choose seasons" ]'
key DPAD_CENTER; sleep 1; dump
check "season picker: S1 available and S2 pending shown, not selectable" 'grep -q "Season 1  ·  8 episodes   —   Available" "$OUT/ui.xml" && grep -q "Season 2  ·  8 episodes   —   Pending approval" "$OUT/ui.xml"'
check "focus starts on the first requestable season (Season 3)" 'focused | grep -q "Season 3"'
shot 06-season-picker
key DPAD_UP; sleep 0.5
check "Up does not land on seasons that cannot be requested" 'focused | grep -q "Season 3"'
key DPAD_CENTER; sleep 0.5
check "OK selects Season 3 (✓) and the button says Request 1 season" 'focused | grep -q "✓  Season 3" && { dump; text "Request 1 season"; }'
key DPAD_DOWN; sleep 0.5
check "Down: Season 4 (the last season that can be requested)" 'focused | grep -q "Season 4"'
key DPAD_DOWN; sleep 0.5
check "Down from the last season: Request 1 season" '[ "$(focused)" = "Request 1 season" ]'
key DPAD_CENTER; sleep 2; dump
check "season request sent for S3 only, Pending approval" 'qa | grep -q "submit tv:8205 seasons=S3 ok=true" && grep -q "Requested S3" "$OUT/ui.xml"'
shot 07-season-requested
key BACK; sleep 1; key DPAD_CENTER; sleep 2; key DPAD_CENTER; sleep 1; dump
check "reopened picker: Season 3 now pending, only Season 4 selectable" 'grep -q "Season 3  ·  8 episodes   —   Pending approval" "$OUT/ui.xml" && focused | grep -q "Season 4"'
key BACK; sleep 1
check "Back from the picker returns to the series details" 'text "Choose seasons"'
key BACK; sleep 1

# ---------------- My Requests ----------------
for i in $(seq 1 10); do close_kb; [ "$(focused)" = "Search" ] && break; key DPAD_UP; done
check "Up from the search box reaches the tabs" '[ "$(focused)" = "Search" ]'
key DPAD_RIGHT; check "Right: My Requests" '[ "$(focused)" = "My Requests" ]'
key DPAD_CENTER; sleep 3; dump
check "My Requests: my movie (Pending approval) and my season (S3)" 'grep -q "Alpha Movie" "$OUT/ui.xml" && grep -q "Alpha Show" "$OUT/ui.xml" && grep -q "S3" "$OUT/ui.xml"'
check "My Requests: an earlier request shows Approved · processing" 'grep -q "Alpha Earlier Request" "$OUT/ui.xml" && grep -q "Approved · processing" "$OUT/ui.xml"'
check "My Requests: focused card and its text fully visible" 'card_visible'
shot 08-my-requests

# ---------------- unavailable ----------------
server '{"requests":"down"}'
for i in $(seq 1 6); do [ "$(focused)" = "My Requests" ] && break; key DPAD_UP; done
key DPAD_LEFT DPAD_CENTER; sleep 4; dump
check "service down: Requests are temporarily unavailable." 'grep -q "Requests are temporarily unavailable." "$OUT/ui.xml"'
shot 09-unavailable
key BACK; sleep 1.5; in_activity RequestsActivity && { key BACK; sleep 1.5; }
check "Back leaves Requests for Home" 'in_activity HomeActivity'
menu_to "Movies"; key DPAD_CENTER; sleep 3; dump
check "browsing still works while Requests is down (Movies grid)" 'grep -q "All Movies" "$OUT/ui.xml" && [ "$(grep -c "content-desc=\"[A-Z][a-z]* [A-Z]" "$OUT/ui.xml")" -ge 1 ]'
adb shell "run-as $PKG rm -f files/demo_account.json"

check "no crash during the whole run" '[ "$(crashes)" = "0" ]'
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null; [ -s "$OUT/crash.txt" ] || rm -f "$OUT/crash.txt"
note ""; note "requests flow: $PASS passed, $FAIL failed"
