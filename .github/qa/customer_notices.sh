#!/usr/bin/env bash
# All notification fixtures are offline. Never contacts OnePanel or Seerr.
set -eu
PKG=com.myflixtown.tv.native.qa
OUT=${QA_OUT:-qa/customer-notices};mkdir -p "$OUT/screens"
PASS=0
note(){ echo "$*" | tee -a "$OUT/report.txt"; }
ok(){ PASS=$((PASS+1));note "PASS $*"; }
key(){ for k in "$@";do adb shell input keyevent "KEYCODE_$k";sleep .5;done; }
dump(){ adb shell uiautomator dump /sdcard/notices-ui.xml >/dev/null 2>&1 || true;adb shell cat /sdcard/notices-ui.xml > "$OUT/ui.xml"; }
has(){ dump;grep -q "$1" "$OUT/ui.xml"; }
wait_text(){ for n in $(seq 1 20);do has "$1" && return 0;sleep 1;done;note "FAIL missing $1";return 1; }
activity(){ adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1; }
wait_home(){ for n in $(seq 1 45);do activity | grep -q HomeActivity && return 0;sleep 1;done;note 'FAIL Home';return 1; }
shot(){ adb shell screencap -p /sdcard/notice-shot.png;adb pull /sdcard/notice-shot.png "$OUT/screens/$1.png" >/dev/null; }
live(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; }
reopen(){ adb shell am force-stop "$PKG";adb shell am start -W -n "$PKG/com.flixtown.tv.LoginActivity" >/dev/null;wait_home;sleep 6; }
: > "$OUT/report.txt"
adb install -r app/build/outputs/apk/qa/app-qa.apk >/dev/null
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n "$PKG/com.flixtown.tv.LoginActivity" --ez demo_reset true >/dev/null
wait_home;sleep 6;ok 'existing login and Home open normally'
live '{"customer_notices":true,"customer_notices":true,"notice_requests":[],"resolved_reports":[{"id":"42","type":"movie","media_id":1000,"title":"Resolved fixture"}]}'
reopen;wait_text 'marked resolved';wait_text 'Watch';shot 01-resolved;ok 'own resolved report offers Watch'
key BACK;sleep 4;! has 'marked resolved';ok 'Back defers without immediately reopening dialog'
reopen;wait_text 'marked resolved';ok 'deferred report remains unread on next launch'
key DPAD_RIGHT DPAD_CENTER;sleep 3;! has 'marked resolved';ok 'Dismiss closes resolved notice'
reopen;! has 'marked resolved';ok 'dismissed resolution does not repeat after process restart'
live '{"customer_notices":true,"notice_requests":[{"type":"movie","id":8100,"title":"Approved fixture","year":"2020","state":"approved","requested_at":123,"seasons":[]}]}'
reopen;wait_text 'has been approved';wait_text 'View requests';! has 'text="Watch"';shot 02-approved;ok 'approval is distinct from ready and offers no Watch'
key DPAD_RIGHT DPAD_CENTER
live '{"customer_notices":true,"notice_requests":[{"type":"movie","id":8100,"title":"Ready fixture","year":"2020","state":"available","requested_at":123,"seasons":[]}]}'
reopen;wait_text 'ready to watch';shot 03-ready;ok 'same request transitions from approved to ready'
key DPAD_RIGHT DPAD_CENTER;reopen;! has 'ready to watch';ok 'ready notice does not repeat after dismissal'
live '{"customer_notices":true,"requests":"down","reports_down":true}'
reopen;! has 'has been approved';! has 'marked resolved';ok 'service outage leaves Home working without error dialog'
adb logcat -b crash -d > "$OUT/crash.txt"
! grep -q "Process: $PKG," "$OUT/crash.txt";ok 'no Flix Town crash'
note "Customer notices: $PASS passed, 0 failed"
