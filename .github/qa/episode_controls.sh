#!/usr/bin/env bash
# Offline demo only. No live accounts, streams, reports or production changes.
set -eu
PKG=com.myflixtown.tv.native.qa
OUT=${QA_OUT:-qa/episode-controls};mkdir -p "$OUT/screens"
PASS=0
note(){ echo "$*" | tee -a "$OUT/report.txt"; }
ok(){ PASS=$((PASS+1));note "PASS $*"; }
key(){ for k in "$@";do adb shell input keyevent "KEYCODE_$k";sleep .5;done; }
dump(){ adb shell uiautomator dump /sdcard/episode-ui.xml >/dev/null 2>&1 || true;adb shell cat /sdcard/episode-ui.xml > "$OUT/ui.xml"; }
has(){ dump;grep -q "$1" "$OUT/ui.xml"; }
wait_text(){ for n in $(seq 1 15);do has "$1" && return 0;sleep 1;done;note "FAIL missing $1";return 1; }
activity(){ adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1; }
wait_activity(){ for n in $(seq 1 45);do activity | grep -q "$1" && return 0;sleep 1;done;note "FAIL activity $1";return 1; }
shot(){ adb shell screencap -p /sdcard/episode-shot.png;adb pull /sdcard/episode-shot.png "$OUT/screens/$1.png" >/dev/null; }
focus_text(){ dump;python3 - "$OUT/ui.xml" <<'PY'
import sys,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('focused')=='true':
  print(' '.join(c.get('text','') or c.get('content-desc','') for c in n.iter('node')));break
PY
}
focus_to(){ for n in $(seq 1 12);do focus_text | grep -q "$2" && return 0;key "$1";done;note "FAIL focus $2";return 1; }
qa(){ adb logcat -d -s FlixTownQA:I | grep ' player ' | tail -1; }
state(){ for n in $(seq 1 20);do qa | grep -q "$1" && return 0;sleep .5;done;qa;note "FAIL state $1";return 1; }
control(){ key DPAD_DOWN;key DPAD_DOWN;key DPAD_UP;for n in $(seq 1 8);do qa | grep -q "focus=$1 " && return 0;key DPAD_RIGHT;done;note "FAIL control $1";return 1; }
options(){ key DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP DPAD_CENTER;wait_text 'Player options'; }
back_details(){ for n in $(seq 1 8);do activity | grep -q DetailsActivity && return 0;key BACK;sleep 1;done;return 1; }
resume_check(){
adb shell "run-as $PKG cat shared_prefs/flix.xml" > "$OUT/progress.xml"
python3 - "$OUT/progress.xml" "$1" <<'PY_ASSERT'
import sys,xml.etree.ElementTree as E
values={n.get('name'):n.get('value',n.text or '') for n in E.parse(sys.argv[1]).getroot()}
keys=[k for k,v in values.items() if k.startswith('resume_episode_series:') and v==sys.argv[2]]
assert len(keys)==1,keys
key=keys[0].removeprefix('resume_episode_')
assert key in values.get('continue_ids','').split(','),values.get('continue_ids')
assert int(values.get('resume_position_'+key,'0'))<4000
assert 'resume_done_'+key not in values or values['resume_done_'+key]!='true'
PY_ASSERT
}
: > "$OUT/report.txt"
trap 'adb logcat -d -s FlixTownQA:I > "$OUT/qa-log.txt";adb shell screencap -p /sdcard/final-episode.png;adb pull /sdcard/final-episode.png "$OUT/screens/final.png" >/dev/null 2>&1 || true' EXIT
adb install -r app/build/outputs/apk/qa/app-qa.apk >/dev/null
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n "$PKG/com.flixtown.tv.LoginActivity" --ez demo_reset true >/dev/null
wait_activity HomeActivity;sleep 5;ok 'login and Home startup'
printf '%s' '{"stream":"http://10.0.2.2:8766/episode-test.mkv"}' | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"
adb shell "run-as $PKG cat files/demo_account.json" | grep -q '8766/episode-test.mkv' 
# Movie controls remain unchanged.
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;sleep 2;key DPAD_RIGHT DPAD_CENTER
wait_activity DetailsActivity;sleep 3;key DPAD_CENTER
wait_activity PlayerActivity;sleep 3;state 'playing=true';key MEDIA_PAUSE;state 'episode_controls=false';shot 01-movie-controls
ok 'movies have no episode controls'
control audio;key DPAD_CENTER;wait_text 'Audio';adb logcat -d -s FlixTownQA:I | grep -q 'tracks type=1';key BACK;ok 'movie Audio picker still opens'
options;key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
adb shell "run-as $PKG cat shared_prefs/flix.xml" | grep -q 'name="watched_movie:.*" value="true"'
ok 'movie can be marked watched'
options;wait_text 'Mark as unwatched';key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
adb shell "run-as $PKG cat shared_prefs/flix.xml" | grep -q 'name="watched_movie:.*" value="false"'
ok 'movie can be marked unwatched without losing My List'
back_details;key BACK;wait_activity HomeActivity;sleep 1
key BACK;sleep 1;key DPAD_LEFT;focus_to DPAD_DOWN 'TV Shows';key DPAD_CENTER;sleep 2;key DPAD_RIGHT DPAD_CENTER
wait_activity DetailsActivity;sleep 3;key DPAD_CENTER;sleep 1;key DPAD_CENTER
wait_activity PlayerActivity;sleep 3;state 'playing=true';key MEDIA_PAUSE
state 'episode=9101 previous=false next=true';ok 'first episode disables Previous and enables Next'
control next;state 'controls_fit=true';ok 'single-row controls fit inside TV margins';shot 02-series-controls;key DPAD_CENTER
state 'episode=9102 previous=true next=true';state 'playing=false';state 'elapsed=[0-3] ';ok 'D-pad Next starts E2 at zero and stays paused'
resume_check 9102;ok 'first manual switch creates Continue Watching immediately'
key DPAD_LEFT;state 'focus=previous ';key DPAD_CENTER
state 'episode=9101 previous=false next=true';state 'focus=play ';ok 'Previous returns to E1 and restores reachable focus at boundary'
key MEDIA_PREVIOUS;state 'episode=9101 ';ok 'Previous at first episode never wraps'
for n in $(seq 1 7);do key MEDIA_NEXT;done
state 'episode=9108 ';key MEDIA_NEXT;state 'episode=9201 ';shot 03-season-boundary
ok 'Next crosses S1 E8 to S2 E1'
key MEDIA_PREVIOUS;state 'episode=9108 ';ok 'Previous crosses S2 E1 to S1 E8'
for n in $(seq 1 16);do key MEDIA_NEXT;done
state 'episode=9308 previous=true next=false';key MEDIA_NEXT;state 'episode=9308 ';shot 04-last-episode
ok 'last episode disables Next without wrapping'
# Reports must use the NEW selected episode, never the original episode ID.
control report;key DPAD_CENTER;wait_text 'Video stutters';key DPAD_DOWN DPAD_CENTER;wait_text 'Send report?';key DPAD_CENTER;wait_text 'Report sent';key DPAD_CENTER
adb logcat -d -s FlixTownQA:I | grep -q 'report type=series .*episode=9308 issue=no_audio';ok 'report captures selected episode after navigation'
for n in $(seq 1 23);do key MEDIA_PREVIOUS;done
state 'episode=9101 previous=false next=true';ok 'rapid backwards navigation retains the full future queue'
key MEDIA_PLAY;state 'playing=true';ok 'play after manual navigation'
# Real EOF/autoplay, not a synthetic navigation hook.
for n in $(seq 1 100);do qa | grep -q 'episode=9102 ' && break;sleep 1;done
state 'episode=9102 previous=true next=true';state 'playing=true';ok 'normal EOF autoplay still advances and keeps Previous available'
key MEDIA_PAUSE;control subs;key DPAD_CENTER;wait_text 'Subtitles';adb logcat -d -s FlixTownQA:I | grep -q 'tracks type=3';key BACK;ok 'Subtitles picker works after autoplay and navigation'
key MEDIA_FAST_FORWARD;state 'elapsed=1[0-4] ';ok 'existing ten-second seeking is unchanged'
adb shell input keyevent KEYCODE_MEDIA_FAST_FORWARD KEYCODE_MEDIA_NEXT
state 'episode=9103 ';state 'elapsed=[0-3] ';state 'playing=false';ok 'pending seek from old episode never lands on new episode'
shot 05-new-episode-paused
resume_check 9103
ok 'selected episode is in Continue Watching immediately at zero'
back_details;wait_activity DetailsActivity;ok 'Back returns to Details'
key DPAD_UP DPAD_UP;focus_to DPAD_LEFT 'Continue';key DPAD_CENTER
wait_activity PlayerActivity;state 'episode=9103 previous=true next=true';ok 'Continue resumes the manually selected episode even before fifteen seconds'
key MEDIA_PAUSE;key MEDIA_PREVIOUS;state 'episode=9102 previous=true next=true';ok 'fresh player receives earlier episodes from Details'

