#!/usr/bin/env bash
# Runs ratings.php and trending.php against a fake TMDB API and an SQLite copy of the panel tables.
#   bash panel/tests/tmdb_http_test.sh [path/to/real/api/bootstrap.php]
# With no argument a minimal stand-in bootstrap is used; pass the panel's own bootstrap.php to test
# against it (FLIXTOWN_CONFIG then points at a throwaway config, never the real one).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; mkdir -p "$WORK/api" "$WORK/private"
cp "$HERE/../tmdb/"*.php "$WORK/api/"
DB="$WORK/panel.sqlite"
php -r '$d=new PDO("sqlite:".$argv[1]);$d->exec("CREATE TABLE settings(name TEXT PRIMARY KEY,value TEXT)");$d->exec("CREATE TABLE tmdb_cache(cache_key CHAR(64) PRIMARY KEY,payload TEXT,expires_at DATETIME)");$d->exec("INSERT INTO settings VALUES(\"tmdb_key\",\"test-key\")");' "$DB"
if [ -n "${1:-}" ]; then
  cp "$1" "$WORK/api/bootstrap.php"
  printf '<?php return ["app_key"=>str_repeat("a",64),"qr_origin"=>"https://example.invalid","db_dsn"=>"sqlite:%s","db_user"=>null,"db_password"=>null];' "$DB" > "$WORK/private/config.php"
  export FLIXTOWN_CONFIG="$WORK/private/config.php"
else cp "$HERE/tmdb-fixture/bootstrap.php" "$WORK/api/bootstrap.php"; fi
FIX="$WORK/fixture"; cp -R "$HERE/tmdb-fixture" "$FIX"; rm -f "$FIX/requests.log" "$FIX/down"
php -S 127.0.0.1:8799 "$FIX/router.php" > "$WORK/tmdb.log" 2>&1 & T=$!
export FLIXTOWN_TMDB_BASE="http://127.0.0.1:8799/3/" FLIXTOWN_TEST_DB="$DB"
PHP_CLI_SERVER_WORKERS=2 php -S 127.0.0.1:8798 -t "$WORK" > "$WORK/panel.log" 2>&1 & P=$!
trap 'kill $T $P 2>/dev/null; rm -rf "$WORK"' EXIT
sleep 1
PASS=0; FAIL=0
ok(){ if [ "$2" = "$3" ]; then echo "PASS  $1"; PASS=$((PASS+1)); else echo "FAIL  $1: got $2 want $3"; FAIL=$((FAIL+1)); fi; }
q(){ php -r '$j=json_decode(stream_get_contents(STDIN),true);$v=$j;foreach(explode(".",$argv[1]) as $k){if(!is_array($v)||!array_key_exists($k,$v)){echo "missing";exit;}$v=$v[$k];}echo json_encode($v);' "$1"; }
calls(){ wc -l < "$FIX/requests.log" 2>/dev/null | tr -d ' ' || echo 0; }

BODY='{"items":[
 {"key":"movie:1","kind":"movie","tmdb":"603","title":"EN - The Matrix","year":1999},
 {"key":"movie:2","kind":"movie","tmdb":"604","title":"The Matrix","year":1999},
 {"key":"movie:3","kind":"movie","tmdb":"","title":"Halloween (2018)","year":0},
 {"key":"movie:4","kind":"movie","tmdb":"","title":"Halloween","year":0},
 {"key":"movie:5","kind":"movie","tmdb":"","title":"Tiny Film","year":2024},
 {"key":"series:6","kind":"series","tmdb":"","title":"|EN| Game of Thrones","year":2011},
 {"key":"series:7","kind":"series","tmdb":"","title":"The Office","year":0},
 {"key":"series:8","kind":"series","tmdb":"","title":"The Office","year":2005},
 {"key":"movie:9","kind":"movie","tmdb":"","title":"Halloween","year":1990}]}'
