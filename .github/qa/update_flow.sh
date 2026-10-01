#!/usr/bin/env bash
# App update flow on the Android TV emulator, against the real panel module running on this
# machine. One APK link (apk/flixtown.apk) is replaced in place, exactly as on the real panel.
# Results: qa/update-flow/screens/*.png, qa/update-flow/report.txt
set -u
PKG=com.myflixtown.tv.native.qa
OUT=qa/update-flow; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }

# ---------- Panel on this machine (the emulator reaches it at $HOST_IP) ----------
ROOT="$(mktemp -d)"; mkdir -p "$ROOT/api" "$ROOT/apk"
cp -R panel/app-update.php panel/app-update-admin.php panel/flixtown-update "$ROOT/api/"
rm -f "$ROOT/api/flixtown-update/data/state.json"
settings(){ cat > "$ROOT/api/flixtown-update/settings.php" <<EOF
<?php return array('admin_password' => 'qa-pass', 'main_package' => 'com.myflixtown.tv.native', 'recheck_minutes' => $1, 'max_apk_mb' => 200, 'cache_bust' => true);
EOF
  sleep 3; }
settings 0
start_panel(){ PHP_CLI_SERVER_WORKERS=4 php -S 0.0.0.0:8787 -t "$ROOT" >> "$OUT/panel.log" 2>&1 & PANEL=$!; sleep 1; }
php -S 0.0.0.0:8788 -t "$ROOT" >> "$OUT/files.log" 2>&1 & FILES=$!
start_panel
trap 'kill $PANEL $FILES 2>/dev/null || true' EXIT
BASE="http://$HOST_IP:8787"; APK_URL="http://$HOST_IP:8788/apk/flixtown.apk"
JAR="$ROOT/cookies"
csrf(){ curl -s -c "$JAR" -b "$JAR" "$BASE/api/app-update-admin.php" | sed -n 's/.*name="csrf" value="\([0-9a-f]*\)".*/\1/p' | head -1; }
admin(){ curl -s -c "$JAR" -b "$JAR" -o /dev/null "$BASE/api/app-update-admin.php" --data-urlencode "csrf=$(csrf)" "$@"; }
place(){ cp "$1" "$ROOT/apk/.next"; mv "$ROOT/apk/.next" "$ROOT/apk/flixtown.apk"; }
api(){ curl -s "$BASE/api/app-update.php?package=$PKG&t=$RANDOM"; }
code_on_panel(){ api | php -r '$j=json_decode(stream_get_contents(STDIN),true); echo isset($j["update_version_code"])?$j["update_version_code"]:"none";'; }

