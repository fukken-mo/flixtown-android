#!/usr/bin/env bash
# Subtitles in the player (QA build) with a local test video whose subtitle cues are known:
#   track "Plain"  (SRT, green):  one continuous cue after another, the usual bottom position
#   track "Bottom" (ASS, yellow): cues placed by the file at the very bottom edge ({\an2\pos(640,720)}),
#                                 like many episode subtitle tracks
# Every cue lasts 4 s and they run back to back for 5 minutes, so they span the 3 s auto-hide of the
# controls. Subtitle pixels are found by colour in screenshots (the video is black; the controls are
# white/red), with the controls shown and after they hid, for a movie and for an episode.
# Needs TEST_STREAM (e.g. http://10.0.2.2:8000/subs.mkv). Results: $OUT/report.txt, $OUT/screens/*.png
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

# Screenshot -> where pixels of a colour are: "x0 y0 x1 y1 W H count" (count 0 = none).
sub_box(){ adb exec-out screencap > "$OUT/s.raw"; qaf controls > "$OUT/lc"
  adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$SHOTS/$1.png" > /dev/null
  python3 - "$OUT/s.raw" "$2" <<'PYEOF'
import sys, struct
d=open(sys.argv[1],'rb').read(); w,h=struct.unpack('<II',d[:8]); hdr=len(d)-w*h*4   # 12 or 16 byte header
want=sys.argv[2]; x0=y0=10**9; x1=y1=-1; n=0
for y in range(0,h):
    row=d[hdr+y*w*4: hdr+(y+1)*w*4]
    for x in range(0,w,2):                   # every other pixel is plenty for text
        R,G,B=row[4*x],row[4*x+1],row[4*x+2]
        hit = (G>170 and R<110 and B<110) if want=='green' else (R>170 and G>170 and B<110)
        if hit: n+=1; x0=min(x0,x); x1=max(x1,x); y0=min(y0,y); y1=max(y1,y)
print(x0,y0,x1,y1,w,h,n) if n else print(0,0,0,0,w,h,0)
PYEOF
}
# A box is "safe" when it lies inside the 5 % TV safe margins.
safe(){ set -- $1; [ "$7" -gt 20 ] && [ "$1" -ge $(($5*5/100)) ] && [ "$3" -le $(($5*95/100)) ] && [ "$2" -ge $(($6*5/100)) ] && [ "$4" -le $(($6*95/100)) ]; }
present(){ set -- $1; [ "$7" -gt 20 ]; }
absent(){ set -- $1; [ "$7" -le 3 ]; }
# With the controls up, the subtitles sit above them (actions row + progress line = 122 dp from the bottom).
above_controls(){ set -- $1; [ "$7" -gt 20 ] && [ "$4" -le $(($6 - $6*122/540)) ]; }

# Capture with the controls up: shown right before (the auto-hide is 3 s), retried if they had gone.
shown_box(){ local r; for t in 1 2 3; do key DPAD_UP; sleep 0.5; r="$(sub_box "$1" "$2")"; [ "$(cat "$OUT/lc")" = true ] && break; done; echo "$r"; }
pickers(){ adb logcat -d -s FlixTownQA:I | grep -c " picker Subtitles"; }
# Opens the Subtitles menu and waits until it is really open (its QA line appears).
open_picker(){ local n0; n0="$(pickers)"
  # Deterministic start: controls hidden, then Up opens them with Play focused (PlayerActivity).
  if [ "$(qaf controls)" = true ]; then key BACK; wait_qa controls false 6; fi
  key DPAD_UP; wait_qa focus play 5 || note "INFO  after Up: $(qa_line)"
  # Play -> Audio -> Subtitles, one step at a time, each confirmed by the QA state before the next.
  for want in audio subs; do [ "$(qaf focus)" = subs ] && break
    adb shell input keyevent KEYCODE_DPAD_RIGHT; wait_qa focus "$want" 4 || note "INFO  expected focus $want: $(qa_line)"; done
  [ "$(qaf focus)" = subs ] || return 1
  key DPAD_CENTER
  for i in $(seq 1 12); do [ "$(pickers)" -gt "$n0" ] && return 0; sleep 0.5; done; return 1; }
# Picks a subtitle track by label through the player's Subtitles menu; checks the label after, once more if needed.
choose_subs(){ local want="$1" expect
  case "$want" in Plain) expect=English;; Bottom) expect=French;; *) expect=Off;; esac
  for attempt in 1 2; do
    open_picker || { note "INFO  Subtitles menu did not open (attempt $attempt)"; key BACK; sleep 1; continue; }
    sleep 0.8
    local line; line="$(picker_line)"; note "INFO  $line"
    local moves; moves="$(python3 -c '
import re,sys
l=sys.argv[1]; want=sys.argv[2]
m=re.search(r"picker Subtitles: (.*) selected=(\d+)",l)
labels=m.group(1).split("|"); sel=int(m.group(2))
t=[i for i,x in enumerate(labels) if want in x][0]
print(("DOWN "*(t-sel) if t>sel else "UP "*(sel-t)).strip())' "$line" "$want")"
    for k in $moves; do key "DPAD_$k"; done
    key DPAD_CENTER; sleep 1.5
    qaf subs | grep -q "$expect" && return 0
    note "INFO  label after choosing $want: $(qaf subs) (attempt $attempt)"
  done; return 1; }

