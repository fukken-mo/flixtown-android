#!/usr/bin/env bash
# Used by .github/workflows/release-production.yml (job upgrade-data). Production 3.8.18 -> the new build
# with a TV's settings and history in the app's storage, written as root on a google_apis emulator (the
# Android TV images have no root shell). A signed-in session needs the live panel, so the sign-in is
# checked with the QA builds in release-final-checks.yml. Every stored setting and history entry must
# survive the upgrade. The app is opened through its regular launcher entry (phone image).
# Usage: upgrade_data_prod.sh OLD.apk NEW.apk
set -u; P=com.myflixtown.tv.native; OLD=$1; NEW=$2; F=/data/data/$P/shared_prefs/flix.xml; fail=0
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
[ "$(adb shell id -u | tr -d '\r')" = 0 ] || { echo "FAIL  data check: no root shell on this emulator image"; exit 1; }
adb uninstall $P > /dev/null 2>&1; adb install "$OLD" > /dev/null || { echo "FAIL  data check: install 3.8.18"; exit 1; }
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 8; adb shell am force-stop $P; sleep 1
uid="$(adb shell stat -c %u /data/data/$P | tr -d '\r')"
adb shell "mkdir -p /data/data/$P/shared_prefs; [ -f $F ] || echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map></map>' > $F; chown -R $uid:$uid /data/data/$P/shared_prefs; chmod 771 /data/data/$P/shared_prefs; chmod 660 $F; restorecon -R /data/data/$P/shared_prefs"
adb shell cat $F > flix.xml
python3 - flix.xml <<'PY'
import sys,re
p=sys.argv[1];s=open(p).read()
seed=[('boolean','autoplay_next','false'),('boolean','subtitles_on','true'),('string','subtitle_language','fr'),('float','subtitle_size','1.4'),
      ('string','favorites','|movie:1003||series:5002|'),('boolean','watched_movie:1005','true'),('string','continue_ids','movie:1001'),
      ('long','resume_position_movie:1001','600000'),('long','resume_duration_movie:1001','5400000'),('string','resume_extension_movie:1001','mp4')]
for t,k,v in seed:
    s=re.sub(r'\s*<[a-z]+ name="%s"[^>]*?(/>|>[^<]*</[a-z]+>)'%re.escape(k),'',s)
    s=s.replace('</map>',('    <string name="%s">%s</string>\n</map>' if t=='string' else '    <'+t+' name="%s" value="%s" />\n</map>')%(k,v))
open(p,'w').write(s)
PY
adb shell "cat > $F" < flix.xml
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 8; adb shell am force-stop $P; sleep 1
vals(){ adb shell cat $F | python3 -c 'import sys,re
for m in sorted(re.finditer(r"<(\w+) name=\"([^\"]+)\"(?: value=\"([^\"]*)\")?\s*(?:/>|>([^<]*)</\1>)",sys.stdin.read()),key=lambda m:m.group(2)):
    print("%s=%s"%(m.group(2),m.group(3) if m.group(3) is not None else m.group(4)))'; }
vals > before.txt
out="$(adb install -r "$NEW" 2>&1)"; echo "$out" | grep -q Success || { echo "FAIL  data check: upgrade install: $out"; exit 1; }
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; sleep 10; adb shell am force-stop $P; sleep 1
vals > after.txt
echo "INFO  3.8.18 stored $(wc -l < before.txt) preferences before the upgrade, $(wc -l < after.txt) after"
for k in autoplay_next subtitles_on subtitle_language subtitle_size favorites watched_movie:1005 continue_ids resume_position_movie:1001 resume_duration_movie:1001 resume_extension_movie:1001; do
  b="$(grep -m1 "^$k=" before.txt)"
  [ -n "$b" ] && grep -qxF "$b" after.txt && echo "PASS  3.8.18 -> new kept $b" || { echo "FAIL  3.8.18 -> new lost or changed $k (before: ${b:-missing}; after: $(grep -m1 "^$k=" after.txt))"; fail=1; }
done
lost="$(comm -23 <(cut -d= -f1 before.txt | sort -u) <(cut -d= -f1 after.txt | sort -u))"
echo "INFO  preferences removed by the upgrade: ${lost:-none}"
adb logcat -d -b crash | grep -Fq "Process: $P," && { echo "FAIL  app crash"; fail=1; } || echo "PASS  no app crash (data check)"
exit $fail
