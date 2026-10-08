#!/usr/bin/env bash
# Continue Watching menu (hold OK) on an Android TV emulator (QA build, offline demo server).
# Checked from the live UI (uiautomator) and the player's QA state lines, plus screenshots.
# Results: qa/continue-menu/report.txt and qa/continue-menu/screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=${QA_OUT:-qa/continue-menu}; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

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
# Text or description of the focused view (or of its first labelled descendant).
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
# Presses a key until the focused view's label matches (max n presses).
focus_to(){ local k=$1 want=$2 n=${3:-6}; for i in $(seq 1 $n); do focused | grep -q -- "$want" && return 0; key "$k"; done; focused | grep -q -- "$want"; }
focused_id(){ dump; python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    if n.get('focused')=='true': print(n.get('resource-id','')); break
EOF
}
text_of_id(){ dump; python3 - "$OUT/ui.xml" "$1" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    if n.get('resource-id','').endswith(':id/'+sys.argv[2]): print(n.get('text','')); break
EOF
}
# QA-build state lines (UI dumps do not work while video is playing).
qa_line(){ adb logcat -d -s FlixTownQA:I | grep " player " | tail -1; }
qaf(){ qa_line | python3 -c 'import re,sys
l=sys.stdin.read();k=sys.argv[1]
m=re.search(k+r"=\[([^\]]*)\]",l) or re.search(k+r"=(.*)$" if k=="subtitle" else k+r"=(\S+)",l)
print(m.group(1).strip() if m else "")' "$1"; }
picker_line(){ adb logcat -d -s FlixTownQA:I | grep " picker $1" | tail -1; }
# Moves through the player controls until the QA state reports the wanted control focused.
player_focus(){ for i in 1 2 3 4; do [ "$(qaf focus)" = "$1" ] && return 0; key "$2"; sleep 0.6; done; [ "$(qaf focus)" = "$1" ]; }
wait_qa(){ local t=0; while [ $t -lt "$3" ]; do [ "$(qaf "$1")" = "$2" ] && return 0; sleep 1; t=$((t+1)); done; return 1; }
secs(){ python3 -c 'import sys;p=sys.argv[1].lstrip("-").split(":");print(sum(int(x)*60**i for i,x in enumerate(reversed(p))) if all(x.isdigit() for x in p) else -1)' "$1"; }
refreshes(){ adb logcat -d -s FlixTown:I | grep -c "Refresh started" ; }
hold_ok(){ adb shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1.2; }
# Titles of the cards in the Continue Watching row, in order (empty when the row is gone).
continue_cards(){ dump; python3 - "$OUT/ui.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot()
def walk(n, parent=None):
    for c in n:
        yield c, n
        yield from walk(c, n)
parents={id(c): p for c, p in walk(root)}
for n in root.iter('node'):
    if n.get('text') == 'Continue Watching':
        sec = parents.get(id(n))
        while sec is not None and not any(c.get('class','').endswith('HorizontalGridView') for c in sec.iter('node')):
            sec = parents.get(id(sec))
        if sec is None: break
        grid = next(c for c in sec.iter('node') if c.get('class','').endswith('HorizontalGridView'))
        print("|".join(c.get('content-desc') for c in grid if c.get('content-desc')))
        break
PY
}
back_home(){ for i in 1 2 3 4; do in_activity HomeActivity && return 0; key BACK; sleep 1.2; done; in_activity HomeActivity; }

# ---------------- set up: real progress for one movie (also in My List) and one show ----------------
launch --ez demo_reset true
wait_activity HomeActivity 45 && sleep 8
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;         sleep 2.5        # menu -> Movies
movie="$(focused)"
key DPAD_CENTER;                             sleep 3.5        # details
focus_to DPAD_RIGHT "My List" 3; key DPAD_CENTER; sleep 0.8
check "set up: '$movie' added to My List" 'focused | grep -q "✓"'
focus_to DPAD_LEFT "Play" 3; key DPAD_CENTER
wait_activity PlayerActivity 10; sleep 22
key BACK; sleep 1; in_activity PlayerActivity && key BACK
wait_activity DetailsActivity 6; key BACK; sleep 1.5
key DPAD_LEFT; focus_to DPAD_UP "TV Shows" 3; key DPAD_CENTER; sleep 2.5
show="$(focused)"
key DPAD_CENTER;                             sleep 4
focus_to DPAD_DOWN "Season 1" 3; key DPAD_DOWN; sleep 0.8; key DPAD_CENTER   # first episode
wait_activity PlayerActivity 10; sleep 22
key BACK; sleep 1; in_activity PlayerActivity && key BACK
wait_activity DetailsActivity 6; key BACK; sleep 1.5
key DPAD_LEFT; focus_to DPAD_UP "Home" 4; key DPAD_CENTER; sleep 3
key DPAD_DOWN;                               sleep 1.5
cards="$(continue_cards)"
check "Continue Watching shows the show and the movie ($cards)" '[ "$cards" = "$show|$movie" ]'
check "focus on the first Continue Watching card ($show)" '[ "$(focused)" = "$show" ]'
shot 01-continue-row

# ---------------- hold OK: the menu ----------------
hold_ok
check "holding OK opens the menu" 'has "Remove from Continue Watching" && has "Start over" && has "Resume"'
check "Resume is focused first" '[ "$(focused)" = "Resume" ]'
check "the press did not also open the title (still on Home)" 'in_activity HomeActivity'
shot 02-menu
key DPAD_DOWN; check "D-pad Down: Start over" '[ "$(focused)" = "Start over" ]'
key DPAD_DOWN; check "D-pad Down: Remove from Continue Watching" '[ "$(focused)" = "Remove from Continue Watching" ]'
shot 03-menu-remove-focused 0.3
key BACK; sleep 0.8
check "Back closes the menu, nothing removed, focus back on the card" '! has "Remove from Continue Watching" && [ "$(continue_cards)" = "$show|$movie" ] && [ "$(focused)" = "$show" ]'

# ---------------- normal OK keeps its behaviour ----------------
key DPAD_RIGHT; sleep 0.6
check "focus on the movie card" '[ "$(focused)" = "$movie" ]'
adb logcat -c
key DPAD_CENTER
wait_activity PlayerActivity 10 && sleep 3
check "normal OK on a movie: player asks 'Continue watching?' (unchanged)" '[ "$(qaf card)" = "Continue watching?" ]'
back_home; sleep 1.5

# ---------------- Resume and Start over ----------------
focus_to DPAD_RIGHT "$movie" 3
adb logcat -c
hold_ok; key DPAD_CENTER                                         # Resume (focused)
wait_activity PlayerActivity 10 && sleep 4
e="$(qaf elapsed)"
check "Resume: plays from the saved position without asking (${e}s)" '[ -z "$(qaf card)" ] && [ -n "$e" ] && [ "$e" -ge 15 ]'
back_home; sleep 1.5
focus_to DPAD_LEFT "$show" 3
adb logcat -c
hold_ok; key DPAD_DOWN DPAD_CENTER                               # Start over
wait_activity PlayerActivity 15 && sleep 4
e="$(qaf elapsed)"
check "Start over (show): the saved episode from 0:00, no prompt (${e}s)" '[ -z "$(qaf card)" ] && [ -n "$e" ] && [ "$e" -lt 12 ]'
back_home; sleep 1.5
check "after Start over the row still has both titles (nothing lost)" '[ "$(continue_cards)" = "$show|$movie" ]'

# ---------------- Remove ----------------
focus_to DPAD_LEFT "$show" 3
hold_ok; key DPAD_DOWN DPAD_DOWN; key DPAD_CENTER; sleep 1.5
check "Remove: the show leaves the row at once ($(continue_cards))" '[ "$(continue_cards)" = "$movie" ]'
check "focus moves to the nearest remaining card ($movie)" '[ "$(focused)" = "$movie" ]'
shot 04-after-remove-show
hold_ok; key DPAD_DOWN DPAD_DOWN; key DPAD_CENTER; sleep 1.5
check "removing the last card removes the row" '! has "Continue Watching"'
f="$(focused)"
check "focus is on the next row, not lost ('$f')" '[ -n "$f" ] && in_activity HomeActivity'
shot 05-row-gone

# ---------------- nothing else affected ----------------
key DPAD_LEFT; focus_to DPAD_DOWN "My List" 5; key DPAD_CENTER; sleep 2
check "My List still has '$movie'" 'has "$movie"'
key DPAD_LEFT; focus_to DPAD_UP "TV Shows" 4; key DPAD_CENTER; sleep 2.5
focus_to DPAD_RIGHT "$show" 6; key DPAD_CENTER; sleep 4
check "the show's details offer Watch Now again (no stale Continue)" 'has "Watch Now" && ! has "▶  Continue"'
back_home; sleep 1
launch; sleep 12
check "reopened app: Continue Watching stays empty" 'in_activity HomeActivity && ! has "Continue Watching"'
shot 06-reopened

adb logcat -d | grep -E "FATAL EXCEPTION" -A 12 > "$OUT/crash-log.txt" || true
check "no crash during the run" '! grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt"'
note "continue menu: $PASS passed, $FAIL failed"
