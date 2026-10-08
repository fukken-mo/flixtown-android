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
# The app's saved settings (QA build is debuggable): proves what is really stored, not only what is shown.
prefs(){ adb shell run-as $PKG cat shared_prefs/flix.xml 2>/dev/null; }
# Saved progress for one title: its resume_* keys or its entry in the Continue Watching list (not My List).
progress_of(){ prefs | grep -Eq "name=\"resume_[a-z_]*$1\"|name=\"continue_ids\">[^<]*$1"; }
# On a failed check, record where the app was (activity + focused view) so a failure is explainable.
check(){ if eval "$2"; then ok "$1"; else bad "$1"; note "      at: $(resumed | grep -o '[A-Za-z]*Activity' | tail -1) focus='$(focused)'"; fi; }
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
        is_list = lambda c: 'RecyclerView' in c.get('class','') or 'GridView' in c.get('class','')
        while sec is not None and not any(is_list(c) for c in sec.iter('node')):
            sec = parents.get(id(sec))
        if sec is None: break
        grid = next(c for c in sec.iter('node') if is_list(c))
        print("|".join(c.get('content-desc') for c in grid if c.get('content-desc')))
        break
PY
}
back_home(){ for i in 1 2 3 4 5 6 7; do in_activity HomeActivity && return 0; key BACK; sleep 1.5; done; in_activity HomeActivity; }
# Home tab, up to the hero, then the first card of the first row under it (Continue Watching).
home_row(){ back_home; key DPAD_LEFT; focus_to DPAD_UP "Home" 5; key DPAD_CENTER; sleep 2.5
  key DPAD_UP DPAD_UP DPAD_UP; sleep 0.8; key DPAD_DOWN; sleep 1.5; }

# ---------------- set up: a movie (also put in My List) and a show with saved progress ----------------
launch --ez demo_reset true --ez demo_continue true
wait_activity HomeActivity 45 && sleep 8
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;         sleep 2.5        # menu -> Movies
listed="$(focused)"
key DPAD_CENTER;                             sleep 3.5        # details
focus_to DPAD_RIGHT "My List" 3; key DPAD_CENTER; sleep 0.8
check "set up: '$listed' added to My List" 'focused | grep -q "✓"'
home_row
cards="$(continue_cards)"
movie="${cards%%|*}"; show="${cards##*|}"
check "Continue Watching shows the seeded movie and show ($cards)" '[ -n "$movie" ] && [ -n "$show" ] && [ "$movie" != "$show" ] && [ "$movie|$show" = "$cards" ]'
check "the movie card is the one in My List" '[ "$movie" = "$listed" ]'
check "focus on the first Continue Watching card ($movie)" '[ "$(focused)" = "$movie" ]'
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
check "Back closes the menu, nothing removed, focus back on the card" '! has "Remove from Continue Watching" && [ "$(continue_cards)" = "$movie|$show" ] && [ "$(focused)" = "$movie" ]'

# ---------------- normal OK keeps its behaviour ----------------
check "focus on the movie card" '[ "$(focused)" = "$movie" ]'
adb logcat -c
key DPAD_CENTER
wait_activity PlayerActivity 10 && sleep 3
check "normal OK on a movie: player asks 'Continue watching?' (unchanged)" '[ "$(qaf card)" = "Continue watching?" ]'
home_row

# ---------------- Resume and Start over ----------------
focus_to DPAD_LEFT "$movie" 3
adb logcat -c
hold_ok; key DPAD_CENTER                                         # Resume (focused)
wait_activity PlayerActivity 10 && sleep 4
e="$(qaf elapsed)"
check "Resume: plays from the saved position (0:20) without asking (${e}s)" '[ -z "$(qaf card)" ] && [ -n "$e" ] && [ "$e" -ge 18 ] && [ "$e" -lt 40 ]'
shot 07-resume-playing 0.2
home_row
focus_to DPAD_RIGHT "$show" 3
adb logcat -c
hold_ok; key DPAD_DOWN DPAD_CENTER                               # Start over
wait_activity PlayerActivity 25 && sleep 4
e="$(qaf elapsed)"
check "Start over (show): the saved episode from 0:00, no prompt (${e}s)" '[ -z "$(qaf card)" ] && [ -n "$e" ] && [ "$e" -lt 12 ]'
shot 08-start-over-playing 0.2
home_row
check "after Start over the row still has both titles (nothing lost)" '[ "$(continue_cards)" = "$movie|$show" ]'

# ---------------- Remove ----------------
home_row
focus_to DPAD_RIGHT "$show" 3
check "focus on the show card before removing" '[ "$(focused)" = "$show" ]' 
hold_ok; key DPAD_DOWN DPAD_DOWN; key DPAD_CENTER; sleep 1.5
check "Remove: the show leaves the row at once ($(continue_cards))" '[ "$(continue_cards)" = "$movie" ]'
sleep 1
check "the show's saved progress is gone (episode, queued next episode, row entry)" 'progress_of "movie:1000" && ! progress_of "series:5002"'
check "focus moves to the nearest remaining card ($movie)" '[ "$(focused)" = "$movie" ]'
shot 04-after-remove-show
hold_ok; key DPAD_DOWN DPAD_DOWN; key DPAD_CENTER; sleep 1.5
check "removing the last card removes the row (still on Home)" 'in_activity HomeActivity && ! has "Continue Watching"'
sleep 1
check "the movie's saved progress is gone too" '! progress_of "movie:1000"'
check "My List entry for the movie is still stored" 'prefs | grep -q "|movie:1000|"'
f="$(focused)"
check "focus is on the next row, not lost ('$f')" '[ -n "$f" ] && in_activity HomeActivity'
shot 05-row-gone

# ---------------- nothing else affected ----------------
key DPAD_LEFT; focus_to DPAD_DOWN "My List" 5; key DPAD_CENTER; sleep 2
check "My List still has '$movie'" 'has "$movie"'
back_home; sleep 1
launch; sleep 12
check "reopened app: Continue Watching stays empty" 'in_activity HomeActivity && ! has "Continue Watching"'
check "reopened app: no saved progress came back" '! progress_of "series:5002" && ! progress_of "movie:1000"'
shot 06-reopened

adb logcat -d | grep -E "FATAL EXCEPTION" -A 12 > "$OUT/crash-log.txt" || true
check "no crash during the run" '! grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt"'
note "continue menu: $PASS passed, $FAIL failed"
