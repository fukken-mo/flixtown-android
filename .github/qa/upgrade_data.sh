#!/usr/bin/env bash
# Workflow: .github/workflows/release-final-checks.yml (QA builds only; nothing is published).
# Upgrade keeps the customer's data: the QA build of the released 3.8.18 source is signed in (QR, offline
# demo server), given settings and history, then the QA build of the new source is installed over it
# (same package and test key, like a customer update). After the new version opens, the sign-in still
# works (Home opens, not the sign-in screen) and every setting and history entry is unchanged.
# Usage: upgrade_data.sh OLD.apk NEW.apk     Results: $OUT/report.txt
set -u
PKG=com.myflixtown.tv.native.qa
OLD=$1; NEW=$2
OUT=${QA_OUT:-qa/upgrade-data}; rm -rf "$OUT" && mkdir -p "$OUT/screens"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
shot(){ adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$OUT/screens/$1.png" > /dev/null; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am force-stop $PKG; adb shell am start $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
resumed(){ adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -1; }
wait_activity(){ local t=0; while [ $t -lt "$2" ]; do resumed | grep -q "$1" && return 0; sleep 1; t=$((t+1)); done; return 1; }
prefs(){ adb shell "run-as $PKG cat shared_prefs/flix.xml"; }
version(){ adb shell dumpsys package $PKG | grep -m1 -o 'versionCode=[0-9]*'; }
# Settings and history a customer would have: names and values exactly as the app stores them
SEED=(autoplay_next:b:false subtitles_on:b:true subtitle_language:s:fr subtitle_size:f:1.4
  "favorites:s:|movie:1003||series:5002|" watched_movie:1005:b:true watched_movie:1007:b:true
  continue_ids:s:movie:1001 resume_position_movie:1001:l:600000 resume_duration_movie:1001:l:5400000 resume_extension_movie:1001:s:mp4)
seed(){ adb shell am force-stop $PKG; sleep 0.5; prefs > "$OUT/flix.xml"
  python3 - "$OUT/flix.xml" "${SEED[@]}" <<'EOF'
import sys,re
p=sys.argv[1];s=open(p).read()
for kv in sys.argv[2:]:
    k,t,v=kv.rsplit(':',2) if kv.count(':')>2 and kv.rsplit(':',2)[1] in 'bfsl' else kv.split(':',2)
    s=re.sub(r'\s*<[a-z]+ name="%s"[^>]*?(/>|>[^<]*</[a-z]+>)'%re.escape(k),'',s)
    tag={'b':'<boolean name="%s" value="%s" />','f':'<float name="%s" value="%s" />','l':'<long name="%s" value="%s" />'}.get(t,'<string name="%s">%s</string>')%(k,v)
    s=s.replace('</map>','    %s\n</map>'%tag)
open(p,'w').write(s)
EOF
  adb shell "run-as $PKG sh -c 'cat > shared_prefs/flix.xml'" < "$OUT/flix.xml"; }
# name=value of every preference, one per line, sorted
values(){ python3 -c 'import sys,re
for m in sorted(re.finditer(r"<(\w+) name=\"([^\"]+)\"(?: value=\"([^\"]*)\")?\s*(?:/>|>([^<]*)</\1>)",sys.stdin.read()),key=lambda m:m.group(2)):
    print("%s=%s"%(m.group(2),m.group(3) if m.group(3) is not None else m.group(4)))'; }

adb uninstall $PKG > /dev/null 2>&1
adb install "$OLD" > /dev/null && ok "3.8.18 source (QA build, $(version)) installed" || { bad "install old"; exit 1; }
launch --ez demo_reset true
wait_activity HomeActivity 60 && ok "3.8.18: signed in (QR) and Home opened" || bad "3.8.18: Home did not open ($(resumed))"
sleep 5; seed
launch; wait_activity HomeActivity 40 || bad "3.8.18: Home did not open with the seeded data"; sleep 8; shot before-home
adb shell am force-stop $PKG; sleep 1
prefs | values > "$OUT/before.txt"
for k in account_iv account_data account_owner server; do grep -q "^$k=." "$OUT/before.txt" || bad "3.8.18 has no $k before the upgrade"; done
note "INFO  before: $(wc -l < "$OUT/before.txt") preferences; $(grep -cE '^(favorites|watched_|continue_ids|resume_)' "$OUT/before.txt") history entries"

first=$(adb shell dumpsys package $PKG | grep -m1 firstInstallTime | tr -d '\r ')
out="$(adb install -r "$NEW" 2>&1)"; echo "$out" | grep -q Success && ok "new version installed over 3.8.18 ($(version))" || bad "upgrade install: $out"
[ "$first" = "$(adb shell dumpsys package $PKG | grep -m1 firstInstallTime | tr -d '\r ')" ] && ok "upgraded in place (same install)" || bad "reinstalled instead of upgraded"
adb logcat -b crash -c 2>/dev/null || true
launch; wait_activity HomeActivity 40 && ok "after the upgrade the TV opens Home, still signed in (no sign-in screen)" || bad "after the upgrade: $(resumed)"
sleep 10; shot after-home
launch; wait_activity HomeActivity 40 && ok "second launch after the upgrade: Home again" || bad "second launch: $(resumed)"
sleep 6; adb shell am force-stop $PKG; sleep 1
prefs | values > "$OUT/after.txt"

for k in account_iv account_data account_owner server; do
  check "sign-in kept: $k unchanged" '[ "$(grep "^$k=" "$OUT/before.txt")" = "$(grep "^$k=" "$OUT/after.txt")" ]'; done
for kv in "${SEED[@]}"; do k=${kv%%:*}; case "$kv" in watched_movie:*|resume_*_movie:*) k=$(echo "$kv" | cut -d: -f1-2);; esac
  check "kept: $k" 'b="$(grep -F "$k=" "$OUT/before.txt" | head -1)"; [ -n "$b" ] && grep -qxF "$b" "$OUT/after.txt"'; done
lost="$(comm -23 <(cut -d= -f1 "$OUT/before.txt" | sort -u) <(cut -d= -f1 "$OUT/after.txt" | sort -u))"
changed="$(comm -23 <(sort "$OUT/before.txt") <(sort "$OUT/after.txt") | cut -d= -f1 | grep -vxF -f <(printf '%s\n' $lost) 2>/dev/null)"
note "INFO  preferences removed by the upgrade: ${lost:-none}"
note "INFO  preferences whose value changed: $(echo $changed)"
check "no history entry removed or changed (favorites, watched, Continue Watching, resume)" \
  '! printf "%s\n%s\n" "$lost" "$changed" | grep -qE "^(favorites|watched_|continue_ids|resume_)"'
check "no setting removed or changed" '! printf "%s\n%s\n" "$lost" "$changed" | grep -qE "^(autoplay_next|subtitle)"'
adb logcat -d -b crash | grep -Fq "Process: $PKG," && { bad "app crash"; adb logcat -d -b crash | tail -30 >> "$REPORT"; } || ok "no app crash"
note ""; note "upgrade data: $PASS passed, $FAIL failed"
