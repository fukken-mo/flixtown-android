#!/usr/bin/env bash
# Offline QA only. All reports go to DemoData, never OnePanel/Seerr.
set -eu
PKG=com.myflixtown.tv.native.qa
OUT=${QA_OUT:-qa/playback-reports};mkdir -p "$OUT/screens"
PASS=0
note(){ echo "$*" | tee -a "$OUT/report.txt"; }
ok(){ PASS=$((PASS+1));note "PASS $*"; }
key(){ for k in "$@";do adb shell input keyevent "KEYCODE_$k";sleep .5;done; }
dump(){ adb shell uiautomator dump /sdcard/reports-ui.xml >/dev/null 2>&1 || true;adb shell cat /sdcard/reports-ui.xml > "$OUT/ui.xml"; }
has(){ dump;grep -q "$1" "$OUT/ui.xml"; }
wait_text(){ for n in $(seq 1 15);do has "$1" && return 0;sleep 1;done;note "FAIL missing $1";return 1; }
activity(){ adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1; }
wait_activity(){ for n in $(seq 1 45);do activity | grep -q "$1" && return 0;sleep 1;done;note "FAIL activity $1";return 1; }
shot(){ adb shell screencap -p /sdcard/report-shot.png;adb pull /sdcard/report-shot.png "$OUT/screens/$1.png" >/dev/null; }
focus_text(){ dump;python3 - "$OUT/ui.xml" <<'PY'
import sys,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('focused')=='true':
  print(' '.join(c.get('text','') or c.get('content-desc','') for c in n.iter('node')));break
PY
}
focus_to(){ local keycode=$1 text=$2;for n in $(seq 1 12);do focus_text | grep -q "$text" && return 0;key "$keycode";done;note "FAIL focus $text";return 1; }
qa(){ adb logcat -d -s FlixTownQA:I | grep ' player ' | tail -1; }
report_focus(){ key DPAD_UP;for n in $(seq 1 8);do qa | grep -q 'focus=report' && return 0;key DPAD_RIGHT;done;note 'FAIL report control focus';return 1; }
log_reports(){ adb logcat -d -s FlixTownQA:I | grep -c 'report type=' || true; }
live(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; }
back_details(){ for n in $(seq 1 6);do activity | grep -q DetailsActivity && return 0;key BACK;sleep 1;done;return 1; }
: > "$OUT/report.txt"
adb install -r app/build/outputs/apk/qa/app-qa.apk >/dev/null
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n "$PKG/com.flixtown.tv.LoginActivity" --ez demo_reset true >/dev/null
wait_activity HomeActivity;sleep 5;ok 'login and Home startup'
# Stable existing remote route: Movies, first title, Watch.
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;sleep 2;key DPAD_RIGHT DPAD_CENTER
wait_activity DetailsActivity;sleep 3;key DPAD_CENTER
wait_activity PlayerActivity;sleep 3
report_focus;ok 'Report reached by D-pad';shot 01-player-report
key DPAD_CENTER;wait_text 'Report a problem';shot 02-issues
wait_text 'No sound';ok 'issue list appears'
qa | grep -q 'playing=false reporting=true';ok 'playback pauses while reporting'
key BACK;sleep 1
qa | grep -q 'focus=report' && ok 'Back restores Report focus'
# A viewer who paused before reporting must remain paused afterwards.
key MEDIA_PAUSE;report_focus;key DPAD_CENTER;wait_text 'Video stutters';key BACK;sleep 1
qa | grep -q 'playing=false reporting=false';ok 'previously paused playback stays paused after cancel'
key MEDIA_PLAY
# Report without typing. Confirm, submit, acknowledge, return.
report_focus;key DPAD_CENTER;wait_text 'Video stutters';key DPAD_CENTER;wait_text 'Send report?';shot 03-confirm
key DPAD_CENTER;wait_text 'Thank you';wait_text 'Report sent';shot 04-sent;key DPAD_CENTER;sleep 1
[ "$(log_reports)" -eq 1 ];ok 'one movie report submitted'
qa | grep -q 'focus=report';ok 'success returns focus to Report'
# Same movie/issue is acknowledged without creating a duplicate (fake server matches backend contract).
report_focus;key DPAD_CENTER;wait_text 'Video stutters';key DPAD_CENTER;wait_text 'Send report?';key DPAD_CENTER;wait_text 'already reported';key DPAD_CENTER
adb logcat -d -s FlixTownQA:I | grep -q 'report type=movie .*duplicate=true';ok 'duplicate acknowledged'
# An unavailable service affects only reporting; cancel and return to playback.
live '{"reports_down":true}'
report_focus;key DPAD_CENTER;wait_text 'Video stutters';key DPAD_CENTER;wait_text 'Send report?';key DPAD_CENTER;wait_text 'Report not sent';shot 05-unavailable
key DPAD_RIGHT DPAD_CENTER;sleep 1;activity | grep -q PlayerActivity;ok 'report outage returns to player'
live '{}'
back_details;key BACK;wait_activity HomeActivity;sleep 1
key BACK;sleep 1;key DPAD_LEFT;focus_to DPAD_DOWN 'TV Shows';key DPAD_CENTER;sleep 2;key DPAD_RIGHT DPAD_CENTER
wait_activity DetailsActivity;sleep 3;key DPAD_CENTER;sleep 1;key DPAD_CENTER
wait_activity PlayerActivity;sleep 3
report_focus;key DPAD_CENTER;wait_text 'Video stutters';key DPAD_DOWN DPAD_CENTER;wait_text 'Send report?';key DPAD_CENTER;wait_text 'Report sent';shot 06-episode-sent;key DPAD_CENTER
adb logcat -d -s FlixTownQA:I | grep -Eq 'report type=series media=[1-9][0-9]* episode=[1-9][0-9]* issue=no_audio';ok 'episode report captures current series and episode'
# Back from the picker sends nothing; service remains independent from audio/subtitle selection.
before=$(log_reports);report_focus;key DPAD_CENTER;wait_text 'Video stutters';key BACK;sleep 1
[ "$(log_reports)" -eq "$before" ];ok 'cancel sends no report'
key DPAD_LEFT DPAD_CENTER;wait_text 'Subtitles';shot 07-subtitles-unchanged;key BACK
back_details;activity | grep -q DetailsActivity;ok 'Back returns to Details'
key BACK;wait_activity HomeActivity
# Force a local failing test stream. No credential or live request is involved.
live '{"stream":"http://10.0.2.2:9/movie/FAKE_USER/FAKE_PASSWORD/1.mp4"}'
key BACK;sleep 1;key DPAD_LEFT;focus_to DPAD_DOWN 'Movies';key DPAD_CENTER;sleep 2;key DPAD_RIGHT DPAD_CENTER
wait_activity DetailsActivity;sleep 2;key DPAD_CENTER;wait_activity PlayerActivity
wait_text "couldn.t be played";shot 08-playback-error
key DPAD_RIGHT DPAD_CENTER;wait_text 'Report a problem';focus_to DPAD_DOWN "Video won.t play";key DPAD_CENTER;wait_text 'Send report?';key DPAD_CENTER;wait_text 'Report sent';key DPAD_CENTER
wait_text "couldn.t be played";ok 'error screen supports reporting and restores error actions'
adb logcat -d -s FlixTownQA:I | grep -q 'issue=wont_play';ok 'unplayable report submitted'
if adb logcat -d | grep -E 'FAKE_USER|FAKE_PASSWORD';then note 'FAIL credential leak';exit 1;fi
ok 'no stream credentials in log'
live '{}'
adb logcat -d -s FlixTownQA:I > "$OUT/qa-log.txt"
note "Playback reports: $PASS passed, 0 failed"
