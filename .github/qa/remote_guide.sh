#!/usr/bin/env bash
# Offline QA only. No live credentials or backend writes.
set -eu
PKG=com.myflixtown.tv.native.qa
OUT=${QA_OUT:-qa/remote-guide};mkdir -p "$OUT/screens"
PASS=0
ok(){ PASS=$((PASS+1));echo "PASS $*" | tee -a "$OUT/report.txt"; }
key(){ for k in "$@";do adb shell input keyevent "KEYCODE_$k";sleep .4;done; }
activity(){ adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1; }
wait_activity(){ for n in $(seq 1 40);do activity | grep -q "$1" && return 0;sleep 1;done;activity;return 1; }
dump(){ adb shell uiautomator dump /sdcard/guide-ui.xml >/dev/null 2>&1;adb shell cat /sdcard/guide-ui.xml > "$OUT/ui.xml"; }
text(){ dump;grep -q "$1" "$OUT/ui.xml"; }
focused(){ dump;python3 - "$OUT/ui.xml" <<'PY'
import sys,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('focused')=='true':
  print(n.get('content-desc') or n.get('text') or ' '.join(x.get('text','') for x in n.iter('node')));break
PY
}
focus_to(){ for n in $(seq 1 18);do focused | grep -q "$2" && return 0;key "$1";done;return 1; }
shot(){ adb shell screencap -p /sdcard/guide.png;adb pull /sdcard/guide.png "$OUT/screens/$1.png" >/dev/null; }
open_guide(){ key DPAD_LEFT;focus_to DPAD_UP 'Search';focus_to DPAD_DOWN 'Settings';key DPAD_CENTER;sleep 2;key DPAD_RIGHT;focus_to DPAD_DOWN 'Remote Guide';key DPAD_CENTER;wait_activity RemoteGuideActivity; }
trap 'adb shell settings put system font_scale 1.0;adb logcat -d -b crash > "$OUT/crash.txt";adb shell screencap -p /sdcard/guide-final.png;adb pull /sdcard/guide-final.png "$OUT/screens/final.png" >/dev/null 2>&1 || true' EXIT
: > "$OUT/report.txt"
adb install -r app/build/outputs/apk/qa/app-qa.apk >/dev/null
adb logcat -c;adb logcat -b crash -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n "$PKG/com.flixtown.tv.LoginActivity" --ez demo_reset true >/dev/null
wait_activity HomeActivity;sleep 4;ok 'existing startup reaches Home'
open_guide;ok 'Settings opens Remote Guide'
text 'TV remote diagram';text 'Continue Watching: Resume';text 'watched / unwatched';ok 'Browsing shows a sharp remote diagram and existing hold-OK actions'
focused | grep -q Browsing;ok 'initial focus starts on Browsing'
shot 01-browsing
key DPAD_RIGHT DPAD_CENTER;text 'Seek 10 seconds';text 'subtitle size and appearance';text 'Hide the controls first';ok 'Watching explains seeking, Options and Back accurately'
shot 02-watching
key DPAD_DOWN;focused | grep -q 'Up\.';ok 'Down reaches the first instruction'
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN;focused | grep -q 'Back\.';ok 'all Watching instructions are D-pad reachable'
key BACK;wait_activity HomeActivity;focused | grep -q 'Remote Guide';ok 'Back returns to the same Settings row'
key DPAD_CENTER;wait_activity RemoteGuideActivity;key DPAD_UP DPAD_CENTER;wait_activity HomeActivity;ok 'Close returns safely to Settings'
# Larger system text remains readable through the scrollable, focusable instruction list.
adb shell settings put system font_scale 1.3
adb shell am force-stop "$PKG"
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n "$PKG/com.flixtown.tv.LoginActivity" >/dev/null
wait_activity HomeActivity;sleep 3;open_guide
key DPAD_RIGHT DPAD_CENTER DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN
focused | grep -q 'Back\.';text 'Hide the controls first';ok 'larger text can scroll to the last instruction'
shot 03-larger-text
key BACK;wait_activity HomeActivity;ok 'larger text does not trap Back navigation'
if adb logcat -d -b crash | grep -q 'FATAL EXCEPTION';then echo 'FAIL app crash';exit 1;fi
ok 'no app crash'
echo "Remote Guide: $PASS passed, 0 failed" | tee -a "$OUT/report.txt"
