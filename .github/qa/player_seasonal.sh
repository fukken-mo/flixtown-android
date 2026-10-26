#!/usr/bin/env bash
# Workflow: .github/workflows/seasonal-player-preview.yml (Preview only; nothing is published).
# Focused QA (QA build, offline demo server + local media server on the host at 10.0.2.2:8000):
#   A. seasonal startup splash: built-in schedule offline, downloaded pictures, holiday dates and the
#      year boundary, priority tie, Easter/Thanksgiving, stale and bad pictures, malformed / disabled
#      schedule, skip with OK, timing, intro video, return from background, rapid reopen
#   B. player: error replacing Up next (no countdown), Try again / Report / Back, interrupted episode,
#      seeking (tap, hold, end guard, paused, cancel), Go to time (+ cancel), unseekable stream,
#      normal movie end, subtitle position (16:9, letterbox, 4:3; SRT and positioned ASS; controls
#      shown and hidden; Extra large)
# Results: $OUT/report.txt and $OUT/screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
APK=${APK:-app/build/outputs/apk/qa/app-qa.apk}
OUT=${QA_OUT:-qa/player-seasonal}; SHOTS=$OUT/screens
M=http://10.0.2.2:8000
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
shot(){ sleep "${2:-0.5}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am force-stop $PKG; adb logcat -b main -b system -c; adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
dump(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }
has(){ dump; grep -q -- "$1" "$OUT/ui.xml"; }
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
demo_live(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; }
set_prefs(){ # name=value pairs into the app's preferences (app stopped first)
  adb shell am force-stop $PKG; sleep 0.5
  adb shell "run-as $PKG cat shared_prefs/flix.xml" > "$OUT/flix.xml" 2>/dev/null
  python3 - "$OUT/flix.xml" "$@" <<'EOF'
import sys,re
p=sys.argv[1];s=open(p).read()
for kv in sys.argv[2:]:
    k,t,v=kv.split(':',2)
    s=re.sub(r'\s*<[a-z]+ name="%s"[^>]*?(/>|>[^<]*</[a-z]+>)'%re.escape(k),'',s)
    tag='<boolean name="%s" value="%s" />'%(k,v) if t=='b' else '<float name="%s" value="%s" />'%(k,v) if t=='f' else '<string name="%s">%s</string>'%(k,v)
    s=s.replace('</map>','    %s\n</map>'%tag)
open(p,'w').write(s)
EOF
  adb shell "run-as $PKG sh -c 'cat > shared_prefs/flix.xml'" < "$OUT/flix.xml"; }
qa(){ adb logcat -d -s FlixTownQA:I | grep "$1" | tail -1; }
qaf(){ qa " player " | python3 -c 'import re,sys
l=sys.stdin.read();k=sys.argv[1]
m=re.search(k+r"=\[([^\]]*)\]",l) or re.search(k+r"=(\S+)",l)
print(m.group(1).strip() if m else "")' "$1"; }
wait_qa(){ local t=0; while [ $t -lt "$3" ]; do [ "$(qaf "$1")" = "$2" ] && return 0; sleep 1; t=$((t+1)); done; return 1; }
splash(){ qa " splash event="; }
back_home(){ for i in 1 2 3 4 5 6; do in_activity HomeActivity && return 0; key BACK; sleep 1.2; done; in_activity HomeActivity; }
open_movie(){ back_home; focus_to DPAD_LEFT "Home" 8 > /dev/null; focus_to DPAD_DOWN "Movies" 4 > /dev/null; key DPAD_CENTER; sleep 2.5
  key DPAD_CENTER; wait_activity DetailsActivity 10; sleep 3; key DPAD_CENTER; wait_activity PlayerActivity 10; sleep 4
  if [ "$(qaf card)" = "Continue watching?" ]; then key DPAD_RIGHT DPAD_CENTER; sleep 3; fi; }
open_episode(){ back_home; focus_to DPAD_LEFT "Home" 8 > /dev/null; focus_to DPAD_DOWN "TV Shows" 4 > /dev/null; key DPAD_CENTER; sleep 2.5
  key DPAD_CENTER; wait_activity DetailsActivity 10; sleep 4; focus_to DPAD_DOWN "Season" 5 > /dev/null; key DPAD_DOWN; sleep 0.8; key DPAD_CENTER
  wait_activity PlayerActivity 10; sleep 4; if [ "$(qaf card)" = "Continue watching?" ]; then key DPAD_RIGHT DPAD_CENTER; sleep 3; fi; }
leave_player(){ for i in 1 2 3; do in_activity PlayerActivity || break; key BACK; sleep 1.2; done; }
elapsed(){ qaf elapsed; }
# Go to time through Player options: digits for each field ("4" "30" = 4:30), then Go.
go_to(){ key DPAD_UP; sleep 0.4; key DPAD_UP; sleep 0.4; key DPAD_CENTER; sleep 1.2
  focus_to DPAD_DOWN "Go to time" 3 > /dev/null; key DPAD_CENTER; sleep 1.2
  for f in "$@"; do for (( i=0; i<${#f}; i++ )); do adb shell input keyevent "KEYCODE_${f:$i:1}"; sleep 0.3; done; done
  focus_to DPAD_RIGHT "Go" 4 > /dev/null; key DPAD_CENTER; sleep 2; }
# Hold a D-pad key for N seconds through the emulator's input device (Android generates the repeats).
hold(){ local code=$1 secs=$2 dev
  dev=$(adb shell getevent -lp 2>/dev/null | awk '/add device/{d=$4} /KEY_RIGHT/{print d; exit}')
  [ -z "$dev" ] && return 1
  adb shell "sendevent $dev 1 $code 1; sendevent $dev 0 0 0; sleep $secs; sendevent $dev 1 $code 0; sendevent $dev 0 0 0"; }
# Subtitle colour box on screen: "x0 y0 x1 y1 W H count"
sub_box(){ adb exec-out screencap > "$OUT/s.raw"; adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$SHOTS/$1.png" > /dev/null
  python3 - "$OUT/s.raw" "$2" <<'EOF'
import sys,struct
d=open(sys.argv[1],'rb').read();w,h=struct.unpack('<II',d[:8]);hdr=len(d)-w*h*4;want=sys.argv[2];x0=y0=10**9;x1=y1=-1;n=0
for y in range(h):
    row=d[hdr+y*w*4:hdr+(y+1)*w*4]
    for x in range(0,w,2):
        R,G,B=row[4*x],row[4*x+1],row[4*x+2]
        hit=(G>100 and R<G//2 and B<G//2) if want=='green' else (R>100 and G>100 and B<min(R,G)//2 and abs(R-G)<60)
        if hit:n+=1;x0=min(x0,x);x1=max(x1,x);y0=min(y0,y);y1=max(y1,y)
print(x0,y0,x1,y1,w,h,n) if n else print(0,0,0,0,w,h,0)
EOF
}
frac(){ python3 -c "import sys;print(round(int(sys.argv[1])/int(sys.argv[2]),3))" "$1" "$2"; }
center_rgb(){ adb exec-out screencap > "$OUT/c.raw"; python3 - "$OUT/c.raw" <<'EOF'
import sys,struct
d=open(sys.argv[1],'rb').read();w,h=struct.unpack('<II',d[:8]);hdr=len(d)-w*h*4;o=hdr+((h//2)*w+w//2)*4
print(d[o],d[o+1],d[o+2])
EOF
}
home_shown_ms(){ adb logcat -d -v epoch | python3 -c '
import re,sys
s=h=None
for l in sys.stdin:
    t=l.split()[0] if l.split() else ""
    if s is None and " splash event=" in l: s=float(t)
    if s is not None and h is None and "Displayed" in l and "HomeActivity" in l and float(t)>=s: h=float(t)
print(int((h-s)*1000) if s and h else -1)'; }

adb install -r "$APK" > /dev/null || { bad "install"; exit 1; }
adb shell settings put system screen_off_timeout 1800000 || true

# ====================== A. Seasonal splash ======================
ART="$M/art"
CFG='{"version":5,"enabled":true,"display_ms":3000,"events":[
 {"id":"halloween","name":"Halloween","rule":"fixed","start":"10-15","end":"10-31","priority":40,"image":"'$ART'/green.jpg"},
 {"id":"christmas","name":"Christmas","rule":"fixed","start":"12-01","end":"12-26","priority":50,"image":"'$ART'/corrupt.jpg"},
 {"id":"new_year","name":"New Year","rule":"fixed","start":"12-27","end":"01-03","priority":60,"image":"'$ART'/blue.jpg"},
 {"id":"st_patricks","name":"St. Patrick","rule":"fixed","start":"03-13","end":"03-18","priority":40},
 {"id":"valentines","name":"Valentine","rule":"fixed","start":"02-10","end":"02-15","priority":40,"image":"'$ART'/small.jpg"},
 {"id":"independence_day","name":"July 4","rule":"fixed","start":"07-01","end":"07-05","priority":40,"image":"'$M'/missing/x.jpg"},
 {"id":"easter","name":"Easter","rule":"easter","start_offset":-6,"end_offset":1,"priority":45},
 {"id":"thanksgiving","name":"Thanksgiving","rule":"thanksgiving","start_offset":-3,"end_offset":1,"priority":40},
 {"id":"tie_b","name":"Tie B","rule":"fixed","start":"08-10","end":"08-12","priority":40},
 {"id":"tie_a","name":"Tie A","rule":"fixed","start":"08-10","end":"08-12","priority":40}]}'
CFG=$(printf '%s' "$CFG" | tr -d '\n')
live(){ demo_live "{\"today\":\"$1\"${2:+,\"seasonal\":$2}}"; }
splash_is(){ local t=0; while [ $t -lt 20 ]; do splash | grep -q "event=$1 image=$2" && return 0; sleep 0.5; t=$((t+1)); done; return 1; }
relaunch_today(){ live "$1" "$CFG"; launch --ez demo_seasonal_refresh true; wait_activity HomeActivity 30; sleep 6; live "$1" "$CFG"; launch; }

launch --ez demo_reset true
wait_activity HomeActivity 60 && ok "A0 signed in (QR) and Home opened" || bad "A0 Home did not open ($(resumed))"
note "INFO  first launch: $(splash)"
check "A1 first launch, no panel schedule (404): built-in schedule, normal background" 'splash | grep -q "image=false"'
sleep 4
relaunch_today 2026-10-20
splash_is halloween true && ok "A2 Halloween picture downloaded in the background and shown on the next launch" || bad "A2 $(splash)"
sleep 0.8; shot A2-halloween 0.1; rgb="$(center_rgb)"; note "INFO  centre pixel: $rgb"
check "A2 the picture fills the screen (green test picture at the centre)" 'set -- $rgb; [ "$2" -gt 120 ] && [ "$1" -lt 90 ]'
check "A2 nothing drawn over the picture (the logo is hidden once it shows)" 'qa "splash image_shown event=halloween" | grep -q halloween'
wait_activity HomeActivity 15; ms=$(home_shown_ms); note "INFO  splash to Home: ${ms} ms (display 3000 ms)"
check "A3 splash stays for the display time, then Home (2.6–6 s)" '[ "$ms" -ge 2600 ] && [ "$ms" -le 6000 ]'
launch; t=0; while [ $t -lt 20 ] && [ -z "$(splash)" ]; do sleep 0.25; t=$((t+1)); done; key DPAD_CENTER; wait_activity HomeActivity 15; ms=$(home_shown_ms); note "INFO  with OK pressed: ${ms} ms"
check "A3 OK skips the splash (Home in under 2.5 s)" '[ "$ms" -ge 0 ] && [ "$ms" -lt 2500 ]'
for c in "2026-12-26:christmas:false" "2026-12-27:new_year:true" "2026-12-31:new_year:true" "2027-01-03:new_year:true" "2027-01-04:default:false" \
         "2026-03-15:st_patricks:false" "2027-02-12:valentines:false" "2027-07-03:independence_day:false" "2027-08-11:tie_a:false" \
         "2027-03-28:easter:false" "2027-03-30:default:false" "2027-11-25:thanksgiving:false" "2026-06-15:default:false"; do
  IFS=: read -r day ev img <<< "$c"; relaunch_today "$day"
  splash_is "$ev" "$img" && ok "A4 $day → $ev (picture shown: $img)" || bad "A4 $day expected $ev/$img, got: $(splash)"
  wait_activity HomeActivity 15
done
note "INFO  christmas = corrupt file, valentines = 800×450, July 4 = 404, St. Patrick = no picture (Halloween's cached picture is not reused)"
# malformed schedule keeps the last good one; disabled schedule shows the normal splash
relaunch_today 2026-10-20; wait_activity HomeActivity 15; sleep 2   # Halloween picture fetched again (other dates pruned it)
live 2026-10-20 '{"_malformed":true}'; launch --ez demo_seasonal_refresh true; wait_activity HomeActivity 30; sleep 5
live 2026-10-20 '{"_malformed":true}'; launch
splash_is halloween true && ok "A5 malformed schedule ignored: the last good schedule and picture stay" || bad "A5 $(splash)"
wait_activity HomeActivity 15
OFF=$(printf '%s' "$CFG" | sed 's/"enabled":true,"display_ms"/"enabled":false,"display_ms"/')
live 2026-10-20 "$OFF"; launch --ez demo_seasonal_refresh true; wait_activity HomeActivity 30; sleep 5; live 2026-10-20 "$OFF"; launch
splash_is default false && ok "A6 seasonal splash turned off in the panel: normal splash" || bad "A6 $(splash)"
wait_activity HomeActivity 15
# back from the background: no splash again
live 2026-10-20 "$CFG"; launch --ez demo_seasonal_refresh true; wait_activity HomeActivity 30; sleep 6
adb logcat -b main -c; key HOME; sleep 2; adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null; sleep 4
check "A7 returning from the background goes back to Home without the splash" 'in_activity HomeActivity && [ -z "$(splash)" ]'
# rapid reopen
adb shell am force-stop $PKG; adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null; sleep 0.3
adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null; sleep 9
homes=$(adb shell dumpsys activity activities | grep "Hist #" | grep -c "com.flixtown.tv.HomeActivity")
check "A8 opening twice quickly leaves one Home ($homes)" '[ "$homes" = 1 ]'
# intro video enabled: splash only as its loading frame, then the intro
launch --ez demo_intro true; sleep 1
t=0; while [ $t -lt 12 ] && ! has "Skip intro"; do sleep 1; t=$((t+1)); done
check "A9 intro video enabled: the intro follows the splash (Skip intro shown)" 'has "Skip intro"'
key DPAD_CENTER; wait_activity HomeActivity 15
crashA="$(adb logcat -d -b crash | grep -A1 'FATAL EXCEPTION' | grep -c "Process: $PKG")"
check "A no Flix Town crash during the splash checks" '[ "$crashA" = 0 ]'
live 2026-06-15 '{}'

# ====================== B. Player ======================
stream(){ demo_live "{\"today\":\"2026-06-15\",\"stream\":\"$1\"}"; }
# B1. Error replacing Up next near the end of an episode (connection dropped at 95 %)
stream "$M/cut/subs.mkv"; adb logcat -b main -c
open_episode
go_to 4 32
note "INFO  after Go to time: $(qa ' player ' | grep -o 'elapsed=[0-9]*')"
t=0; while [ $t -lt 60 ] && [ "$(qaf card)" != "This title couldn't be played" ]; do sleep 1; t=$((t+1)); done
saw_next=$(adb logcat -d -s FlixTownQA:I | grep -c "card=\[Up next\]")
note "INFO  Up next shown before the failure: $saw_next line(s); card now: $(qaf card) / $(qaf cardmsg)"
check "B1 the stream failure near the end shows the error card" '[ "$(qaf card)" = "This title couldn'"'"'t be played" ]'
check "B1 the Up next card was showing before (the case from the screenshot)" '[ "$saw_next" -ge 1 ]'
sleep 2.5; shot B1-error-card 0.1
check "B1 the error card has no 'Starts in' countdown (2.5 s later)" '! qaf cardmsg | grep -q "Starts in"'
check "B1 Try again, Report a problem and Back" 'has "Try again" && has "Report a problem" && has "Back"'
focus_to DPAD_RIGHT "Report a problem" 3 > /dev/null; key DPAD_CENTER; sleep 1.5
check "B1 Report a problem opens the report menu" 'qa " picker Report a problem" | grep -q Report'
key BACK; sleep 1.5
check "B1 cancelling the report returns to the same error card" '[ "$(qaf card)" = "This title couldn'"'"'t be played" ] && ! qaf cardmsg | grep -q "Starts in"'
focus_to DPAD_LEFT "Try again" 3 > /dev/null; key DPAD_CENTER; sleep 2
t=0; while [ $t -lt 40 ] && [ "$(qaf card)" != "This title couldn't be played" ]; do sleep 1; t=$((t+1)); done
check "B1 Try again retries; the failure shows the error card again, still without a countdown" '[ "$(qaf card)" = "This title couldn'"'"'t be played" ] && ! qaf cardmsg | grep -q "Starts in"'
key BACK; sleep 2
check "B1 Back on the error card leaves playback (not just the countdown)" '! in_activity PlayerActivity'
note "INFO  Continue Watching after a failure in the last 30 s: $(adb shell "run-as $PKG cat shared_prefs/flix.xml" | grep -o 'name="resume_episode_series:[0-9]*">[^<]*' | head -1)"
# B2. Interrupted mid-way (50 %): not marked watched, position kept
stream "$M/cut50/subs.mkv"; adb logcat -b main -c
open_episode; ep="$(qaf episode)"
go_to 2 20
t=0; while [ $t -lt 60 ] && [ "$(qaf card)" != "This title couldn't be played" ]; do sleep 1; t=$((t+1)); done
check "B2 a mid-way failure shows the error card" '[ "$(qaf card)" = "This title couldn'"'"'t be played" ]'
key BACK; sleep 2
prefs="$(adb shell "run-as $PKG cat shared_prefs/flix.xml")"
pos=$(echo "$prefs" | grep -o 'name="resume_position_series:[0-9]*" value="[0-9]*"' | head -1 | grep -o '[0-9]*"$' | tr -d '"')
note "INFO  episode $ep, saved position ${pos:-none} ms"
check "B2 the interrupted episode is not marked watched: it stays the resume episode with its position" 'echo "$prefs" | grep -q "name=\"resume_episode_series:[0-9]*\">$ep<" && [ "${pos:-0}" -ge 100000 ]'
# B3. Seeking on a movie
stream "$M/subs.mkv"; adb logcat -b main -c
open_movie; sleep 3; e0=$(elapsed)
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.25; p1=$(qaf pending); st="$(qaf status)"
note "INFO  tap: elapsed ${e0}s, pending ${p1}s, status '$st'"
check "B3 a tap shows the pending target (+10 s) before it is applied" '[ "$p1" -ge $((e0+9)) ] && [ "$p1" -le $((e0+12)) ] && echo "$st" | grep -q "→"'
sleep 1.5; e1=$(elapsed)
check "B3 then it is applied once ($e0 → $e1 s) and the marker goes" '[ "$e1" -ge $((e0+9)) ] && [ "$(qaf pending)" = "-1" ]'
key BACK; sleep 1; wait_qa controls false 6
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.2; adb shell input keyevent KEYCODE_BACK; sleep 1.5; e2=$(elapsed)
check "B3 Back cancels a pending seek (no jump: ${e1} → ${e2} s)" '[ "$(qaf pending)" = "-1" ] && [ "$e2" -lt $((e1+8)) ]'
wait_qa controls false 6
if hold 106 4; then sleep 0.3; ph=$(qaf pending); sleep 1.2; eh=$(elapsed)
  note "INFO  after holding Right 4 s: pending ${ph}, elapsed ${eh}"
  check "B3 holding Right accelerates to the end guard in 4 s (duration − 5 s = 295 s), never past it" '[ "$eh" -ge 280 ] && [ "$eh" -le 296 ]'
  t=0; while [ $t -lt 25 ] && in_activity PlayerActivity; do sleep 1; t=$((t+1)); done
  check "B3 the movie then ends normally and playback closes (no error card)" '! in_activity PlayerActivity && ! adb logcat -d -s FlixTownQA:I | grep -q "card=\[This title"'
else note "INFO  hold: no input device for sendevent on this emulator (acceleration covered by SeekStepTest)"; fi
# paused seek and Go to time on the movie
stream "$M/subs.mkv"; open_movie; sleep 2
key DPAD_CENTER; sleep 1; wait_qa controls false 6; e3=$(elapsed)
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 2; e4=$(elapsed)
check "B4 seeking while paused stays paused (${e3} → ${e4} s)" '[ "$(qaf playing)" = "false" ] && [ "$e4" -ge $((e3+9)) ]'
key DPAD_CENTER; sleep 1
go_to 2 30; e5=$(elapsed)
check "B4 Go to time 2:30 jumps there (${e5} s)" '[ "$e5" -ge 148 ] && [ "$e5" -le 158 ]'
shot B4-after-go-to 0.1
key DPAD_UP; sleep 0.4; key DPAD_UP; sleep 0.4; key DPAD_CENTER; sleep 1.2; focus_to DPAD_DOWN "Go to time" 3 > /dev/null; key DPAD_CENTER; sleep 1.2
shot B4-go-to-dialog 0.1; key BACK; sleep 1.5; e6=$(elapsed)
check "B4 cancelling Go to time leaves playback where it was (${e5} → ${e6} s)" '[ "$e6" -ge "$e5" ] && [ "$e6" -le $((e5+8)) ] && [ "$(qaf dialog)" = "false" ]'
leave_player
# unseekable stream
stream "$M/live/live.ts"; open_movie; sleep 4
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.5; st="$(qaf status)"
check "B5 an unseekable stream says so instead of seeking ('$st')" 'echo "$st" | grep -q "can.t be skipped"'
leave_player
# ====================== C. Subtitles ======================
measure(){ # file colour label
  stream "$M/$1"; open_movie; sleep 5; wait_qa controls false 6; sleep 1
  local b; b="$(sub_box "C-$3-hidden" "$2")"; note "INFO  $3 controls hidden: $b"; echo "$b" > "$OUT/box-$3"
  key DPAD_UP; sleep 0.6; local s; s="$(sub_box "C-$3-shown" "$2")"; note "INFO  $3 controls shown: $s"; echo "$s" > "$OUT/box-$3-shown"
  leave_player; }
set_prefs subtitles_on:b:true subtitle_language:s:en subtitle_size:f:1.0
adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null; wait_activity HomeActivity 30; sleep 5
note "INFO  $(qa subtitle_builtin_parent)"
measure subs.mkv green plain169
measure letterbox.mkv green plainLB
measure subs43.mkv green plain43
set_prefs subtitles_on:b:true subtitle_language:s:fr
adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null; wait_activity HomeActivity 30; sleep 5
measure letterbox.mkv yellow assLB
measure subs.mkv yellow ass169
set_prefs subtitles_on:b:true subtitle_language:s:en subtitle_size:f:1.4
adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" > /dev/null; wait_activity HomeActivity 30; sleep 5
measure subs.mkv green xl169
geom(){ cat "$OUT/box-$1"; }
low(){ set -- $(geom "$1"); [ "$7" -gt 20 ] && python3 -c "import sys;y1,h=int(sys.argv[1]),int(sys.argv[2]);sys.exit(0 if .93*h<=y1<=.972*h else 1)" "$4" "$6"; }
inside(){ set -- $(geom "$1"); [ "$7" -gt 20 ] && [ "$1" -ge $(($5*5/100)) ] && [ "$3" -le $(($5*95/100)) ] && [ "$2" -ge $(($6*5/100)) ] && [ "$4" -le $(($6*972/1000)) ]; }
same(){ set -- $(geom "$1") ; local a=$2; set -- $(geom "$1-shown"); [ "$7" -gt 20 ] && [ $((a-$2)) -le 8 ] && [ $(($2-a)) -le 8 ]; }
for c in plain169 plainLB plain43; do set -- $(geom $c); note "INFO  $c text bottom at $(frac $4 $6) of the screen height"; done
check "C1 16:9: ordinary subtitles bottom-centre near the bottom (93–97 % of height)" 'low plain169 && inside plain169'
check "C1 letterboxed movie: ordinary subtitles near the screen bottom, not inside the picture" 'low plainLB && inside plainLB'
check "C1 4:3: ordinary subtitles near the bottom" 'low plain43 && inside plain43'
set -- $(geom assLB); note "INFO  assLB bottom at $(frac $4 $6) (the picture ends at about 0.872)"
check "C2 letterboxed: a positioned ASS caption stays inside the picture (above the black bar)" 'set -- $(geom assLB); [ "$7" -gt 20 ] && [ "$4" -le $(($6*875/1000)) ]'
check "C2 16:9: positioned ASS caption visible near the bottom" 'inside ass169'
check "C3 subtitles stay put when the controls show (same top line ±8 px)" 'same plain169 && same plainLB'
check "C4 Extra large stays fully on screen" 'inside xl169'
crashB="$(adb logcat -d -b crash | grep -A1 'FATAL EXCEPTION' | grep -c "Process: $PKG")"
check "no Flix Town crash" '[ "$crashB" = 0 ]'
rm -f "$OUT"/s.raw "$OUT"/c.raw "$OUT"/flix.xml "$OUT"/ui.xml
note ""; note "player & seasonal: $PASS passed, $FAIL failed"
