#!/usr/bin/env bash
# Automatic update flow on the Android TV emulator, against the real panel update module running
# on this machine. One APK link (apk/flixtown.apk) is replaced in place, as on the real panel:
# an older installed build must be offered the newer one automatically on launch (no Settings),
# update, keep sign-in and settings, and stop prompting once current.
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

OLD=10010; NEW=10011
popup(){ screen_has "Update available" || screen_has "Update required"; }
adb logcat -c
adb install -r dist/qa-$OLD.apk > /dev/null && ok "installed the older build ($OLD)" || bad "install $OLD failed"

# ---------- 1. Panel publishes the same version that is installed ----------
place dist/qa-$OLD.apk
admin --data-urlencode action=login --data-urlencode password=qa-pass
admin --data-urlencode action=save --data-urlencode slot=test --data-urlencode "url=$APK_URL" --data-urlencode "notes=Faster start-up and smoother menus."
[ "$(code_on_panel)" = "$OLD" ] && ok "panel detected $OLD from the APK" || bad "panel did not detect $OLD ($(api))"

# ---------- 2. Fresh launch while current: no automatic popup ----------
launch --ez demo_reset true
sleep 32;                                   shot 01-home-current 1
popup && bad "popup shown although the app is current" || ok "current version: no automatic popup"

# ---------- 3. Settings: manual check says up to date (and set a preference to keep) ----------
open_settings
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; shot 02-autoplay-off 0.5
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 3; shot 03-manual-up-to-date 0.3
screen_has "up to date" && screen_has "Version 3.5.0" && screen_has 'text="Done"' && ok "manual check: 'You’re up to date', version shown, Done" || bad "manual up-to-date dialog wrong"
key DPAD_CENTER

# ---------- 4. Offline start: silent; manual check reports the failure ----------
kill $PANEL; sleep 1
adb shell am force-stop $PKG; launch --ez demo_offline true
sleep 16;                                   shot 04-offline-start 0.5
popup || screen_has "Unable to check" && bad "offline start showed an update dialog" || ok "offline start: no update dialog (silent)"
open_settings; key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
sleep 4;                                    shot 05-manual-check-offline 0.3
screen_has "Unable to check for updates" && screen_has "Try again" && screen_has 'text="Close"' && ok "manual check offline: 'Unable to check for updates', Try again / Close" || bad "offline manual check dialog wrong"
! screen_has "up to date" && ok "offline is never reported as up to date" || bad "offline reported as up to date"
key DPAD_RIGHT DPAD_CENTER                  # Close
start_panel

# ---------- 5. Publish a newer APK at the same link (Detect) ----------
place dist/qa-$NEW.apk
admin --data-urlencode action=detect --data-urlencode slot=test
[ "$(code_on_panel)" = "$NEW" ] && ok "same link, newer APK detected ($NEW)" || bad "panel did not detect $NEW"

# ---------- 6. Fresh launch: popup appears by itself on Home ----------
adb shell am force-stop $PKG; launch
sleep 16;                                   shot 06-automatic-popup 0.5
screen_has "Update available" && screen_has "Update now" && screen_has 'text="Later"' && ok "fresh launch: 'Update available' popup with Update now / Later" || bad "no automatic popup on launch"
screen_has "Faster start-up" && ok "release notes shown" || bad "release notes missing"

# ---------- 7. Later, then navigate: no duplicate popups ----------
key DPAD_RIGHT DPAD_CENTER;                 shot 07-after-later 0.6
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;        sleep 2; shot 08-movies 0.3
key BACK;                                   sleep 2; shot 09-back-home 0.3
popup && bad "popup came back during navigation" || ok "after Later: no duplicate popup while navigating"

# ---------- 8. Required update: no Later, Back keeps it ----------
admin --data-urlencode action=save --data-urlencode slot=test --data-urlencode "url=$APK_URL" --data-urlencode "notes=Faster start-up and smoother menus." --data-urlencode required=1
api | grep -q '"update_required":true' && ok "panel marks the update required" || bad "required flag not published"
adb shell am force-stop $PKG; launch
sleep 16;                                   shot 10-required-popup 0.5
screen_has "Update required" && ! screen_has 'text="Later"' && ok "required update: 'Update required', only Update now" || bad "required popup wrong"
key BACK;                                   shot 11-back-on-required 0.6
screen_has "Update required" && ok "Back does not dismiss a required update" || bad "required popup dismissed by Back"

# ---------- 9. Update now: verified download, Android installer ----------
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
key DPAD_CENTER;                            shot 12-downloading 0.8
sleep 8;                                    shot 13-android-installer 0.5
tap_text "Update|Install" && ok "Android's installer confirmation accepted" || bad "installer button not found"
sleep 15;                                   shot 14-installed 0.5
[ "$(installed_code)" = "$NEW" ] && ok "installed version is now $NEW" || bad "installed version is $(installed_code)"

# ---------- 10. After the update: signed in, settings kept, no popup ----------
launch;                                     sleep 16; shot 15-after-update 0.5
screen_has "Your movies are waiting" && bad "sign-in lost" || ok "still signed in after the update"
popup && bad "popup shown although now current" || ok "now current: no automatic popup"
adb shell run-as $PKG cat shared_prefs/flix.xml 2>/dev/null | grep -q 'name="autoplay_next" value="false"' && ok "autoplay setting kept (Off)" || bad "autoplay setting lost"
open_settings; key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER
sleep 3;                                    shot 16-manual-up-to-date-new 0.3
screen_has "Version 3.5.1" && ok "manual check after update: up to date, version 3.5.1" || bad "manual check after update wrong"
key DPAD_CENTER
adb shell am force-stop $PKG; launch;       sleep 16; shot 17-next-launch 0.5
popup && bad "popup on a later launch" || ok "later launch: still no popup"

adb logcat -d | grep -E "FlixTown|AndroidRuntime|FATAL" | tail -n 200 > "$OUT/app-log.txt" || true
note "update flow: $PASS passed, $FAIL failed"
