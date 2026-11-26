#!/usr/bin/env bash
# Workflow: .github/workflows/autoplay-recovery-qa.yml (QA build only; nothing is published).
# "This title couldn't be played" near the end of an episode: which stream fails, and does autoplay go on?
# QA build, offline demo catalog, local media server on the host (10.0.2.2:8000), a 2 Mbit/s 120 s episode
# (the player can't read it all ahead, so a broken connection is felt where it happens).
#   S1 manual playback from Details, then a normal end: autoplay starts the next episode
#   S2 the current episode's stream breaks 10 s before the end (connection dropped, every retry fails)
#   S3 the current episode ends normally, but the server refuses the next episode for 20 s
#      (an account at its connection limit while the previous stream is still counted)
#   S4 the stream breaks mid-way (not the end): the error card must still appear, nothing is skipped
# Results: $OUT/report.txt and $OUT/screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
APK=${APK:-app/build/outputs/apk/qa/app-qa.apk}
OUT=${QA_OUT:-qa/autoplay-recovery}; SHOTS=$OUT/screens
M=http://10.0.2.2:8000
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ local name="$1"; if eval "$2"; then ok "$name"; else bad "$name"; fi; }
shot(){ sleep "${2:-0.5}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am force-stop $PKG; adb logcat -b main -b system -c; adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
dump(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }
resumed(){ adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -1; }
in_activity(){ resumed | grep -q "$1"; }
wait_activity(){ local t=0; while [ $t -lt "$2" ]; do in_activity "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
# Home, choosing the profile on the "Who's watching?" screen whenever it opens.
to_home(){ local t=0; while [ $t -lt "${1:-60}" ]; do
    in_activity HomeActivity && return 0
    in_activity ProfilesActivity && { sleep 1.5; key DPAD_CENTER; sleep 2; }
    sleep 1; t=$((t+1)); done; in_activity HomeActivity; }
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
demo_live(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; }
stream(){ demo_live "{\"today\":\"2026-06-15\",\"stream\":\"$1\"}"; }
qa(){ adb logcat -d -s FlixTownQA:I | grep "$1" | tail -1; }
qaf(){ qa " player " | python3 -c 'import re,sys
l=sys.stdin.read();k=sys.argv[1]
m=re.search(k+r"=\[([^\]]*)\]",l) or re.search(k+r"=(\S+)",l)
print(m.group(1).strip() if m else "")' "$1"; }
ERR="This title couldn't be played"
started(){ local t=0; while [ $t -lt 60 ]; do
    [ "$(qaf card)" = "Continue watching?" ] && { key DPAD_RIGHT DPAD_CENTER; sleep 2; }
    [ "$(qaf playing)" = "true" ] && [ "$(qaf card)" != "Continue watching?" ] && { sleep 1; return 0; }
    sleep 0.5; t=$((t+1)); done; return 1; }
back_home(){ for i in 1 2 3 4 5 6; do in_activity HomeActivity && return 0; key BACK; sleep 1.2; done; to_home 20; }
# TV Shows › first show › Details › Season › first episode (opened by hand, as a viewer would)
open_episode(){ back_home; focus_to DPAD_LEFT "Home" 8 > /dev/null; focus_to DPAD_DOWN "TV Shows" 4 > /dev/null; key DPAD_CENTER; sleep 2.5
  key DPAD_CENTER; wait_activity DetailsActivity 10; sleep 4; focus_to DPAD_DOWN "Season" 5 > /dev/null; key DPAD_DOWN; sleep 0.8; key DPAD_CENTER
  wait_activity PlayerActivity 10; started; }
player_open(){ for a in 1 2; do open_episode && in_activity PlayerActivity && return 0
  note "INFO  opening the episode, attempt $a, did not reach playback ($(resumed))"; shot "open-fail-$a" 0.1; fresh; done; return 1; }
# Go to time through Player options (minutes, seconds), checked once more if the jump did not happen
go_to(){ key DPAD_UP; sleep 0.4; key DPAD_UP; sleep 0.4; key DPAD_CENTER; sleep 1.2
  focus_to DPAD_DOWN "Go to time" 3 > /dev/null; key DPAD_CENTER; sleep 1.2
  for f in "$@"; do for (( i=0; i<${#f}; i++ )); do adb shell input keyevent "KEYCODE_${f:$i:1}"; sleep 0.3; done; done
  focus_to DPAD_RIGHT "Go" 4 > /dev/null; key DPAD_CENTER; sleep 2; }
go_to_ok(){ local want=$(( 10#$1*60 + 10#$2 )) e a; for a in 1 2; do go_to "$1" "$2"; e=$(qaf elapsed)
    [ "${e:-0}" -ge $((want-2)) ] && [ "${e:-0}" -le $((want+20)) ] && return 0
    note "INFO  Go to time $1:$2 attempt $a reached ${e:-?} s; dialog=$(qaf dialog)"
    [ "$(qaf dialog)" = "true" ] && { key BACK; sleep 1; }; done; return 1; }
fresh(){ launch; to_home 40; sleep 4; }
# Waits up to N s for: "next" (another episode playing past 3 s) or "error" (the error card). Prints which.
outcome(){ local first=$1 t=0; while [ $t -lt "$2" ]; do
    [ "$(qaf card)" = "$ERR" ] && { echo error; return; }
    local ep="$(qaf episode)" e="$(qaf elapsed)"
    [ -n "$ep" ] && [ "$ep" != "$first" ] && [ "$(qaf playing)" = "true" ] && [ "${e:-0}" -ge 3 ] && { echo next; return; }
    sleep 1; t=$((t+1)); done; echo "timeout (card=[$(qaf card)] episode=$(qaf episode) playing=$(qaf playing) elapsed=$(qaf elapsed))"; }
evidence(){ # what the player logged and what the server saw, for the report
  adb logcat -d -s FlixTownPlayer:W | grep "Playback failed" | tail -4 | sed 's/^.*Playback failed/INFO  player error: Playback failed/' >> "$REPORT"
  adb logcat -d -s FlixTownQA:I | grep "playback_recovery" | tail -6 | sed 's/^.*playback_recovery/INFO  recovery:/' >> "$REPORT"
  grep -E '^(drop|refuse|arm)' media.log 2>/dev/null | tail -6 | sed 's/^/INFO  server: /' >> "$REPORT"; }

adb install -r "$APK" > /dev/null || { note "FAIL  install"; exit 1; }
adb shell settings put system screen_off_timeout 1800000 || true
stream "$M/ep.mkv"
launch --ez demo_reset true; sleep 20; to_home 60 && ok "signed in (demo) and reached Home past the profile picker" || bad "Home not reached ($(resumed))"
sleep 4

control(){ key DPAD_DOWN DPAD_DOWN DPAD_UP; for n in $(seq 1 8); do [ "$(qaf focus)" = "$1" ] && return 0; key DPAD_RIGHT; done; note "      could not reach the $1 control"; return 1; }
# T  the Audio and Subtitles panels (several languages, one track this TV can't play)
note ""; note "== T Audio and Subtitles panels =="
stream "$M/tracks.mkv"; fresh
player_open; sleep 3
control audio; key DPAD_CENTER; sleep 1.5; shot T1-audio-panel 0.3
note "INFO  $(qa ' picker Audio' | sed 's/^.*picker/picker/')"
key DPAD_DOWN; shot T2-audio-panel-focus-down 0.4
key BACK; sleep 1.2
control subs; key DPAD_CENTER; sleep 1.5; shot T3-subtitles-panel 0.3
note "INFO  $(qa ' picker Subtitles' | sed 's/^.*picker/picker/')"
key DPAD_DOWN; key DPAD_CENTER; sleep 2; shot T4-subtitles-english 0.3
check "T the Audio panel opened with every audio track" 'qa " picker Audio" | grep -q "Spanish"'
check "T the Subtitles panel opened with every subtitle track" 'qa " picker Subtitles" | grep -q "French"'
check "T choosing a subtitle closes the panel and turns subtitles on" '[ "$(qaf dialog)" = "false" ] && qaf subs | grep -qv "^Off$"'
key BACK; sleep 1

# S1 manual playback, then a normal end
note ""; note "== S1 manual playback and a normal end =="
stream "$M/ep.mkv"; fresh
player_open; ep1="$(qaf episode)"
check "S1 an episode opened by hand from Details plays (episode $ep1)" '[ -n "$ep1" ] && [ "$(qaf playing)" = "true" ]'
go_to_ok 1 45; r=$(outcome "$ep1" 60); note "INFO  S1 outcome: $r"; evidence; shot S1-after-end 0.1
check "S1 a normal end autoplays the next episode" '[ "$r" = next ]'

# S2 the current stream breaks 10 s before the end, for good
note ""; note "== S2 the current stream breaks near the end =="
stream "$M/cut/ep.mkv"; fresh
player_open; ep2="$(qaf episode)"
go_to_ok 1 35; r=$(outcome "$ep2" 90); note "INFO  S2 outcome: $r"; evidence; shot S2-outcome 0.1
check "S2 a stream failure in the closing seconds still autoplays the next episode (no error card)" '[ "$r" = next ]'

# S3 normal end, but the server refuses the next episode for 20 s
note ""; note "== S3 the next episode is refused for 20 s =="
curl -s "http://127.0.0.1:8000/arm?refuse=0" > /dev/null
stream "$M/refuse/ep.mkv"; fresh
player_open; ep3="$(qaf episode)"
go_to_ok 1 40
t=0; while [ $t -lt 40 ] && [ "$(qaf elapsed)" -lt 116 ] 2>/dev/null; do sleep 0.5; t=$((t+1)); done
curl -s "http://127.0.0.1:8000/arm?refuse=20" > /dev/null; note "INFO  S3 refusal armed at $(qaf elapsed) s of 120"
r=$(outcome "$ep3" 90); note "INFO  S3 outcome: $r"; evidence; shot S3-outcome 0.1
check "S3 the next episode starts once the server accepts it again (no error card)" '[ "$r" = next ]'
curl -s "http://127.0.0.1:8000/arm?refuse=0" > /dev/null

# S4 the stream breaks mid-way, for good: nothing may be skipped
note ""; note "== S4 the stream breaks mid-way =="
# A clean start (no saved progress), so the first episode plays from 0:00 into the break at 1:00 with a
# next episode queued: the case where skipping ahead would be wrong.
launch --ez demo_reset true; sleep 20; to_home 60; sleep 4
stream "$M/cut50/ep.mkv"; fresh
player_open; ep4="$(qaf episode)"; note "INFO  S4 playing episode $ep4 from $(qaf elapsed) s"
r=$(outcome "$ep4" 150); note "INFO  S4 outcome: $r at $(qaf elapsed) s"; evidence; shot S4-outcome 0.1
check "S4 a lasting failure mid-way still shows the error card and does not skip the episode" '[ "$r" = error ] && [ "$(qaf episode)" = "$ep4" ] && [ "$(qaf elapsed)" -ge 50 ] && [ "$(qaf elapsed)" -le 70 ]'
check "S4 the error card explains the connection problem" 'qaf cardmsg | grep -q "server didn"'

crash="$(adb logcat -d -b crash | grep -A1 'FATAL EXCEPTION' | grep -c "Process: $PKG")"
check "no Flix Town crash" '[ "$crash" = 0 ]'
note ""; note "autoplay recovery: $PASS passed, $FAIL failed"
