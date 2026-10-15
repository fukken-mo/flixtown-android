#!/usr/bin/env bash
# Subtitle position in the player (QA build) with local test videos whose subtitle cues are known:
#   track "Plain"  (SRT, green):  one-line and two-line cues in turn, the usual bottom position
#   track "Bottom" (ASS, yellow): cues placed by the file at the very bottom edge ({\an2\pos(W/2,720)}),
#                                 like many episode subtitle tracks
# Every cue lasts 4 s and they run back to back for 5 minutes, so they span the 3 s auto-hide of the
# controls. Subtitle pixels are found by colour in screenshots (the picture is plain grey; the controls
# are white/red/blue). Expected: subtitles bottom-centre, ~5 % above the bottom of the picture, at the
# same place with the controls shown, hidden and toggled, after seeking and switching tracks; Off works;
# 16:9 fills the screen, 4:3 keeps its shape (pillarboxed). Movie and episode at 16:9, a movie at 4:3.
# Needs TEST_STREAM (16:9) and TEST_STREAM_43 (4:3). Results: $OUT/report.txt, $OUT/screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
APK=${APK:-app/build/outputs/apk/qa/app-qa.apk}
OUT=${QA_OUT:-qa/subtitles}; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
adb install -r "$APK" > /dev/null || exit 1
adb shell settings put system screen_off_timeout 1800000 || true
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
resumed(){ adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -1; }
in_activity(){ resumed | grep -q "$1"; }
wait_activity(){ local t=0; while [ $t -lt "$2" ]; do in_activity "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
dump(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }
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
qa_line(){ adb logcat -d -s FlixTownQA:I | grep " player " | tail -1; }
qaf(){ qa_line | python3 -c 'import re,sys
l=sys.stdin.read();k=sys.argv[1]
m=re.search(k+r"=\[([^\]]*)\]",l) or re.search(k+r"=(\S+)",l)
print(m.group(1).strip() if m else "")' "$1"; }
wait_qa(){ local t=0; while [ $t -lt "$3" ]; do [ "$(qaf "$1")" = "$2" ] && return 0; sleep 1; t=$((t+1)); done; return 1; }
player_focus(){ for i in 1 2 3 4 5; do [ "$(qaf focus)" = "$1" ] && return 0; key "$2"; sleep 0.6; done; [ "$(qaf focus)" = "$1" ]; }
picker_line(){ adb logcat -d -s FlixTownQA:I | grep " picker Subtitles" | tail -1; }

# Screenshot -> where pixels of a colour are: "x0 y0 x1 y1 W H count lines px0 px1" (count 0 = none),
# lines = text rows found, px0..px1 = the grey picture's extent across the middle of the screen.
sub_box(){ adb exec-out screencap > "$OUT/s.raw"; qaf controls > "$OUT/lc"
  adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$SHOTS/$1.png" > /dev/null
  python3 - "$OUT/s.raw" "$2" <<'PYEOF'
import sys, struct
d=open(sys.argv[1],'rb').read(); w,h=struct.unpack('<II',d[:8]); hdr=len(d)-w*h*4   # 12 or 16 byte header
want=sys.argv[2]; x0=y0=10**9; x1=y1=-1; n=0; rows=[]
def px(x,y): o=hdr+(y*w+x)*4; return d[o],d[o+1],d[o+2]
for y in range(0,h):
    row=d[hdr+y*w*4: hdr+(y+1)*w*4]; hit_row=False
    for x in range(0,w,2):                   # every other pixel is plenty for text
        R,G,B=row[4*x],row[4*x+1],row[4*x+2]
        # the controls' scrim darkens the text, so colours are judged by proportion, not brightness
        hit = (G>100 and R<G//2 and B<G//2) if want=='green' else (R>100 and G>100 and B<min(R,G)//2 and abs(R-G)<60)
        if hit: n+=1; hit_row=True; x0=min(x0,x); x1=max(x1,x); y0=min(y0,y); y1=max(y1,y)
    if hit_row: rows.append(y)
lines=0; last=-99
for y in rows:
    if y-last>6: lines+=1
    last=y
g=[x for x in range(w) if (lambda p: 40<=p[0]<=110 and abs(p[0]-p[1])<12 and abs(p[1]-p[2])<12)(px(x,h//2))]
print(x0,y0,x1,y1,w,h,n,lines,min(g) if g else -1,max(g) if g else -1) if n else print(0,0,0,0,w,h,0,0,min(g) if g else -1,max(g) if g else -1)
PYEOF
}
# Bottom-centre inside the 5 % safe area and near its bottom: the text's lowest pixel lies between
# 88 % and 95 % of the screen height, horizontally centred, nothing outside the safe margins.
bottom_ok(){ set -- $1; [ "$7" -gt 20 ] && [ "$4" -le $(($6*95/100+2)) ] && [ "$4" -ge $(($6*88/100)) ] && [ "$2" -ge $(($6*5/100)) ] \
  && [ "$1" -ge $(($5*5/100)) ] && [ "$3" -le $(($5*95/100)) ] && [ $(( ($1+$3)/2 - $5/2 )) -le $(($5/50)) ] && [ $(( $5/2 - ($1+$3)/2 )) -le $(($5/50)) ]; }
present(){ set -- $1; [ "$7" -gt 20 ]; }
absent(){ set -- $1; [ "$7" -le 3 ]; }
bottom_of(){ set -- $1; echo "$4"; }
lines_of(){ set -- $1; echo "$8"; }
# Same bottom line as an earlier capture (tolerance $3 px; default 3).
same_bottom(){ local a b; a=$(bottom_of "$1"); b=$(bottom_of "$2"); [ $((a-b)) -le ${3:-3} ] && [ $((b-a)) -le ${3:-3} ]; }
# The grey picture: 16:9 fills the width; 4:3 is H*4/3 wide (within 2 %), centred.
aspect_ok(){ local a=$1; set -- $2; local pw=$(( ${10} - $9 + 1 ))
  if [ "$a" = 169 ]; then [ "$9" -le $(($5/100)) ] && [ "${10}" -ge $(($5*99/100)) ]
  else local want=$(($6*4/3)); [ $((pw-want)) -le $((want/50)) ] && [ $((want-pw)) -le $((want/50)) ]; fi; }

# Capture with the controls up: shown right before (the auto-hide is 3 s), retried if they had gone.
shown_box(){ local r; for t in 1 2 3; do key DPAD_UP; sleep 0.5; r="$(sub_box "$1" "$2")"; [ "$(cat "$OUT/lc")" = true ] && break; done; echo "$r"; }
pickers(){ adb logcat -d -s FlixTownQA:I | grep -c " picker Subtitles"; }
# Opens the Subtitles menu and waits until it is really open (its QA line appears).
open_picker(){ local n0; n0="$(pickers)"
  # Same keys whatever the player state (PlayerActivity): Up shows the controls on Play (or moves from
  # the progress line to Play), Up again does nothing on the buttons row, Left x3 ends on Play (Left on
  # Play does nothing), Right x2 = Play -> Audio -> Subtitles. Each key keeps the controls up.
  key DPAD_UP DPAD_UP DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_RIGHT DPAD_RIGHT DPAD_CENTER
  for i in $(seq 1 12); do [ "$(pickers)" -gt "$n0" ] && { sleep 1.2; return 0; }; sleep 0.5; done
  note "INFO  menu not open: $(qa_line)"; return 1; }
# Picks a subtitle track by label through the player's Subtitles menu; checks the label after, once more if needed.
choose_subs(){ local want="$1" expect
  case "$want" in Plain) expect=English;; Bottom) expect=French;; *) expect=Off;; esac
  for attempt in 1 2; do
    open_picker || { note "INFO  Subtitles menu did not open (attempt $attempt)"; continue; }
    local line; line="$(picker_line)"; note "INFO  $line"
    local moves; moves="$(python3 -c '
import re,sys
l=sys.argv[1]; want=sys.argv[2]
m=re.search(r"picker Subtitles: (.*) selected=(\d+)",l)
labels=m.group(1).split("|"); sel=int(m.group(2))
t=[i for i,x in enumerate(labels) if want in x][0]
print(("DOWN "*(t-sel) if t>sel else "UP "*(sel-t)).strip())' "$line" "$want")"
    for k in $moves; do key "DPAD_$k"; done
    key DPAD_CENTER; sleep 1
    for i in 1 2 3 4; do qaf subs | grep -q "$expect" && return 0; sleep 1; done
    note "INFO  label after choosing $want: $(qaf subs) (attempt $attempt)"
  done; return 1; }

run_case(){ local kind="$1" aspect="$2"
  wait_activity PlayerActivity 15 && ok "$kind: player opened" || { bad "$kind: player did not open ($(resumed))"; return; }
  for i in $(seq 1 40); do e="$(qaf elapsed)"; [ -n "$e" ] && [ "$e" -ge 3 ] 2>/dev/null && break; sleep 1; done
  note "INFO  $kind: playing at ${e:-?}s"
  open_picker
  check "$kind: playing the test video (its subtitle tracks are listed)" 'picker_line | grep -q "Plain" && picker_line | grep -q "Bottom"'
  key BACK; sleep 1
  picker_line | grep -q "Plain" || { note "      $(picker_line)"; for i in 1 2 3; do in_activity PlayerActivity || break; key BACK; sleep 1; done; return; }
  # --- Plain (SRT) track: one-line and two-line cues ---
  choose_subs "Plain"
  lab="$(qaf subs)"; check "$kind: subtitle label shows the Plain track, English ($lab)" 'echo "$lab" | grep -q "English"'
  wait_qa controls false 8 && ok "$kind: controls hid by themselves" || bad "$kind: controls still up ($(qa_line))"
  one=""; two=""
  for i in $(seq 1 8); do b="$(sub_box "$kind-plain-hidden-$i" green)"; note "INFO  plain, controls hidden: $b"
    case "$(lines_of "$b")" in 1) [ -z "$one" ] && one="$b";; 2) [ -z "$two" ] && two="$b";; esac
    [ -n "$one" ] && [ -n "$two" ] && break; sleep 1.5; done
  check "$kind: one-line subtitle bottom-centre, ~5 % above the bottom ($one)" 'bottom_ok "$one"'
  check "$kind: two-line subtitle fully visible, same bottom line, grows upward ($two)" 'bottom_ok "$two" && same_bottom "$one" "$two" && [ "$(set -- $two; echo $2)" -lt "$(set -- $one; echo $2)" ]'
  check "$kind: picture keeps its shape ($aspect)" 'aspect_ok $aspect "$one"'
  ref="$one"
  b="$(shown_box "$kind-plain-controls" green)"; note "INFO  plain, controls shown ($(cat "$OUT/lc")): $b"
  check "$kind: Plain subtitles at the same place with the controls shown" '[ "$(cat "$OUT/lc")" = true ] && present "$b" && same_bottom "$b" "$ref" 8'
  wait_qa controls false 8
  for t in 1 4 8; do sleep $([ $t = 1 ] && echo 1 || echo 3)
    b="$(sub_box "$kind-plain-hidden-${t}s" green)"; note "INFO  plain, controls hidden ${t}s: $b"
    check "$kind: Plain subtitles visible ${t}s after the controls hid, same place" 'bottom_ok "$b" && same_bottom "$b" "$ref"'
  done
  # --- Bottom-positioned (ASS) track, like episode subtitle files ---
  choose_subs "Bottom"
  lab="$(qaf subs)"; check "$kind: subtitle label shows the Bottom track, French ($lab)" 'echo "$lab" | grep -q "French"'
  wait_qa controls false 8
  bref="$(sub_box "$kind-bottom-hidden" yellow)"; note "INFO  bottom, controls hidden: $bref"
  check "$kind: positioned (ASS) subtitles bottom-centre inside the safe area, not clipped" 'bottom_ok "$bref"'
  b="$(shown_box "$kind-bottom-controls" yellow)"; note "INFO  bottom, controls shown ($(cat "$OUT/lc")): $b"
  check "$kind: positioned subtitles at the same place with the controls shown" '[ "$(cat "$OUT/lc")" = true ] && present "$b" && same_bottom "$b" "$bref" 8'
  # --- showing / hiding the controls repeatedly keeps the choice and the place ---
  s1="$(qaf subs)"
  for i in 1 2 3; do key DPAD_UP; sleep 0.8; wait_qa controls false 8
    b="$(sub_box "$kind-cycle-$i" yellow)"
    check "$kind: show/hide cycle $i: subtitles drawn at the same place, choice kept ($s1)" 'bottom_ok "$b" && same_bottom "$b" "$bref" && [ "$(qaf subs)" = "$s1" ]'
  done
  # --- seeking keeps the choice and the place ---
  key DPAD_UP; sleep 0.5; key DPAD_DOWN; sleep 0.6; t0="$(qaf elapsed)"
  key DPAD_RIGHT; sleep 2.5; t1="$(qaf elapsed)"
  check "$kind: seeking still works (+10 s: ${t0}s -> ${t1}s)" '[ -n "$t0" ] && [ -n "$t1" ] && [ "$t1" -ge $((t0+8)) ]'
  wait_qa controls false 8; sleep 1
  b="$(sub_box "$kind-after-seek" yellow)"
  check "$kind: after seeking: same choice, same place" 'bottom_ok "$b" && same_bottom "$b" "$bref" && [ "$(qaf subs)" = "$s1" ]'
  # --- switching back to the Plain track: same place as before ---
  choose_subs "Plain"; wait_qa controls false 8; sleep 1
  b="$(sub_box "$kind-plain-again" green)"; y="$(sub_box "$kind-plain-again-y" yellow)"
  check "$kind: switching tracks back to Plain: English label, same place, previous track gone" 'echo "$(qaf subs)" | grep -q "English" && bottom_ok "$b" && same_bottom "$b" "$ref" && absent "$y"'
  # --- Off ---
  choose_subs "Off"
  lab="$(qaf subs)"; check "$kind: subtitle label shows Off ($lab)" 'echo "$lab" | grep -q "Off"'
  b="$(shown_box "$kind-off-controls" yellow)"; g="$(shown_box "$kind-off-controls-g" green)"
  check "$kind: Off: no subtitles with the controls shown" 'absent "$b" && absent "$g"'
  wait_qa controls false 8; sleep 2
  b="$(sub_box "$kind-off-hidden" yellow)"; g="$(sub_box "$kind-off-hidden-g" green)"
  check "$kind: Off: no subtitles with the controls hidden" 'absent "$b" && absent "$g"'
  for i in 1 2 3; do in_activity PlayerActivity || break; key BACK; sleep 1; done
}

# Point the QA demo server's movies and episodes at the test video.
adb shell am force-stop $PKG
adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" --ez demo_reset true > /dev/null
wait_activity HomeActivity 60 || bad "Home did not open ($(resumed))"
sleep 8   # let the demo reset finish before pointing the demo server at the test video
use_test_stream(){ local u=${1:-$TEST_STREAM}; for t in 1 2 3; do
  printf '%s' "{\"stream\":\"$u\"}" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; sleep 1
  adb shell "run-as $PKG cat files/demo_account.json" | grep -q "$u" && return 0; sleep 2; done; return 1; }
use_test_stream && ok "demo server points at the test video ($TEST_STREAM)" || bad "could not set the test video"
adb logcat -c
# Movie
key DPAD_LEFT DPAD_DOWN DPAD_CENTER; sleep 3
key DPAD_RIGHT DPAD_CENTER; wait_activity DetailsActivity 10; sleep 3
use_test_stream > /dev/null
key DPAD_CENTER
run_case movie 169
wait_activity DetailsActivity 8; key BACK; sleep 1.5
# Episode
key DPAD_LEFT; focus_to DPAD_UP "TV Shows" 4 > /dev/null; key DPAD_CENTER; sleep 2.5
key DPAD_CENTER; wait_activity DetailsActivity 10; sleep 4
focus_to DPAD_DOWN "Season 1" 4 > /dev/null; use_test_stream > /dev/null; key DPAD_DOWN; sleep 1.5
note "INFO  episode row focus: $(focused)"
for t in 1 2 3; do key DPAD_CENTER; wait_activity PlayerActivity 10 && break; done
run_case episode 169
# 4:3 movie (another title, so no resume prompt): pillarboxed, subtitles at the bottom of the picture
for i in 1 2 3 4; do in_activity HomeActivity && break; key BACK; sleep 1.5; done
use_test_stream "$TEST_STREAM_43" && ok "demo server points at the 4:3 test video" || bad "could not set the 4:3 test video"
adb shell am force-stop $PKG; adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null
wait_activity HomeActivity 60; sleep 4
key DPAD_LEFT DPAD_DOWN DPAD_CENTER; sleep 3
key DPAD_RIGHT DPAD_RIGHT DPAD_CENTER; wait_activity DetailsActivity 10; sleep 3
use_test_stream "$TEST_STREAM_43" > /dev/null
key DPAD_CENTER
run_case movie-4x3 43
crashes="$(adb logcat -d -b crash | grep -A1 'FATAL EXCEPTION' | grep -c "Process: $PKG")"
check "no Flix Town crash" '[ "$crashes" = 0 ]'
note ""; note "subtitles: $PASS passed, $FAIL failed"
