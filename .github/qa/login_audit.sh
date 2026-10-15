#!/usr/bin/env bash
# Focused check for the production audit fixes (QA build, offline demo server):
#  1. an expired QR code is replaced by a fresh one and pairing still completes;
#  2. signing in with a DIFFERENT account removes the previous account's Continue Watching / progress;
#     signing back in to the SAME account keeps them (the existing behaviour);
#  3. migration: data with no recorded owner is kept for a TV still signed in (owner recorded on start)
#     and removed when an account signs in on a TV that was signed out before updating;
#  4. next-episode autoplay continues E1 -> E2 -> E3, across a season boundary, after resuming from
#     Continue Watching, and with a cut-off queue the show stays in Continue Watching on the next episode;
#  5. a failing stream's address and credentials do not appear in the log.
# Results: qa/login-audit/report.txt and screens.
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=${QA_OUT:-qa/login-audit}; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am force-stop $PKG; adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
dump(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }
has(){ dump; grep -q -- "$1" "$OUT/ui.xml"; }
wait_for(){ local t=0; while [ $t -lt "$2" ]; do has "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
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
prefs(){ adb shell "run-as $PKG cat shared_prefs/flix.xml" 2>/dev/null; }
drop_owner(){ adb shell am force-stop $PKG; sleep 1; adb shell "run-as $PKG sed -i '/name=\"account_owner\"/d' shared_prefs/flix.xml"; }
pref_value(){ prefs | grep -o "name=\"$1\">[^<]*" | sed 's/.*>//'; }
qa_line(){ adb logcat -d -s FlixTownQA:I | grep " player " | tail -1; }
qaf(){ qa_line | python3 -c 'import re,sys
l=sys.stdin.read();k=sys.argv[1]
m=re.search(k+r"=\[([^\]]*)\]",l) or re.search(k+r"=(\S+)",l)
print(m.group(1).strip() if m else "")' "$1"; }
wait_qa(){ local t=0; while [ $t -lt "$3" ]; do [ "$(qaf "$1")" = "$2" ] && return 0; sleep 1; t=$((t+1)); done; return 1; }
demo_live(){ printf '%s' "$1" | adb shell "run-as $PKG sh -c 'cat > files/demo_account.json'"; }
to_end(){ key DPAD_UP; sleep 0.5; key DPAD_DOWN; sleep 0.5; for i in $(seq 1 12); do adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.15; done; }
progress_keys(){ prefs | grep -cE 'name="(continue_ids|resume_[a-z_]*)' ; }
continue_ids(){ prefs | grep -o 'name="continue_ids">[^<]*' | sed 's/.*>//'; }
signed_in_as(){ key DPAD_LEFT; focus_to DPAD_UP "Search" 8 >/dev/null; focus_to DPAD_DOWN "Settings" 8 >/dev/null; key DPAD_CENTER; sleep 2
  dump; grep -o 'Signed in as [^. "]*' "$OUT/ui.xml" | head -1; }
sign_out(){ signed_in_as > /dev/null; focus_to DPAD_DOWN "Sign out" 12 > /dev/null || note "INFO  Sign out row not focused: $(focused)"
  key DPAD_CENTER; sleep 1; focus_to DPAD_RIGHT "Sign out" 2 > /dev/null; key DPAD_CENTER; wait_activity LoginActivity 15; }
remote_sign_in(){ focus_to DPAD_DOWN "Sign in with remote" 4 > /dev/null; key DPAD_CENTER; sleep 1
  key DPAD_CENTER; sleep 1; adb shell input text "$1"; sleep 0.5; key ENTER; sleep 0.8; adb shell input text "$2"; sleep 0.5; key ENTER
  wait_activity HomeActivity 40; sleep 6; }

adb install -r "$APK" > /dev/null || { bad "install"; exit 1; }
adb shell settings put system screen_off_timeout 1800000 || true

# ---------------- 1. Expired QR code ----------------
launch --ez demo_reset true --ez demo_qr_expire true
wait_for "FT4K2Q" 20 && ok "QR: first code shown (FT4K2Q)" || bad "QR: first code not shown"
shot 01-first-code 0.2
wait_for "FT7N3R" 30 && ok "QR: the expired code was replaced by a fresh one (FT7N3R)" || bad "QR: no fresh code after expiry ($(resumed))"
shot 02-fresh-code 0.2
wait_activity HomeActivity 45 && ok "QR: approval of the fresh code opens Home" || bad "QR: fresh code did not reach Home ($(resumed))"
sleep 6; who="$(signed_in_as)"
check "QR: signed in as the approved account ($who)" '[ "$who" = "Signed in as 0048213977" ]'
key BACK; sleep 1

# ---------------- 2. Account switch ----------------
demo_live '{"hold_qr":true}'   # the demo QR stays pending, so only the remote sign-ins below sign in
launch --ez demo_continue true; wait_activity HomeActivity 45; sleep 6   # A: progress for a show and a movie
a_ids="$(continue_ids)"; note "INFO  account A Continue Watching: $a_ids"
check "account A has Continue Watching progress" '[ -n "$a_ids" ] && [ "$(progress_keys)" -ge 2 ]'
sign_out
check "after signing out, the login screen is shown" 'in_activity LoginActivity'
check "signing out alone keeps this TV's progress (as before)" '[ "$(continue_ids)" = "$a_ids" ]'
remote_sign_in 0012345678 0000123456789012
check "account B signed in (Home)" 'in_activity HomeActivity'
shot 03-home-account-b 0.3
check "account B does not get account A's Continue Watching or progress" '[ -z "$(continue_ids)" ] && [ "$(progress_keys)" = 0 ]'
check "account B's Home shows no Continue Watching row" '! has "Continue Watching"'
who="$(signed_in_as)"; check "Settings shows account B ($who)" '[ "$who" = "Signed in as 0012345678" ]'
key BACK; sleep 1
# Same account again: its own progress stays.
launch --ez demo_continue true; wait_activity HomeActivity 45; sleep 6
b_ids="$(continue_ids)"
sign_out
remote_sign_in 0012345678 0000123456789012
check "signing back in to the same account keeps its progress ($b_ids)" '[ -n "$b_ids" ] && [ "$(continue_ids)" = "$b_ids" ]'

# ---------------- 3. Migration: data with no recorded owner ----------------
launch --ez demo_continue true; wait_activity HomeActivity 45; sleep 6
drop_owner                                        # as if updated from 3.8.13 while signed in
launch; wait_activity HomeActivity 45; sleep 6
check "migration, signed in: the owner is recorded on start" '[ -n "$(pref_value account_owner)" ]'
c_ids="$(continue_ids)"
sign_out; remote_sign_in 0012345678 0000123456789012
check "migration, signed in: the same customer keeps progress after signing out and in ($c_ids)" '[ -n "$c_ids" ] && [ "$(continue_ids)" = "$c_ids" ]'
sign_out; drop_owner                              # as if signed out on 3.8.13 before updating
launch; wait_activity LoginActivity 20; sleep 3
remote_sign_in 0012345678 0000123456789012
check "migration, signed out: data with no owner is not handed to the next sign-in" '[ -z "$(continue_ids)" ] && [ "$(progress_keys)" = 0 ]'

# ---------------- 4. Autoplay: several episodes, season boundary, after resuming, cut-off queue ----------------
launch --ez demo_reset true; wait_activity HomeActivity 60; sleep 8      # fresh QR sign-in, no saved progress
episode_now(){ qaf subtitle | grep -o 'S[0-9]* · E[0-9]*'; }
wait_episode(){ local t=0; while [ $t -lt 15 ]; do e="$(episode_now)"; [ -n "$e" ] && [ "$e" != "$1" ] && { echo "$e"; return 0; }; sleep 1; t=$((t+1)); done; episode_now; }
next_now(){ to_end; wait_qa card "Up next" 150 || { note "      no Up next: $(qa_line)"; return 1; }; key DPAD_CENTER; sleep 4; }
open_show(){ back_home; focus_to DPAD_LEFT "Home" 8 > /dev/null; focus_to DPAD_DOWN "TV Shows" 4 > /dev/null; key DPAD_CENTER; sleep 2.5
  key DPAD_CENTER; wait_activity DetailsActivity 10; sleep 4; }
play_episode(){ focus_to DPAD_DOWN "Season" 5 > /dev/null; key DPAD_DOWN; sleep 0.8
  for i in $(seq 2 "$1"); do key DPAD_RIGHT; done; sleep 0.5; key DPAD_CENTER
  wait_activity PlayerActivity 10 || { shot "no-player-e$1" 0.2; note "      player not opened: $(resumed) focus='$(focused)'"; return 1; }
  sleep 4; if [ "$(qaf card)" = "Continue watching?" ]; then key DPAD_CENTER; sleep 3; fi; }
leave_player(){ for i in 1 2 3; do in_activity PlayerActivity || break; key BACK; sleep 1.2; done; }
back_home(){ for i in 1 2 3 4 5 6 7; do in_activity HomeActivity && return 0; key BACK; sleep 1.5; done; in_activity HomeActivity; }
home_row(){ back_home; focus_to DPAD_LEFT "Home" 8 > /dev/null; focus_to DPAD_UP "Home" 5 > /dev/null; key DPAD_CENTER; sleep 2.5
  key DPAD_UP DPAD_UP DPAD_UP; sleep 0.8; key DPAD_DOWN; sleep 1.5; }

# 4a. E1 -> E2 -> E3
adb logcat -b main -c
open_show; play_episode 1 && ok "4a: first episode plays ($(episode_now))" || bad "4a: first episode did not play"
shot 04-e1-playing 0.2
e1="$(episode_now)"; next_now; e2="$(wait_episode "$e1")"; next_now; e3="$(wait_episode "$e2")"
check "4a: autoplay continues E1 -> E2 -> E3 ($e1 -> $e2 -> $e3)" '[ "$e1" = "S1 · E1" ] && [ "$e2" = "S1 · E2" ] && [ "$e3" = "S1 · E3" ]'
leave_player
# 4b. Season boundary: S1 E8 -> S2 E1 -> S2 E2
open_show; play_episode 8
b1="$(episode_now)"; next_now; b2="$(wait_episode "$b1")"; next_now; b3="$(wait_episode "$b2")"
check "4b: across the season boundary ($b1 -> $b2 -> $b3)" '[ "$b1" = "S1 · E8" ] && [ "$b2" = "S2 · E1" ] && [ "$b3" = "S2 · E2" ]'
leave_player
# 4c. Resume from Continue Watching, then autoplay keeps going
home_row; shot 05-continue-row 0.2
key DPAD_CENTER; wait_activity PlayerActivity 20; sleep 4
if [ "$(qaf card)" = "Continue watching?" ]; then key DPAD_CENTER; sleep 3; fi
r1="$(episode_now)"; next_now; r2="$(wait_episode "$r1")"; next_now; r3="$(wait_episode "$r2")"
check "4c: resumed from Continue Watching, autoplay continues ($r1 -> $r2 -> $r3)" '[ "$r1" = "S2 · E2" ] && [ "$r2" = "S2 · E3" ] && [ "$r3" = "S2 · E4" ]'
leave_player
# 4d. Cut-off queue (QA limit 1): the last episode the player knows is not the end of the show
demo_live '{"queue_limit":1}'
open_show; play_episode 1
c1="$(episode_now)"; next_now; c2="$(wait_episode "$c1")"; next_now; c3="$(wait_episode "$c2")"
check "4d: with a cut-off queue, autoplay still reaches the queued episodes ($c1 -> $c2 -> $c3)" '[ "$c3" = "S1 · E3" ]'
to_end; wait_activity DetailsActivity 150 && ok "4d: after the last queued episode the player closes (no Up next)" || bad "4d: player did not close ($(qa_line))"
sleep 2
check "4d: the show stays in Continue Watching" 'continue_ids | grep -q "series:"'
check "4d: Continue Watching moves on to S1 E4 (9104), nothing marked complete" 'prefs | grep -q "name=\"resume_episode_series:[0-9]*\">9104<" && ! prefs | grep -q "name=\"resume_done_"'
key BACK; sleep 1; demo_live '{}'

# ---------------- 5. No stream address or credentials in the log ----------------
adb logcat -b main -c
demo_live '{"stream":"http://10.0.2.2:9/series/AUDITUSER77/AUDITPASS88/9101.mkv"}'
open_show; play_episode 1; sleep 6
check "5: the player opened the failing stream and shows the error card" '[ "$(qaf card)" = "This title couldn'"'"'t be played" ]'
adb logcat -d > "$OUT/logcat.txt"
check "5: the log names the playback failure" 'grep -q "Playback failed:" "$OUT/logcat.txt"'
check "5: the log contains no stream credentials" 'grep -q "Playback failed:" "$OUT/logcat.txt" && ! grep -q "AUDITPASS88\|AUDITUSER77" "$OUT/logcat.txt"'
grep "Playback failed:" "$OUT/logcat.txt" | head -2 | sed 's/^.*Playback failed/INFO  Playback failed/' | tee -a "$REPORT"
rm -f "$OUT/logcat.txt"
leave_player; demo_live '{}'

crashes="$(adb logcat -d -b crash | grep -A1 'FATAL EXCEPTION' | grep -c "Process: $PKG")"
check "no Flix Town crash" '[ "$crashes" = 0 ]'
note ""; note "login audit: $PASS passed, $FAIL failed"