# ---------- Device helpers ----------
shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; echo "shot $1"; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
installed_code(){ adb shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
screen_has(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -q "$1"; }
# Taps the first on-screen element whose text matches (Android's installer buttons).
tap_text(){
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local xy; xy="$(adb shell cat /sdcard/ui.xml | python3 -c '
import re,sys
xml=sys.stdin.read(); want=re.compile(sys.argv[1],re.I)
for node in re.findall(r"<node [^>]*>",xml):
    t=re.search(r" text=\"([^\"]*)\"",node); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",node)
    if t and b and want.fullmatch(t.group(1).strip()):
        x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break' "$1")"
  [ -n "$xy" ] && adb shell input tap $xy && return 0
  return 1
}
# Settings rows: 0 Subscription … 3 Autoplay … 7 Check for app updates, 8 App version, 9 Sign out
open_settings(){ key DPAD_LEFT; key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 1.2; }

adb logcat -c
adb install -r dist/qa-10006.apk > /dev/null && ok "installed QA build 10006" || bad "install 10006 failed"

# ---------- 1. Panel: publish version 10006 at the link ----------
place dist/qa-10006.apk
admin --data-urlencode action=login --data-urlencode password=qa-pass
admin --data-urlencode action=save --data-urlencode slot=test --data-urlencode "url=$APK_URL" --data-urlencode "notes=Update-flow test release."
[ "$(code_on_panel)" = "10006" ] && ok "panel detected 10006 from the APK" || bad "panel did not detect 10006 ($(api))"

# ---------- 2. Sign in, set a preference, check: up to date ----------
launch --ez demo_reset true
sleep 26;                                   shot 01-home-signed-in 1
open_settings;                              shot 02-settings 0.5
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; shot 03-autoplay-off 0.5
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 04-updates-row 0.5
key DPAD_CENTER;                            shot 05-checking 0.3
sleep 2;                                    shot 06-up-to-date 0.3
screen_has "is up to date" && ok "same version on the panel: 'up to date'" || bad "no 'up to date' answer"
key DPAD_CENTER;                            shot 07-row-up-to-date 0.5

# ---------- 3. Panel unreachable: failure, never 'up to date' ----------
kill $PANEL; sleep 1
key DPAD_CENTER;                            sleep 3; shot 08-network-error 0.3
screen_has "check for updates" && ! screen_has "is up to date" && ok "panel unreachable: 'Couldn't check for updates'" || bad "network error not reported as a failure"
key DPAD_CENTER;                            shot 09-row-check-failed 0.5
start_panel

# ---------- 4. Same link, new file: 10007 (no button pressed on the panel) ----------
place dist/qa-10007.apk
[ "$(code_on_panel)" = "10007" ] && ok "same link replaced: panel detected 10007 by itself" || bad "panel did not notice the replaced file"

# ---------- 5. Background check on launch offers the update ----------
adb shell am force-stop $PKG; launch
sleep 16;                                   shot 10-startup-offer 0.5
screen_has "3.4.1 is available" && ok "startup check offered 3.4.1" || bad "no update offer after launch"
key DPAD_RIGHT DPAD_CENTER;                 shot 11-later 0.5      # "Later"

# ---------- 6. Checksum protection: the panel says 10007 but the link now serves other bytes ----------
settings 60
place dist/qa-10006.apk                     # panel still announces 10007 and its checksum
open_settings; key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN
shot 12-row-available 0.5
key DPAD_CENTER;                            shot 13-offer-from-settings 0.5
key DPAD_CENTER;                            sleep 6; shot 14-checksum-failed 0.3
screen_has "checksum" && ok "wrong file at the link refused by checksum" || bad "checksum mismatch not reported"
key DPAD_CENTER
[ "$(installed_code)" = "10006" ] && ok "nothing installed after a failed check" || bad "version changed unexpectedly"

# ---------- 7. Real update: download, verify, installer ----------
place dist/qa-10007.apk; settings 0
key DPAD_CENTER;                            sleep 3; shot 15-available-again 0.3
key DPAD_CENTER;                            shot 16-downloading 1.2
sleep 8;                                    shot 17-after-download 0.5
if screen_has "Allow Flix Town to install"; then
  ok "asks for the install permission first"
  key DPAD_RIGHT DPAD_CENTER                # "Not now"; grant it as the viewer would in Settings
  adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
  shot 18-ready-to-install 0.5
  screen_has "Ready to install" && ok "row shows 'Ready to install'" || bad "row does not show 'Ready to install'"
  key DPAD_CENTER;                          sleep 3
fi
shot 19-android-installer 1
if tap_text "Update|Install"; then ok "Android's installer confirmation shown and accepted"; else bad "installer button not found"; fi
sleep 15;                                   shot 20-after-install 0.5
CODE="$(installed_code)"
[ "$CODE" = "10007" ] && ok "installed version is now 10007" || bad "installed version is $CODE, expected 10007"

# ---------- 8. Sign-in and settings kept ----------
launch;                                     sleep 12; shot 21-relaunch-home 0.5
screen_has "Your movies are waiting" && bad "sign-in was lost" || ok "still signed in after the update"
open_settings; key DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 22-settings-after-update 0.5
screen_has 'text="Off"' && ok "autoplay row still Off" || bad "autoplay row not Off"
adb shell run-as $PKG cat shared_prefs/flix.xml 2>/dev/null | grep -q 'name="autoplay_next" value="false"' && ok "autoplay preference kept (off)" || bad "autoplay preference lost"
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 23-version-after-update 0.5
screen_has "3.4.1" && ok "Settings shows version 3.4.1" || bad "Settings does not show 3.4.1"
key DPAD_CENTER; sleep 3; shot 24-up-to-date-after-update 0.3
screen_has "is up to date" && ok "after updating: up to date" || bad "after updating: not 'up to date'"

adb logcat -d | grep -E "FlixTown|AndroidRuntime|FATAL" | tail -n 200 > "$OUT/app-log.txt" || true
note "update flow: $PASS passed, $FAIL failed"
