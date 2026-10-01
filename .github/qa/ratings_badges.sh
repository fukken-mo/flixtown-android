#!/usr/bin/env bash
# Ratings, Trending and "new" badges on a 1080p Android TV emulator (QA build, offline demo server).
# The demo server mixes the cases (see DemoData): provider ratings of "10" that must never show,
# TMDB ratings with some titles unrated, trending titles on and off the server, an older series with
# a new episode, a recently added series, an unchanged series with a touched last_modified, a series
# without episode times that gets a new episode, and a series that appears after the first import.
# Results: qa/ratings-badges/screens/*.png and qa/ratings-badges/report.txt
set -u
PKG=com.myflixtown.tv.native.qa
APK=app/build/outputs/apk/qa/app-qa.apk
OUT=qa/ratings-badges; SHOTS=$OUT/screens
rm -rf "$OUT" && mkdir -p "$SHOTS"
REPORT="$OUT/report.txt"; : > "$REPORT"
note(){ echo "$*" | tee -a "$REPORT"; }
PASS=0; FAIL=0
ok(){ note "PASS  $1"; PASS=$((PASS+1)); }
bad(){ note "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

adb install -r "$APK" > /dev/null || exit 1
adb shell settings put system screen_off_timeout 1800000 || true
adb logcat -c
shot(){ sleep "${2:-0.8}"; adb shell screencap -p "/sdcard/$1.png"; adb pull "/sdcard/$1.png" "$SHOTS/$1.png" > /dev/null; echo "shot $1"; }
key(){ for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done; }
LAUNCHER="-a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000"
launch(){ adb shell am start -S -W $LAUNCHER -n "$PKG/com.flixtown.tv.LoginActivity" "$@" > /dev/null; }
dump(){ adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml > "$OUT/ui.xml"; cp "$OUT/ui.xml" "$OUT/ui-$1.xml"; }
has(){ grep -q -- "$1" "$OUT/ui.xml"; }
# Texts shown inside the card whose content description is the given title ("" when not on screen).
card(){ python3 - "$OUT/ui.xml" "$1" <<'EOF'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    if n.get('content-desc') == sys.argv[2]:
        print('|'.join(t for t in (c.get('text') for c in n.iter('node')) if t)); break
EOF
}
# Demo titles (same formula as DemoData.title).
title(){ python3 - "$1" "$2" <<'EOF'
import sys
A=["Silent","Crimson","Last","Midnight","Broken","Golden","Hidden","Northern","Electric","Lost","Wild","Paper","Iron","Distant","Hollow"]
B=["Harbor","Frontier","Signal","Garden","Kingdom","Horizon","Letters","Station","Empire","Tide","Orchard","Protocol","Summer","Voyage","Circuit"]
i,s=int(sys.argv[1]),int(sys.argv[2]); t=A[(i*7+s)%15]+" "+B[(i*11+s*3)%15]
print("The "+t+" and the Long Road Home" if i%9==4 else "The "+t if i%5==0 else t)
EOF
}
S0="$(title 0 5)"; S1="$(title 1 5)"; S2="$(title 2 5)"; S3="$(title 3 5)"; S9="The Night Shift Files"
M3="$(title 3 1)"; M10="$(title 10 1)"; M20="$(title 20 1)"

# ---------- 1. First catalog import ----------
launch --ez demo_reset true
sleep 26;                                    shot 01-home-hero 0.5; dump hero
check "hero shows a real trending title (#n TRENDING; it rotates)" 'grep -q "#[0-9]* TRENDING" "$OUT/ui.xml"'
check "no provider '★ 10.0' anywhere on Home" '! has "★ 10.0"'
# Episode checks run in the background a few seconds after start-up; Home updates at the hero.
sleep 25;                                    shot 02-home-after-episode-checks 0.5
key DPAD_DOWN;                               shot 03-trending-row 2.5; dump trending
check "Trending shows TMDB trending matched by TMDB ID ($M3)" '[ -n "$(card "$M3")" ]'
check "Trending: title + year within one ($M10)" '[ -n "$(card "$M10")" ]'
check "trending rank #1 is not shown as a rating" '! card "$M3" | grep -q "★ 1\.0"'
check "Trending cards show TMDB ratings ★ x.x" 'grep -q "★ [0-9]\.[0-9]" "$OUT/ui.xml"'
check "no provider '★ 10.0' in Trending" '! has "★ 10.0"'
key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT; shot 03b-trending-row-right 2; dump trending2
check "trending title with too few TMDB votes shows no rating ($M20)" '[ -n "$(card "$M20")" ] && ! card "$M20" | grep -q "★"'
key DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT; sleep 0.5
key DPAD_DOWN;                               shot 04-latest-movies 2.5; dump movies
check "Latest Movies: NEW for movies added this week" 'has "text=\"NEW\""'
check "Latest Movies: some titles without a TMDB rating show none" 'python3 - "$OUT/ui.xml" <<EOF
import sys, xml.etree.ElementTree as ET
r=ET.parse(sys.argv[1]).getroot(); metas=[n.get("text") for n in r.iter("node") if n.get("text","")[:2] in ("19","20") and len(n.get("text"))>=4]
sys.exit(0 if any("★" in m for m in metas) and any("★" not in m for m in metas) else 1)
EOF'
key DPAD_DOWN;                               shot 05-latest-tv-first-import 2.5; dump tv1
note "  $S0: $(card "$S0")"; note "  $S1: $(card "$S1")"; note "  $S2: $(card "$S2")"; note "  $S3: $(card "$S3")"
check "older series with a new episode: NEW EPISODES ($S0)" 'card "$S0" | grep -q "NEW EPISODES"'
check "recently added series: NEW SERIES ($S1)" 'card "$S1" | grep -q "NEW SERIES"'
check "unchanged series with touched last_modified: no badge ($S2)" '! card "$S2" | grep -q "NEW"'
check "series without episode times, first check: no badge ($S3)" '! card "$S3" | grep -q "NEW"'
check "first import: not every poster is NEW" '[ "$(grep -o "text=\"NEW [A-Z]*\"" "$OUT/ui.xml" | wc -l)" -le 2 ]'
check "badged series lead Latest TV Shows" 'python3 - "$OUT/ui.xml" "$S0" "$S1" <<EOF
import sys, xml.etree.ElementTree as ET
menu={"Search","Home","Movies","TV Shows","My List","Settings"}
r=ET.parse(sys.argv[1]).getroot(); order=[n.get("content-desc") for n in r.iter("node") if n.get("content-desc") and n.get("content-desc") not in menu]
sys.exit(0 if sys.argv[2] in order[:4] and sys.argv[3] in order[:4] else 1)
EOF'
check "no provider '★ 10.0' on TV cards" '! has "★ 10.0"'

# ---------- 2. Details page rating ----------
key DPAD_UP DPAD_RIGHT;                      sleep 0.6   # Latest Movies, second card (the first has no TMDB rating)
key DPAD_CENTER;                             shot 06-movie-details 3; dump details
check "details page shows the TMDB rating ★ x.x" 'grep -q "★ [0-9]\.[0-9]" "$OUT/ui.xml"'
check "details page never shows the provider 10" '! has "★ 10.0"'
key BACK;                                    sleep 1.5

# ---------- 3. Browse cards ----------
key DPAD_UP DPAD_UP DPAD_UP;                 sleep 1
key DPAD_LEFT DPAD_DOWN DPAD_CENTER;         shot 07-movies-grid 4; dump grid
check "browse cards show year · ★ rating" 'grep -q "text=\"[0-9]\{4\}  ·  ★ [0-9]\.[0-9]\"" "$OUT/ui.xml"'
check "browse cards without a rating show only the year" 'grep -q "text=\"[0-9]\{4\}\"" "$OUT/ui.xml"'

# ---------- 4. A later catalog: new series appears, series without times gains an episode ----------
key DPAD_LEFT DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 1.5    # menu on Movies → Settings
dump settings
python3 - "$OUT/ui.xml" <<'EOF' > "$OUT/refresh-xy"
import sys, re, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    if re.search('Check for new movies and shows', n.get('text','')):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((x1+x2)//2,(y1+y2)//2); break
EOF
if [ -s "$OUT/refresh-xy" ]; then adb shell input tap $(cat "$OUT/refresh-xy"); sleep 0.6; else note "  (refresh row not found by text)"; fi
key DPAD_CENTER                               # a tap only focuses the row on a TV; the remote's OK starts the check
sleep 5;                                     shot 08-refreshed 0.3
sleep 22                                      # episode checks for the changed series
key DPAD_LEFT DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_CENTER; sleep 2.5   # menu on Settings → Home
key DPAD_DOWN DPAD_DOWN DPAD_DOWN;           shot 09-latest-tv-after-new-catalog 2; dump tv2
note "  $S9: $(card "$S9")"; note "  $S3: $(card "$S3")"
check "series that appeared after the first import: NEW SERIES" 'card "$S9" | grep -q "NEW SERIES"'
check "series without episode times, new episode ID: NEW EPISODES ($S3)" 'card "$S3" | grep -q "NEW EPISODES"'
check "earlier badges kept ($S0 NEW EPISODES)" 'card "$S0" | grep -q "NEW EPISODES"'
check "unchanged series still without badge ($S2)" '! card "$S2" | grep -q "NEW"'

# ---------- 5. Restart: saved ratings and badges, a plain refresh adds nothing ----------
launch;                                      sleep 8; shot 10-relaunch-home 0.5
key DPAD_DOWN DPAD_DOWN DPAD_DOWN;           shot 11-latest-tv-relaunch 0.8; dump tv3
check "after restart: badges come from saved state ($S0)" 'card "$S0" | grep -q "NEW EPISODES"'
check "after restart: unchanged series still without badge ($S2)" '! card "$S2" | grep -q "NEW"'
check "after restart: ratings shown from the saved cache" 'grep -q "★ [0-9]\.[0-9]" "$OUT/ui.xml"'

adb logcat -d | grep -E "FlixTown|AndroidRuntime|FATAL" | tail -n 200 > "$OUT/app-log.txt" || true
check "no crash" '! grep -q "FATAL EXCEPTION" "$OUT/app-log.txt"'
note "ratings & badges: $PASS passed, $FAIL failed"