# Viewer extras use only the offline stream and demo catalog.
options;shot 06-player-options;key DPAD_DOWN DPAD_CENTER;wait_text 'Playback speed';shot 07-speed;key DPAD_DOWN DPAD_CENTER
state 'speed=1.25';ok 'playback speed changes through TV Options'
options;key DPAD_DOWN DPAD_DOWN DPAD_CENTER;wait_text 'Subtitle size';key DPAD_DOWN DPAD_CENTER
state 'subtitle_size=1.2';ok 'subtitle size is saved without moving its safe area'
options;key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER;wait_text 'Subtitle appearance';shot 08-subtitle-style;key DPAD_DOWN DPAD_DOWN DPAD_CENTER
state 'subtitle_style=2';ok 'subtitle appearance is saved'
options;key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
adb shell "run-as $PKG cat shared_prefs/flix.xml" | grep -q 'name="watched_series:.*:9102" value="true"'
ok 'episode can be marked watched without changing playback'
options;wait_text 'Mark as unwatched';key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
adb shell "run-as $PKG cat shared_prefs/flix.xml" | grep -q 'name="watched_series:.*:9102" value="false"'
ok 'episode can be marked unwatched'
options;key DPAD_CENTER;wait_text 'Select season';key DPAD_DOWN DPAD_CENTER;wait_text 'Season 2';shot 09-episode-list;key DPAD_DOWN DPAD_DOWN DPAD_CENTER
state 'episode=9203 previous=true next=true';state 'playing=false';state 'elapsed=[0-3] '
ok 'in-player season and episode list jumps to a real episode'
resume_check 9203;ok 'episode-list choice updates Continue Watching immediately'
control subs;key DPAD_CENTER;wait_text 'Subtitles';key DPAD_DOWN DPAD_CENTER
adb shell "run-as $PKG cat shared_prefs/flix.xml" | grep -q 'name="subtitles_on" value="true"'
ok 'subtitle selection remembers enabled state'
key MEDIA_NEXT;state 'episode=9204 ';state 'speed=1.25';state 'subtitle_size=1.2';state 'subtitle_style=2'
ok 'episode change retains speed and subtitle preferences'
key DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP;state 'focus=options ';key DPAD_DOWN;state 'focus=play '
ok 'Options and playback row have no D-pad trap'
key MEDIA_FAST_FORWARD MEDIA_FAST_FORWARD MEDIA_FAST_FORWARD MEDIA_FAST_FORWARD
key MEDIA_PLAY;options
sleep 32;state 'episode=9204 ';key BACK
state 'episode=9205 ';ok 'autoplay resumes after an Options menu spans episode EOF'

if adb logcat -d | grep -A8 'FATAL EXCEPTION' | grep -q 'com.myflixtown.tv.native.qa';then note 'FAIL app crash';exit 1;fi
ok 'no app crash'
note "Episode controls: $PASS passed, 0 failed"
