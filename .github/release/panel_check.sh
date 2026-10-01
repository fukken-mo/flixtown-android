#!/usr/bin/env bash
# Publishes a production APK in a local copy of the panel's update module (the same PHP the live
# panel runs) and checks what TVs would be told:
#   panel_check.sh APK EXPECTED_PACKAGE EXPECTED_VERSION_CODE EXPECTED_CERT_SHA256
set -u
APK="$1"; PKG="$2"; CODE="$3"; CERT="$4"
HERE="$(cd "$(dirname "$0")/../.." && pwd)/panel"
ROOT="$(mktemp -d)"; mkdir -p "$ROOT/api" "$ROOT/apk"
cp -R "$HERE/app-update.php" "$HERE/app-update-admin.php" "$HERE/flixtown-update" "$ROOT/api/"
rm -f "$ROOT/api/flixtown-update/data/state.json"
cat > "$ROOT/api/flixtown-update/settings.php" <<PHP
<?php return array('admin_password' => 'release-check', 'main_package' => 'com.myflixtown.tv.native', 'recheck_minutes' => 10, 'max_apk_mb' => 200, 'cache_bust' => true);
PHP
cp "$APK" "$ROOT/apk/flixtown.apk"
PHP_CLI_SERVER_WORKERS=4 php -S 127.0.0.1:8795 -t "$ROOT" > "$ROOT/server.log" 2>&1 & S=$!
php -S 127.0.0.1:8796 -t "$ROOT" > "$ROOT/files.log" 2>&1 & F=$!
trap 'kill $S $F 2>/dev/null || true' EXIT
sleep 2
BASE="http://127.0.0.1:8795"; JAR="$ROOT/cookies"; FAIL=0
ok(){ echo "PASS  $1"; }; bad(){ echo "FAIL  $1"; FAIL=1; }
csrf(){ curl -s -c "$JAR" -b "$JAR" "$BASE/api/app-update-admin.php" | sed -n 's/.*name="csrf" value="\([0-9a-f]*\)".*/\1/p' | head -1; }
post(){ curl -s -c "$JAR" -b "$JAR" -o /dev/null "$BASE/api/app-update-admin.php" --data-urlencode "csrf=$(csrf)" "$@"; }
json(){ php -r '$j=json_decode(stream_get_contents(STDIN),true);$v=$j;foreach(explode(".",$argv[1]) as $k){$v=is_array($v)&&array_key_exists($k,$v)?$v[$k]:null;}echo is_bool($v)?($v?"true":"false"):$v;' "$1"; }
post --data-urlencode action=login --data-urlencode password=release-check
post --data-urlencode action=save --data-urlencode slot=main --data-urlencode "url=http://127.0.0.1:8796/apk/flixtown.apk" --data-urlencode "notes=Flix Town"
R="$(curl -s "$BASE/api/app-update.php?package=$PKG&version_code=$((CODE-1))")"; echo "API ($PKG): $R"
[ "$(echo "$R" | json ok)" = "true" ] && ok "panel reads the APK" || bad "panel could not read the APK"
[ "$(echo "$R" | json package)" = "$PKG" ] && ok "panel package = $PKG" || bad "panel package $(echo "$R" | json package)"
[ "$(echo "$R" | json update_version_code)" = "$CODE" ] && ok "panel versionCode = $CODE (offered to an installed $((CODE-1)))" || bad "panel versionCode $(echo "$R" | json update_version_code)"
[ "$(echo "$R" | json update_signer_sha256)" = "$CERT" ] && ok "panel signing certificate = permanent release key" || bad "panel certificate $(echo "$R" | json update_signer_sha256)"
[ "$(echo "$R" | json update_sha256)" = "$(sha256sum "$APK" | cut -c1-64)" ] && ok "panel checksum = file checksum" || bad "panel checksum differs"
P="$(curl -s "$BASE/api/app-update.php?package=$PKG.preview&version_code=1")"; echo "API ($PKG.preview): $P"
[ "$(echo "$P" | json update_version_code)" = "0" ] && ok "the Preview app is never offered the production APK" || bad "Preview app offered $(echo "$P" | json update_version_code)"
exit $FAIL
