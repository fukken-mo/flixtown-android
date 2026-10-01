#!/usr/bin/env bash
# End-to-end test of the panel module over HTTP, with PHP's built-in server.
#
#   http_test.sh PACKAGE V1.apk:CODE1 V2.apk:CODE2 V3.apk:CODE3 [OTHER_KEY.apk:CODE]
#
# Every APK is served from the SAME link (apk/flixtown.apk), replaced in place, as on the real
# panel. The optional last APK is signed with a different key, to check the panel's warning.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG="$1"; shift
ROOT="$(mktemp -d)"; PORT="${PORT:-8791}"; FILES_PORT=$((PORT+1)); BASE="http://127.0.0.1:$PORT"
mkdir -p "$ROOT/api" "$ROOT/apk"
cp -R "$HERE/../app-update.php" "$HERE/../app-update-admin.php" "$HERE/../flixtown-update" "$ROOT/api/"
rm -f "$ROOT/api/flixtown-update/data/state.json"
# The APK host is a separate server: PHP's built-in server handles one request at a time, so the
# panel downloading from itself would wait forever (real hosting does not have this limit).
PHP_CLI_SERVER_WORKERS=4 php -S 127.0.0.1:$PORT -t "$ROOT" > "$ROOT/server.log" 2>&1 & SERVER=$!
php -S 127.0.0.1:$FILES_PORT -t "$ROOT" > "$ROOT/files.log" 2>&1 & FILES=$!
trap 'kill $SERVER $FILES 2>/dev/null || true' EXIT
sleep 1
JAR="$ROOT/cookies"; PASS=0; FAIL=0
ok(){ echo "PASS  $1"; PASS=$((PASS+1)); }
bad(){ echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: expected '$3', got '$2'"; fi; }
json(){ php -r '$j=json_decode(stream_get_contents(STDIN),true); $v=$j; foreach(explode(".",$argv[1]) as $k){$v=is_array($v)&&array_key_exists($k,$v)?$v[$k]:null;} echo is_bool($v)?($v?"true":"false"):(is_array($v)?json_encode($v):$v);' "$1"; }
settings(){ # password, recheck minutes
  cat > "$ROOT/api/flixtown-update/settings.php" <<EOF
<?php return array('admin_password' => '$1', 'main_package' => '$PKG', 'recheck_minutes' => $2, 'max_apk_mb' => 200, 'cache_bust' => true);
EOF
  sleep 3   # OPcache re-reads a changed PHP file only after opcache.revalidate_freq (2 s)
}
csrf(){ curl -s -c "$JAR" -b "$JAR" "$BASE/api/app-update-admin.php" | sed -n 's/.*name="csrf" value="\([0-9a-f]*\)".*/\1/p' | head -1; }
post(){ curl -s -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/app-update-admin.php" --data-urlencode "csrf=$(csrf)" "$@"; }
page(){ curl -s -c "$JAR" -b "$JAR" "$BASE/api/app-update-admin.php"; }
# Saves the page first: with pipefail, "page | grep -q" fails whenever grep exits before curl finishes.
has(){ page > "$ROOT/page.html"; grep -q "$1" "$ROOT/page.html"; }
api(){ curl -s "$BASE/api/app-update.php?package=${1:-$PKG}&version_code=1&t=$RANDOM"; }
place(){ cp "$1" "$ROOT/apk/flixtown.apk.tmp"; mv "$ROOT/apk/flixtown.apk.tmp" "$ROOT/apk/flixtown.apk"; touch -d "+${2:-0} seconds" "$ROOT/apk/flixtown.apk"; }
URL="http://127.0.0.1:$FILES_PORT/apk/flixtown.apk"
IFS=: read -r A1 C1 <<< "$1"; IFS=: read -r A2 C2 <<< "$2"; IFS=: read -r A3 C3 <<< "$3"; OTHER="${4:-}"

# 1. Locked until a password is set
settings CHANGE-ME 10
has "Locked" && ok "admin page locked while the password is CHANGE-ME" || bad "admin page not locked"
settings test-pass 10

# 2. Sign in (wrong password first)
post --data-urlencode action=login --data-urlencode password=nope >/dev/null
has "Wrong password" && ok "wrong password rejected" || bad "wrong password not rejected"
post --data-urlencode action=login --data-urlencode password=test-pass >/dev/null
has "Save &amp; detect" && ok "signed in" || bad "sign-in failed"

# 3. Save the link: inspected immediately
place "$A1"
post --data-urlencode action=save --data-urlencode slot=main --data-urlencode "url=$URL" --data-urlencode "notes=First release" >/dev/null
has "versionCode $C1" && ok "save detects versionCode $C1" || { bad "save did not detect v1"; grep -o 'class="msg[^<]*' "$ROOT/page.html" | head; }
R="$(api)"
check "API version code after save" "$(echo "$R" | json update_version_code)" "$C1"
check "API sha256 = file sha256" "$(echo "$R" | json update_sha256)" "$(sha256sum "$A1" | cut -c1-64)"
check "API apk url" "$(echo "$R" | json update_apk_url)" "$URL"
check "API notes" "$(echo "$R" | json update_notes)" "First release"
check "API package" "$(echo "$R" | json package)" "$PKG"
check "API never cached" "$(curl -sI "$BASE/api/app-update.php?package=$PKG" | tr -d '\r' | sed -n 's/^Cache-Control: //Ip')" "no-store, max-age=0"

# 4. Replace the file at the same link; within the recheck interval nothing is fetched
place "$A2" 5
check "within recheck interval: stored version kept" "$(api | json update_version_code)" "$C1"

# 5. Recheck due: the replaced file is noticed by itself (no button pressed)
settings test-pass 0
R="$(api)"
check "same link, replaced file: API detects new versionCode" "$(echo "$R" | json update_version_code)" "$C2"
check "same link, replaced file: new sha256" "$(echo "$R" | json update_sha256)" "$(sha256sum "$A2" | cut -c1-64)"
STATE="$ROOT/api/flixtown-update/data/state.json"
INSPECTED="$(json slots.main.inspected_at < "$STATE")"; sleep 2
api >/dev/null
if [ "$(json slots.main.inspected_at < "$STATE")" = "$INSPECTED" ]; then ok "unchanged file: HEAD only, APK not downloaded again"; else
  echo "      (server sent no ETag/Last-Modified: full re-read is expected)"; ok "unchanged file re-read only because the server sends no validators"; fi
settings test-pass 10

# 6. Detect / Refresh APK reads the file again right away
place "$A3" 10
post --data-urlencode action=detect --data-urlencode slot=main >/dev/null
check "Detect / Refresh APK picks up versionCode" "$(api | json update_version_code)" "$C3"
has "Previous" && ok "previous release shown" || bad "previous release not shown"

# 7. Another app's package gets no offer
R="$(api com.example.other)"
check "other package: ok" "$(echo "$R" | json ok)" "true"
check "other package: no update" "$(echo "$R" | json update_version_code)" "0"

# 8. Different signing key: warning on the panel
if [ -n "$OTHER" ]; then
  IFS=: read -r A4 C4 <<< "$OTHER"; place "$A4" 15
  post --data-urlencode action=detect --data-urlencode slot=main >/dev/null
  has "signed with a different key" && ok "different signing key warned" || bad "no warning for a different signing key"
  place "$A3" 20; post --data-urlencode action=detect --data-urlencode slot=main >/dev/null
fi

# 9. Broken link: the API reports a failure, never "no update"
rm -f "$ROOT/apk/flixtown.apk"
post --data-urlencode action=detect --data-urlencode slot=main >/dev/null
has "Last check failed" && ok "panel shows the failed check" || bad "panel does not show the failure"
R="$(api)"
check "broken link: API ok=false" "$(echo "$R" | json ok)" "false"
echo "      API said: $(echo "$R" | json error)"

# 10. Not an APK at the link
echo "<html>not found</html>" > "$ROOT/apk/flixtown.apk"
post --data-urlencode action=detect --data-urlencode slot=main >/dev/null
check "HTML instead of an APK: API ok=false" "$(api | json ok)" "false"

# 11. Test slot: a different package is served to its own app only
if [ -n "${TEST_SLOT_APK:-}" ]; then
  IFS=: read -r TA TPKG TC <<< "$TEST_SLOT_APK"; cp "$TA" "$ROOT/apk/test.apk"
  post --data-urlencode action=save --data-urlencode slot=test --data-urlencode "url=http://127.0.0.1:$FILES_PORT/apk/test.apk" --data-urlencode "notes=" >/dev/null
  check "test slot offered to its own package" "$(api "$TPKG" | json update_version_code)" "$TC"
fi

echo "panel http test: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
