#!/usr/bin/env bash
# Release check on a 1080p Android TV emulator (QA build, offline demo server, real demo video).
# Every step is checked from the live UI (uiautomator) or Android's own state (activity, IME),
# not just screenshotted. Results: qa/release-check/report.txt and qa/release-check/screens/*.png
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=qa/release-check; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

adb install -r "$APK" > /dev/null || exit 1
adb shell settings put system screen_off_timeout 1800000 || true
shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; echo "shot $1"; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am force-stop $PKG; adb shell am start -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
dump(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; }
has(){ dump; grep -q -- "$1" "$OUT/ui.xml"; }
wait_for(){ local t=0; while [ $t -lt "$2" ]; do has "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
resumed(){ adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -1; }
in_activity(){ resumed | grep -q "$1"; }
wait_activity(){ local t=0; while [ $t -lt "$2" ]; do in_activity "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
# Text or description of the focused view (or of its first labelled descendant).
focused(){ dump; python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
r=ET.parse(sys.argv[1]).getroot()
for n in r.iter('node'):
    if n.get('focused')=='true':
        for c in n.iter('node'):
            v=c.get('text') or c.get('content-desc')
            if v: print(v); sys.exit()
        print(''); sys.exit()
EOF
}
# Presses a key until the focused view's label matches (max n presses).
focus_to(){ local k=$1 want=$2 n=${3:-6}; for i in $(seq 1 $n); do focused | grep -q -- "$want" && return 0; key "$k"; done; focused | grep -q -- "$want"; }
focused_id(){ dump; python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    if n.get('focused')=='true': print(n.get('resource-id','')); break
EOF
}
text_of_id(){ dump; python3 - "$OUT/ui.xml" "$1" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    if n.get('resource-id','').endswith(':id/'+sys.argv[2]): print(n.get('text','')); break
EOF
}
# QA-build state lines (UI dumps do not work while video is playing).
qa_line(){ adb logcat -d -s FlixTownQA:I | grep " player " | tail -1; }
qaf(){ qa_line | python3 -c 'import re,sys
l=sys.stdin.read();k=sys.argv[1]
m=re.search(k+r"=\[([^\]]*)\]",l) or re.search(k+r"=(.*)$" if k=="subtitle" else k+r"=(\S+)",l)
print(m.group(1).strip() if m else "")' "$1"; }
picker_line(){ adb logcat -d -s FlixTownQA:I | grep " picker $1" | tail -1; }
# Moves through the player controls until the QA state reports the wanted control focused.
player_focus(){ for i in 1 2 3 4; do [ "$(qaf focus)" = "$1" ] && return 0; key "$2"; sleep 0.6; done; [ "$(qaf focus)" = "$1" ]; }
wait_qa(){ local t=0; while [ $t -lt "$3" ]; do [ "$(qaf "$1")" = "$2" ] && return 0; sleep 1; t=$((t+1)); done; return 1; }
secs(){ python3 -c 'import sys;p=sys.argv[1].lstrip("-").split(":");print(sum(int(x)*60**i for i,x in enumerate(reversed(p))) if all(x.isdigit() for x in p) else -1)' "$1"; }
refreshes(){ adb logcat -d -s FlixTown:I | grep -c "Refresh started" ; }

# ---------------- 1. QR activation, remote sign-in and keyboard ----------------
launch --ez demo_reset true --ez demo_hold_qr true
wait_for "FT4K2Q" 20 && ok "QR activation: code shown next to the QR" || bad "QR activation code not shown"
check "QR activation: QR image drawn" 'grep -q "Activation QR code" "$OUT/ui.xml"'
shot 01-qr-activation 0.3
check "QR screen: 'Sign in with remote instead' focused (keyboard closed)" 'focused | grep -q "Sign in with remote instead"'
check "keyboard does not open by itself" '! adb shell dumpsys input_method | grep -q "mInputShown=true"'
key DPAD_CENTER;                             shot 02-remote-form 0.6
check "remote sign-in form opens with Username focused" 'focused_id | grep -q ":id/username"'
key DPAD_CENTER;                             sleep 1.5
check "OK on a field opens the on-screen keyboard" 'adb shell dumpsys input_method | grep -q "mInputShown=true"'
shot 03-keyboard-open 0.3
adb shell input text demo;                   sleep 0.5
key ENTER;                                   sleep 1         # IME "Next" → Password
adb shell input text demo;                   sleep 0.5
shot 04-form-filled 0.3
key ENTER                                                    # IME "Done" → Sign in
wait_activity HomeActivity 40 && ok "signed in with the remote keyboard (Home opened)" || bad "remote sign-in did not reach Home ($(resumed))"
sleep 6;                                     shot 05-home-after-sign-in 0.5

# ---------------- 2. Saved login, startup refresh, no refresh during navigation ----------------
adb logcat -c
launch;                                      sleep 14
check "saved login: relaunch goes straight to Home" 'in_activity HomeActivity && ! has "Your movies are waiting"'
check "startup: one catalog refresh on launch" '[ "$(refreshes)" -ge 1 ]'
before="$(refreshes)"
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;         sleep 2          # Movies
key DPAD_CENTER;                             sleep 3          # Details
key BACK;                                    sleep 1.5
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;         sleep 2          # TV Shows
key DPAD_LEFT DPAD_UP DPAD_UP DPAD_CENTER;   sleep 2          # Home
check "no catalog refresh during normal navigation" '[ "$(refreshes)" = "$before" ]'

# ---------------- 3. Panel-configured intro ----------------
launch --ez demo_intro true
wait_activity IntroActivity 8 && wait_for "Skip intro" 12 && ok "intro plays when the panel enables it, with Skip" || bad "intro not shown ($(resumed))"
shot 06-intro 1.5
key DPAD_CENTER
wait_activity HomeActivity 15 && ok "Skip intro opens Home" || bad "Skip intro did not open Home"
launch;                                      sleep 4
check "intro disabled in the panel: no intro" '! in_activity IntroActivity'
sleep 8

# ---------------- 4. Movies: sort, categories, focus restoration ----------------
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;         sleep 2.5        # menu → Movies
shot 07-movies-grid 0.3
key DPAD_UP;                                 sleep 0.6        # Sort chip
check "Sort chip reachable from the grid" 'focused | grep -q "Sort:"'
key DPAD_CENTER;                             shot 08-sort-picker 0.8
check "sort picker lists Title A–Z and Top Rated" 'has "Title A–Z" && has "Top Rated"'
key DPAD_DOWN DPAD_CENTER;                   sleep 1.5
check "sorted by title" 'has "Sort: Title A–Z"'
order="$(dump; python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
menu={"Search","Home","Movies","TV Shows","My List","Settings"}
names=[n.get('content-desc') for n in ET.parse(sys.argv[1]).getroot().iter('node') if n.get('content-desc') and n.get('content-desc') not in menu and n.get('clickable')=='true' and n.get('class','').endswith('LinearLayout')]
print("ok" if len(names)>=4 and [x.lower() for x in names[:6]]==sorted(x.lower() for x in names[:6]) else "bad "+"|".join(names[:6]))
EOF
)"
[ "$order" = "ok" ] && ok "grid titles are in A–Z order" || bad "grid not in A–Z order ($order)"
key DPAD_RIGHT DPAD_CENTER;                  shot 09-category-picker 0.8
key DPAD_DOWN DPAD_DOWN DPAD_CENTER;         sleep 1.5
check "category filter applied (chip shows the category)" 'has "Drama"'
key DPAD_DOWN;                               sleep 0.6
key DPAD_RIGHT DPAD_DOWN;                    sleep 0.6
picked="$(focused)"
key DPAD_CENTER;                             sleep 3
check "details page opens for the chosen title" 'in_activity DetailsActivity'
key BACK;                                    sleep 1.5
check "Back returns focus to the same poster ($picked)" '[ -n "$picked" ] && [ "$(focused)" = "$picked" ]'
shot 10-focus-restored 0.2

# ---------------- 5. Movie details: cast, trailer, My List ----------------
key DPAD_CENTER;                             sleep 3.5;       shot 11-movie-details 0.3
check "details: Play is the focused primary action" 'focused | grep -q "Play\|Resume"'
check "details: cast row with names and portraits" 'has "Ava Moreno" && has "Daniel Okafor"'
check "details: More Like This row" 'has "More Like This"'
focus_to DPAD_RIGHT "Trailer" 2 && ok "trailer button enabled" || bad "trailer button not reachable"
key DPAD_CENTER
wait_activity PlayerActivity 10 && ok "trailer plays in the player" || bad "trailer did not open ($(resumed))"
sleep 4; key BACK; sleep 1; in_activity PlayerActivity && key BACK
wait_activity DetailsActivity 6 && ok "Back from the trailer returns to details" || bad "Back from trailer went to $(resumed)"
title="$(dump; python3 - "$OUT/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
t=[n.get('text') for n in ET.parse(sys.argv[1]).getroot().iter('node') if n.get('text') and n.get('class','').endswith('TextView')]
print(t[0] if t else '')
EOF
)"
focus_to DPAD_RIGHT "My List" 3
before_label="$(focused)"
key DPAD_CENTER;                             sleep 0.8
check "My List toggles on details ('+ My List' → '✓ My List')" 'echo "$before_label" | grep -q "+" && focused | grep -q "✓"'
shot 12-my-list-added 0.2
key BACK;                                    sleep 1.5
key DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT; sleep 0.6
focus_to DPAD_DOWN "My List" 4; key DPAD_CENTER; sleep 2
check "My List page shows the saved title ($title)" '[ -n "$title" ] && has "$title"'
shot 13-my-list 0.2

# ---------------- 6. Series: seasons, episodes, resume / start over ----------------
key DPAD_LEFT; focus_to DPAD_UP "TV Shows" 3; key DPAD_CENTER; sleep 2.5
key DPAD_CENTER;                             sleep 4;         shot 14-series-details 0.3
check "series details: season selector" 'has "Season 1  ›"'
focus_to DPAD_DOWN "Season 1" 3; key DPAD_CENTER; sleep 1
check "season picker lists every season" 'has "Season 2" && has "Season 3"'
shot 15-season-picker 0.2
key DPAD_DOWN DPAD_CENTER;                   sleep 1.5
check "season 2 selected" 'has "Season 2  ›"'
key DPAD_DOWN;                               sleep 0.8
key DPAD_RIGHT;                              sleep 0.6       # S2 E2 (E1 is kept for resume)
key DPAD_CENTER
wait_activity PlayerActivity 10 && ok "episode plays from the episode row" || bad "episode did not play"
sleep 25;                                    shot 16-episode-playing 0.2
key BACK;                                    sleep 1; in_activity PlayerActivity && key BACK
wait_activity DetailsActivity 6; sleep 1.5
check "details now offers Continue for the show" 'has "Continue"'
focus_to DPAD_UP "Continue" 4; key DPAD_CENTER
wait_for "Continue watching?" 15 && ok "resume prompt: 'Continue watching?'" || bad "no resume prompt"
check "resume prompt offers Continue from … and Start over" 'has "Continue from" && has "Start over"'
shot 17-resume-prompt 0.2
key DPAD_RIGHT DPAD_CENTER;                  sleep 5
e="$(qaf elapsed)"
[ -n "$e" ] && [ "$e" -lt 20 ] && ok "Start over plays from the beginning (${e}s)" || bad "Start over position '${e}'s ($(qa_line))"

# ---------------- 7. Player: controls, seeking, audio, subtitles, Back ----------------
key DPAD_UP;                                 shot 18-player-controls 0.5
wait_qa controls true 3 && ok "Up shows the player controls" || bad "controls not shown ($(qa_line))"
wait_qa controls false 8 && ok "controls hide by themselves" || bad "controls stay on screen ($(qa_line))"
key DPAD_UP; sleep 0.5; key DPAD_DOWN; sleep 0.8                # controls, then the progress bar
start="$(qaf elapsed)"
adb shell input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; sleep 2.5
after="$(qaf elapsed)"
[ -n "$start" ] && [ -n "$after" ] && [ "$after" -ge $((start+20)) ] && ok "seeking: 3 × Right moves ~30s (${start}s → ${after}s)" || bad "seeking did not move (${start}s → ${after}s)"
shot 19-seeked 0.1
wait_qa controls false 8
key DPAD_UP; sleep 0.8
player_focus audio DPAD_RIGHT || note "INFO  player focus: $(qa_line)"
audio1="$(qaf audio)"
key DPAD_CENTER;                             sleep 1.5
note "INFO  $(adb logcat -d -s FlixTownQA:I | grep ' tracks type=1' | tail -1)"
pl="$(picker_line Audio)"; note "INFO  $pl"
echo "$pl" | grep -q "|" && ok "audio picker lists the stream's audio tracks" || bad "audio picker missing or one track ($pl)"
shot 20-audio-picker 0.2
key DPAD_DOWN DPAD_CENTER;                   sleep 2
audio2="$(qaf audio)"
[ "$audio1" != "$audio2" ] && ok "audio track changed ($audio1 → $audio2)" || bad "audio track label unchanged ($audio1)"
wait_qa controls false 8
key DPAD_UP; sleep 0.8
player_focus subs DPAD_RIGHT || note "INFO  player focus: $(qa_line)"
subs1="$(qaf subs)"
key DPAD_CENTER;                             sleep 1.5
pl="$(picker_line Subtitles)"; note "INFO  $pl"
echo "$pl" | grep -q "Off" && echo "$pl" | grep -q "|" && ok "subtitle picker offers Off and the stream's languages" || bad "subtitle picker wrong ($pl)"
shot 21-subtitle-picker 0.2
key DPAD_DOWN DPAD_CENTER;                   sleep 2
subs2="$(qaf subs)"
[ "$subs1" != "$subs2" ] && ok "subtitles changed ($subs1 → $subs2)" || bad "subtitle label unchanged ($subs1)"
wait_qa controls false 8
key DPAD_UP; sleep 0.8
key BACK; sleep 1.2
in_activity PlayerActivity && [ "$(qaf controls)" = "false" ] && ok "Back with controls shown: hides them, stays in the player" || bad "Back with controls: $(resumed) $(qa_line)"
key BACK;                                    sleep 1.5
check "Back again leaves the player" '! in_activity PlayerActivity'

# ---------------- 8. Next episode autoplay ----------------
in_activity DetailsActivity || key BACK
focus_to DPAD_DOWN "Season" 4; key DPAD_DOWN; sleep 0.6
key DPAD_RIGHT DPAD_RIGHT;                   sleep 0.5        # a later episode
adb logcat -c
key DPAD_CENTER;                             sleep 6
if [ "$(qaf card)" = "Continue watching?" ]; then key DPAD_RIGHT DPAD_CENTER; sleep 3; fi
sub1="$(qaf subtitle)"; note "INFO  playing: $sub1"
key DPAD_UP; sleep 0.5; key DPAD_DOWN; sleep 0.5
for i in 1 2 3 4; do adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 0.2; done
wait_qa card "Up next" 150 && ok "next episode offered near the end ('Up next')" || bad "no 'Up next' card ($(qa_line))"
shot 22-up-next 0.2
key DPAD_CENTER;                             sleep 5
sub="$(qaf subtitle)"
[ -n "$sub" ] && [ "$sub" != "$sub1" ] && ok "Play now starts the next episode ($sub1 → $sub)" || bad "next episode not started ($sub1 → $sub)"
key BACK; sleep 1; in_activity PlayerActivity && key BACK; sleep 1

# ---------------- 9. Expired account: renewal and Cash App ----------------
launch --ez demo_expired true
wait_for "Your subscription has ended" 30 && ok "expired account opens the renewal screen" || bad "renewal screen not shown ($(resumed))"
shot 23-renewal 0.3
check "renewal shows plans with panel prices" 'has "\$15"'
check "renewal shows the panel's Cash App details" 'has "FlixTownDemo"'
focus_to DPAD_DOWN "Send renewal request" 8 || note "INFO  renewal focus is on: $(focused)"
key DPAD_CENTER; sleep 3
check "renewal request is sent" 'has "Request sent"'
shot 24-renewal-sent 0.2
launch;                                      sleep 4
wait_activity HomeActivity 30 && ok "after the payment is confirmed (account active) Flix Town reopens" || bad "active account did not leave renewal ($(resumed))"

# ---------------- 10. Performance sample (emulator only) ----------------
adb shell dumpsys gfxinfo $PKG reset > /dev/null
key DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_LEFT DPAD_LEFT DPAD_UP DPAD_UP
adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile" | sed 's/^/INFO  emulator frames: /' | tee -a "$REPORT"

adb logcat -d | grep -E "AndroidRuntime|FATAL" | tail -n 100 > "$OUT/crash-log.txt" || true
check "no crash during the run" '! grep -q "FATAL EXCEPTION" "$OUT/crash-log.txt"'
note "release check: $PASS passed, $FAIL failed"