run_case(){ local kind="$1"
  wait_activity PlayerActivity 15 && ok "$kind: player opened" || { bad "$kind: player did not open ($(resumed))"; return; }
  for i in $(seq 1 40); do e="$(qaf elapsed)"; [ -n "$e" ] && [ "$e" -ge 3 ] 2>/dev/null && break; sleep 1; done
  note "INFO  $kind: playing at ${e:-?}s"
  open_picker; sleep 0.8
  check "$kind: playing the test video (its subtitle tracks are listed)" 'picker_line | grep -q "Plain" && picker_line | grep -q "Bottom"'
  key BACK; sleep 1
  picker_line | grep -q "Plain" || { note "      $(picker_line)"; for i in 1 2 3; do in_activity PlayerActivity || break; key BACK; sleep 1; done; return; }
  # --- Plain (SRT) track ---
  choose_subs "Plain"
  lab="$(qaf subs)"; check "$kind: subtitle label shows the Plain track, English ($lab)" 'echo "$lab" | grep -q "English"'
  b="$(shown_box "$kind-plain-controls" green)"; note "INFO  plain, controls shown ($(cat "$OUT/lc")): $b"
  check "$kind: Plain subtitles visible with the controls shown, above the controls" '[ "$(cat "$OUT/lc")" = true ] && above_controls "$b"'
  wait_qa controls false 8 && ok "$kind: controls hid by themselves" || bad "$kind: controls still up ($(qa_line))"
  for t in 1 4 8; do sleep $([ $t = 1 ] && echo 1 || echo 3)
    b="$(sub_box "$kind-plain-hidden-${t}s" green)"; note "INFO  plain, controls hidden ${t}s: $b"
    check "$kind: Plain subtitles still visible ${t}s after the controls hid, inside the TV safe area" 'safe "$b"'
  done
  # --- Bottom-positioned (ASS) track, like episode subtitle files ---
  choose_subs "Bottom"
  lab="$(qaf subs)"; check "$kind: subtitle label shows the Bottom track, French ($lab)" 'echo "$lab" | grep -q "French"'
  b="$(shown_box "$kind-bottom-controls" yellow)"; note "INFO  bottom, controls shown ($(cat "$OUT/lc")): $b"
  check "$kind: bottom-positioned subtitles visible with the controls shown, above the controls" '[ "$(cat "$OUT/lc")" = true ] && above_controls "$b"'
  wait_qa controls false 8
  for t in 1 4 8; do sleep $([ $t = 1 ] && echo 1 || echo 3)
    b="$(sub_box "$kind-bottom-hidden-${t}s" yellow)"; note "INFO  bottom, controls hidden ${t}s: $b"
    check "$kind: bottom-positioned subtitles still visible ${t}s after the controls hid, inside the TV safe area" 'safe "$b"'
  done
  # --- showing / hiding the controls keeps the choice ---
  s1="$(qaf subs)"
  for i in 1 2 3; do key DPAD_UP; sleep 0.8; wait_qa controls false 8; done
  check "$kind: three show/hide cycles keep the subtitle choice ($s1)" '[ "$(qaf subs)" = "$s1" ]'
  b="$(sub_box "$kind-after-cycles" yellow)"
  check "$kind: subtitles still drawn after the cycles" 'safe "$b"'
  # --- seeking keeps the choice and the subtitles ---
  key DPAD_UP; sleep 0.5; key DPAD_DOWN; sleep 0.6; t0="$(qaf elapsed)"
  key DPAD_RIGHT; sleep 2.5; t1="$(qaf elapsed)"
  check "$kind: seeking still works (+10 s: ${t0}s -> ${t1}s)" '[ -n "$t0" ] && [ -n "$t1" ] && [ "$t1" -ge $((t0+8)) ]'
  wait_qa controls false 8
  b="$(sub_box "$kind-after-seek" yellow)"
  check "$kind: subtitles shown again after seeking, same choice" 'safe "$b" && [ "$(qaf subs)" = "$s1" ]'
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
use_test_stream(){ for t in 1 2 3; do
  printf '%s' "{\"stream\":\"$TEST_STREAM\"}" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; sleep 1
  adb shell "run-as $PKG cat files/demo_account.json" | grep -q "$TEST_STREAM" && return 0; sleep 2; done; return 1; }
use_test_stream && ok "demo server points at the test video ($TEST_STREAM)" || bad "could not set the test video"
adb logcat -c
# Movie
key DPAD_LEFT DPAD_DOWN DPAD_CENTER; sleep 3
key DPAD_RIGHT DPAD_CENTER; wait_activity DetailsActivity 10; sleep 3
use_test_stream > /dev/null
key DPAD_CENTER
run_case movie
wait_activity DetailsActivity 8; key BACK; sleep 1.5
# Episode
key DPAD_LEFT; focus_to DPAD_UP "TV Shows" 4 > /dev/null; key DPAD_CENTER; sleep 2.5
key DPAD_CENTER; wait_activity DetailsActivity 10; sleep 4
focus_to DPAD_DOWN "Season 1" 4 > /dev/null; use_test_stream > /dev/null; key DPAD_DOWN; sleep 1.5
note "INFO  episode row focus: $(focused)"
for t in 1 2 3; do key DPAD_CENTER; wait_activity PlayerActivity 10 && break; done
run_case episode
crashes="$(adb logcat -d -b crash | grep -A1 'FATAL EXCEPTION' | grep -c "Process: $PKG")"
check "no Flix Town crash" '[ "$crashes" = 0 ]'
note ""; note "subtitles: $PASS passed, $FAIL failed"