R="$(curl -s -X POST -H 'Content-Type: application/json' --data "$BODY" http://127.0.0.1:8798/api/ratings.php)"
echo "$R"
ok "TMDB id used and verified (The Matrix)"            "$(q ratings.movie:1.rating <<< "$R")" "8.2"
ok "wrong provider id ignored, search finds 1999"       "$(q ratings.movie:2.tmdb_id <<< "$R")" "603"
ok "year from \"(2018)\" picks the 2018 remake"         "$(q ratings.movie:3.rating <<< "$R")" "6.5"
ok "same title, no year: ambiguous, no rating"          "$(q ratings.movie:4 <<< "$R")" "null"
ok "2 votes of 10.0 is not a rating"                    "$(q ratings.movie:5.rating <<< "$R")" "null"
ok "TV match by title and year"                         "$(q ratings.series:6.rating <<< "$R")" "8.5"
ok "two series named The Office, no year: none"         "$(q ratings.series:7 <<< "$R")" "null"
ok "The Office (2005) is the US series"                 "$(q ratings.series:8.tmdb_id <<< "$R")" "2316"
ok "no title from that year: none (never a default)"    "$(q ratings.movie:9 <<< "$R")" "null"
before="$(calls)"
R2="$(curl -s -X POST -H 'Content-Type: application/json' --data "$BODY" http://127.0.0.1:8798/api/ratings.php)"
ok "second request served from tmdb_cache (no TMDB calls)" "$(( $(calls) - before ))" "0"
ok "cached answer identical"                            "$(q ratings <<< "$R2")" "$(q ratings <<< "$R")"
touch "$FIX/down"
R3="$(curl -s -X POST -H 'Content-Type: application/json' --data '{"items":[{"key":"movie:20","kind":"movie","tmdb":"","title":"Halloween","year":1978}]}' http://127.0.0.1:8798/api/ratings.php)"
ok "TMDB down: key left out (retry later), not null"    "$(q ratings.movie:20 <<< "$R3")" "missing"
rm -f "$FIX/down"
R4="$(curl -s -X POST -H 'Content-Type: application/json' --data '{"items":[{"key":"movie:20","kind":"movie","tmdb":"","title":"Halloween","year":1978}]}' http://127.0.0.1:8798/api/ratings.php)"
ok "TMDB back: failure was not cached"                  "$(q ratings.movie:20.rating <<< "$R4")" "7.6"
ok "more than 12 items refused" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data "{\"items\":[$(printf '{"key":"movie:1","kind":"movie","title":"x"},%.0s' {1..13} | sed 's/,$//')]}" http://127.0.0.1:8798/api/ratings.php)" "400"

T1="$(curl -s http://127.0.0.1:8798/api/trending.php)"
echo "$T1"
ok "trending keeps movies and TV only, in TMDB order"   "$(php -r '$j=json_decode($argv[1],true);echo implode(",",array_map(fn($i)=>$i["media"].":".$i["id"],$j["items"]));' "$T1")" "movie:424139,tv:1399,movie:555,movie:603,movie:77"
ok "trending rank is position, rating separate"         "$(q items.0.rank <<< "$T1")/$(q items.0.rating <<< "$T1")" "1/6.5"
ok "trending item with 2 votes has no rating"           "$(q items.4.rating <<< "$T1")" "null"
before="$(calls)"; curl -s http://127.0.0.1:8798/api/trending.php > /dev/null
ok "trending cached (no TMDB calls)"                    "$(( $(calls) - before ))" "0"
php -r '$d=new PDO("sqlite:".$argv[1]);$d->exec("UPDATE tmdb_cache SET expires_at=\"2000-01-01 00:00:00\"");' "$DB"
touch "$FIX/down"
T2="$(curl -s http://127.0.0.1:8798/api/trending.php)"
ok "TMDB down: last list served as stale"              "$(q stale <<< "$T2")/$(q items.1.id <<< "$T2")" "true/1399"
php -r '$d=new PDO("sqlite:".$argv[1]);$d->exec("UPDATE settings SET value=\"\"");' "$DB"; rm -f "$FIX/down"
ok "no TMDB key: says so, no ratings"                   "$(curl -s -X POST --data "$BODY" http://127.0.0.1:8798/api/ratings.php | php -r 'echo isset(json_decode(stream_get_contents(STDIN),true)["unavailable"])?"yes":"no";')" "yes"
echo "tmdb http: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
