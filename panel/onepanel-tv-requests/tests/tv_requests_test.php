<?php
/*
 * End-to-end test of the customer Requests API: the real api/tv-requests.php and
 * includes/tv_requests.php, plus OnePanel's own includes/seerr.php (fixture copy, unmodified),
 * served by `php -S` with several workers, against tests/fake_bootstrap.php (a real MySQL database,
 * fake Flix Town adapter and catalog, fake Seerr following the verified Seerr permission and
 * duplicate rules). Never contacts a real Seerr or Flix Town server.
 *
 * Needs a MySQL 8 (or MariaDB) server and an account that may create databases:
 *   TVQ_MYSQL_HOST=127.0.0.1 TVQ_MYSQL_PORT=3306 TVQ_MYSQL_USER=root TVQ_MYSQL_PASS=... \
 *   php panel/onepanel-tv-requests/tests/tv_requests_test.php
 * A fresh database tvq_test_<pid> is created from sql/migrations/2026-10-14-tv-requests.sql as
 * shipped, and dropped at the end when every check passed.
 */
$root = dirname(__DIR__);
$work = sys_get_temp_dir() . '/tvq-test-' . getmypid();
@mkdir("$work/api", 0777, true); @mkdir("$work/includes", 0777, true);
copy("$root/api/tv-requests.php", "$work/api/tv-requests.php");
copy("$root/includes/tv_requests.php", "$work/includes/tv_requests.php");
copy(__DIR__ . '/fixtures/seerr.php', "$work/includes/seerr.php");
copy(__DIR__ . '/fake_bootstrap.php', "$work/includes/bootstrap.php");
$state = "$work/state.json"; $storage = "$work/storage"; $log = "$work/server.log";
$my = ['host' => getenv('TVQ_MYSQL_HOST') ?: '127.0.0.1', 'port' => getenv('TVQ_MYSQL_PORT') ?: '3306', 'user' => getenv('TVQ_MYSQL_USER') ?: 'root', 'pass' => (string)getenv('TVQ_MYSQL_PASS')];
$dbName = 'tvq_test_' . getmypid();
$env = ['TVQ_STATE' => $state, 'TVQ_STORAGE' => $storage, 'PHP_CLI_SERVER_WORKERS' => '6', 'TVQ_MYSQL_HOST' => $my['host'], 'TVQ_MYSQL_PORT' => $my['port'],
  'TVQ_MYSQL_USER' => $my['user'], 'TVQ_MYSQL_PASS' => $my['pass'], 'TVQ_MYSQL_DB' => $dbName];
foreach ($env as $k => $v) putenv("$k=$v");

$opts = [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC, PDO::ATTR_EMULATE_PREPARES => false];
$admin = new PDO("mysql:host={$my['host']};port={$my['port']};charset=utf8mb4", $my['user'], $my['pass'], $opts);
$admin->exec("DROP DATABASE IF EXISTS `$dbName`"); $admin->exec("CREATE DATABASE `$dbName` CHARACTER SET utf8mb4");
$pdo = new PDO("mysql:host={$my['host']};port={$my['port']};dbname=$dbName;charset=utf8mb4", $my['user'], $my['pass'], $opts);
// The migration exactly as shipped, statement by statement (as the mysql client would run it).
$migration = file_get_contents("$root/sql/migrations/2026-10-14-tv-requests.sql");
foreach (array_filter(array_map('trim', explode(';', preg_replace('/^--[^\n]*$/m', '', $migration)))) as $stmt) $pdo->exec($stmt);
$migrationTwice = true; try { foreach (array_filter(array_map('trim', explode(';', preg_replace('/^--[^\n]*$/m', '', $migration)))) as $stmt) $pdo->exec($stmt); } catch (Throwable $e) { $migrationTwice = false; }

$future = gmdate('Y-m-d\TH:i:s', time() + 86400 * 30); $past = gmdate('Y-m-d\TH:i:s', time() - 86400);
$seasons = function ($n) { $o = [['seasonNumber' => 0, 'name' => 'Specials', 'episodeCount' => 3]];
  for ($i = 1; $i <= $n; $i++) $o[] = ['seasonNumber' => $i, 'name' => "Season $i", 'episodeCount' => 8]; return $o; };
file_put_contents($state, json_encode([
  'flix' => [
    'alice' => ['username' => 'alice', 'password' => 'alice-pass', 'expires_at' => $future, 'enabled' => true],
    'bob' => ['username' => 'bob', 'password' => 'bob-pass', 'expires_at' => null, 'enabled' => true],
    'carol' => ['username' => 'carol', 'password' => 'carol-pass', 'expires_at' => $future, 'enabled' => true],
    'dave' => ['username' => 'dave', 'password' => 'dave-pass', 'expires_at' => $future, 'enabled' => true],
    'erin' => ['username' => 'erin', 'password' => 'erin-pass', 'expires_at' => $future, 'enabled' => true],
    'gina' => ['username' => 'gina', 'password' => 'gina-pass', 'expires_at' => $future, 'enabled' => true],
    'hank' => ['username' => 'hank', 'password' => 'hank-pass', 'expires_at' => $future, 'enabled' => true],
    'old' => ['username' => 'old', 'password' => 'old-pass', 'expires_at' => $past, 'enabled' => true],
  ],
  'users' => ['1' => ['permissions' => 2], '7' => ['permissions' => 32], '8' => ['permissions' => 32 | 128], '9' => ['permissions' => 2], '10' => ['permissions' => 64],
    '11' => ['permissions' => 32 | 256], '12' => ['permissions' => 32 | 512], '13' => ['permissions' => 32 | 16]],
  'catalog' => [
    ['mediaType' => 'movie', 'id' => 101, 'title' => 'Alpha Movie', 'releaseDate' => '2021-05-01', 'posterPath' => '/alpha.jpg', 'backdropPath' => '/alpha-b.jpg', 'overview_full' => '<b>Alpha</b> synopsis'],
    ['mediaType' => 'movie', 'id' => 102, 'title' => 'Alpha Available', 'releaseDate' => '2019-01-01', 'posterPath' => '/x.jpg'],
    ['mediaType' => 'movie', 'id' => 103, 'title' => 'Alpha Pending', 'releaseDate' => '2020-01-01', 'posterPath' => '../../etc/passwd'],
    ['mediaType' => 'movie', 'id' => 104, 'title' => 'Alpha Declined', 'releaseDate' => '2018-01-01'],
    ['mediaType' => 'movie', 'id' => 105, 'title' => 'Alpha Approved', 'releaseDate' => '2017-01-01'],
    ['mediaType' => 'movie', 'id' => 106, 'title' => 'Race Movie', 'releaseDate' => '2022-01-01'],
    ['mediaType' => 'tv', 'id' => 201, 'name' => 'Alpha Show', 'firstAirDate' => '2015-02-03', 'posterPath' => '/show.jpg', 'seasons' => $seasons(4)],
    ['mediaType' => 'tv', 'id' => 203, 'name' => 'Race Show', 'firstAirDate' => '2012-01-01', 'seasons' => $seasons(5)],
    ['mediaType' => 'movie', 'id' => 107, 'title' => 'Flix Movie', 'releaseDate' => '2020-03-01'],
    ['mediaType' => 'movie', 'id' => 108, 'title' => 'Mistagged Movie', 'releaseDate' => '2022-03-01'],
    ['mediaType' => 'movie', 'id' => 109, 'title' => 'Closed Movie', 'releaseDate' => '2019-03-01'],
    ['mediaType' => 'movie', 'id' => 110, 'title' => 'Café 🎬 Night', 'releaseDate' => '2018-03-01'],
    ['mediaType' => 'movie', 'id' => 111, 'title' => 'Guarded Movie', 'releaseDate' => '2017-03-01'],
    ['mediaType' => 'movie', 'id' => 112, 'title' => 'Two Servers Movie', 'releaseDate' => '2016-03-01'],
    ['mediaType' => 'movie', 'id' => 113, 'title' => 'Locked Movie', 'releaseDate' => '2015-03-01'],
    ['mediaType' => 'tv', 'id' => 204, 'name' => 'Flix Show', 'firstAirDate' => '2016-05-01', 'seasons' => $seasons(3)],
    ['mediaType' => 'tv', 'id' => 205, 'name' => 'Flat Show', 'firstAirDate' => '2011-05-01', 'seasons' => $seasons(2)],
    ['mediaType' => 'tv', 'id' => 202, 'name' => 'Complete Show', 'firstAirDate' => '2010-01-01', 'seasons' => $seasons(2)],
  ],
  // The customers' Flix Town catalog (player_api.php): what the TV app would list.
  'flix_catalog' => [
    'vod' => [
      ['stream_id' => 5001, 'name' => 'Flix Movie (2020)', 'tmdb' => '107', 'year' => '2020'],
      ['stream_id' => 5002, 'name' => 'Some Old Film', 'tmdb' => '108', 'year' => '1950'],   // wrong TMDB tag
      ['stream_id' => 5003, 'name' => 'Untagged Film', 'tmdb' => ''],
    ],
    'series' => [
      ['series_id' => 9001, 'name' => 'Flix Show', 'tmdb' => '204', 'releaseDate' => '2016-05-01'],
      ['series_id' => 9002, 'name' => 'Flat Show (2011)', 'tmdb_id' => '205'],
    ],
    'info' => [
      '9001' => ['episodes' => [
        '1' => array_map(fn($e) => ['id' => "1$e", 'episode_num' => $e, 'season' => 1], range(1, 8)),   // whole season
        '2' => array_map(fn($e) => ['id' => "2$e", 'episode_num' => $e, 'season' => 2], range(1, 3)),   // 3 of 8
      ]],
      '9002' => ['episodes' => array_map(fn($e) => ['id' => "9$e", 'episode_num' => $e, 'season' => 1], range(1, 8))],   // flat list
    ],
  ],
  'media' => [
    'movie:102' => ['status' => 5], 'movie:103' => ['status' => 2], 'movie:104' => ['status' => 1], 'movie:105' => ['status' => 3],
    'tv:201' => ['status' => 4, 'seasons' => ['1' => 5, '2' => 2]], 'tv:202' => ['status' => 5, 'seasons' => ['1' => 5, '2' => 5]],
  ],
  'requests' => [
    ['id' => 50, 'key' => 'movie:103', 'status' => 1, 'user' => 3, 'seasons' => []],
    ['id' => 51, 'key' => 'movie:104', 'status' => 3, 'user' => 3, 'seasons' => []],
    ['id' => 52, 'key' => 'movie:105', 'status' => 2, 'user' => 3, 'seasons' => []],
    ['id' => 53, 'key' => 'tv:201', 'status' => 1, 'user' => 3, 'seasons' => [2]],
  ],
]));

$port = 18500 + getmypid() % 1000;
function serve($extraEnv = []) {
  global $work, $port, $env, $log;
  return proc_open([PHP_BINARY, '-S', "127.0.0.1:$port", '-t', $work], [1 => ['file', '/dev/null', 'w'], 2 => ['file', $log, 'a']], $p, null, $env + $extraEnv);
}
$server = serve();
for ($i = 0; $i < 50 && !@fsockopen('127.0.0.1', $port); $i++) usleep(100000);

$pass = 0; $fail = 0; $bodies = '';
function check(string $name, bool $ok, string $extra = ''): void { global $pass, $fail, $last; if (!$ok && $extra === '') $extra = substr((string)$last, 0, 300); echo ($ok ? 'PASS  ' : 'FAIL  ') . $name . ($ok || $extra === '' ? '' : "  [$extra]") . "\n"; $ok ? $pass++ : $fail++; }
function call($body, string $method = 'POST'): array {
  global $port, $bodies;
  $ch = curl_init("http://127.0.0.1:$port/api/tv-requests.php");
  curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CUSTOMREQUEST => $method, CURLOPT_HTTPHEADER => ['Content-Type: application/json'], CURLOPT_TIMEOUT => 30]);
  if ($method === 'POST') curl_setopt($ch, CURLOPT_POSTFIELDS, is_string($body) ? $body : json_encode($body));
  $raw = curl_exec($ch); $code = curl_getinfo($ch, CURLINFO_RESPONSE_CODE); curl_close($ch);
  $bodies .= $raw . "\n"; $GLOBALS['last'] = $code . ' ' . $raw;
  return ['code' => $code, 'raw' => (string)$raw, 'j' => json_decode((string)$raw, true) ?: []];
}
function st(): array { return json_decode(file_get_contents(getenv('TVQ_STATE')), true); }
function setst(callable $f): void { $h = fopen(getenv('TVQ_STATE'), 'c+'); flock($h, LOCK_EX); $s = json_decode(stream_get_contents($h), true);
  $s = $f($s); ftruncate($h, 0); rewind($h); fwrite($h, json_encode($s)); flock($h, LOCK_UN); fclose($h); }
function login($u, $p) { return call(['action' => 'session', 'username' => $u, 'password' => $p])['j']['session'] ?? ''; }
$unavailable = 'Requests are temporarily unavailable.';

// ---------------- MySQL schema (as created by the shipped migration) ----------------
$serverVersion = $pdo->query('SELECT VERSION()')->fetchColumn();
try { $iso = $pdo->query('SELECT @@transaction_isolation')->fetchColumn(); } catch (PDOException $e) { $iso = $pdo->query('SELECT @@tx_isolation')->fetchColumn(); }   // MariaDB name
echo "Database server: $serverVersion, isolation $iso\n";
$cols = []; foreach ($pdo->query("SELECT TABLE_NAME t, COLUMN_NAME c, COLUMN_TYPE ty, IS_NULLABLE n FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$dbName'") as $r) $cols[$r['t'] . '.' . $r['c']] = preg_replace('/^(\w*int)\(\d+\)/', '$1', $r['ty']) . ($r['n'] === 'YES' ? ' null' : '');   // MariaDB adds int display widths
$tables = []; foreach ($pdo->query("SELECT TABLE_NAME t, ENGINE e, TABLE_COLLATION c FROM information_schema.TABLES WHERE TABLE_SCHEMA='$dbName'") as $r) $tables[$r['t']] = $r['e'] . ' ' . $r['c'];
$idx = []; foreach ($pdo->query("SELECT TABLE_NAME t, INDEX_NAME i, NON_UNIQUE nu, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cols FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$dbName' GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE") as $r) $idx[$r['t'] . '.' . $r['i']] = ($r['nu'] ? '' : 'unique ') . $r['cols'];
check('schema: migration runs on MySQL as shipped, and again without error (IF NOT EXISTS)', $migrationTwice && count($tables) === 2);
check('schema: both tables InnoDB, utf8mb4', ($tables['tv_media_requests'] ?? '') !== '' && !array_filter($tables, fn($v) => !preg_match('/^InnoDB utf8mb4_/', $v)), json_encode($tables));
check('schema: ownership columns and types', ($cols['tv_media_requests.username'] ?? '') === 'varchar(120)' && ($cols['tv_media_requests.seerr_request_id'] ?? '') === 'int unsigned'
  && ($cols['tv_media_requests.tmdb_id'] ?? '') === 'int unsigned' && ($cols['tv_media_requests.seasons'] ?? '') === 'varchar(255)' && ($cols['tv_media_requests.last_status'] ?? '') === 'tinyint unsigned', json_encode($cols));
check('schema: one owner per Seerr request (unique), lookups indexed', ($idx['tv_media_requests.uq_tv_media_requests_seerr'] ?? '') === 'unique seerr_request_id'
  && ($idx['tv_media_requests.idx_tv_media_requests_user'] ?? '') === 'username,id' && ($idx['tv_media_requests.idx_tv_media_requests_media'] ?? '') === 'media_type,tmdb_id,last_status'
  && ($idx['tv_request_rate.PRIMARY'] ?? '') === 'unique k', json_encode($idx));

// ---------------- authentication ----------------
check('GET refused', call([], 'GET')['code'] === 405);
check('non-JSON refused', call('query=x')['j']['error'] === 'bad_request');
$r = call(['action' => 'session', 'username' => 'alice', 'password' => 'wrong']);
check('wrong password: 401, no session', $r['code'] === 401 && !isset($r['j']['session']));
check('unknown customer: same 401', call(['action' => 'session', 'username' => 'nobody', 'password' => 'x'])['code'] === 401);
check('expired customer cannot use Requests (403)', call(['action' => 'session', 'username' => 'old', 'password' => 'old-pass'])['j']['error'] === 'inactive');
$A = login('alice', 'alice-pass'); $B = login('bob', 'bob-pass');
check('customer signs in with the Flix Town account (Never Expire too)', $A !== '' && $B !== '');
check('search without a session: 401', call(['action' => 'search', 'query' => 'alpha', 'page' => 1])['code'] === 401);
check('forged session: 401', call(['action' => 'search', 'session' => substr($A, 0, -3) . 'abc', 'query' => 'alpha', 'page' => 1])['code'] === 401);
check('a username in the body is ignored (identity comes from the session)', call(['action' => 'history', 'session' => $A, 'page' => 1, 'username' => 'bob'])['code'] === 200);

// ---------------- search ----------------
$r = call(['action' => 'search', 'session' => $A, 'query' => 'alpha', 'page' => 1]); $res = $r['j']['results'] ?? [];
$by = []; foreach ($res as $x) $by[$x['type'] . ':' . $x['id']] = $x;
check('search: movies and series only (person removed)', count($res) === 6 && !array_filter($res, fn($x) => !in_array($x['type'], ['movie', 'tv'], true)));
check('search: title, year, type, bounded TMDB poster URL', ($by['movie:101']['title'] ?? '') === 'Alpha Movie' && $by['movie:101']['year'] === '2021' && $by['movie:101']['poster'] === 'https://image.tmdb.org/t/p/w342/alpha.jpg');
check('search: unsafe poster path dropped', ($by['movie:103']['poster'] ?? 'x') === '');
check('search: available / pending / approved / partial / requestable', $by['movie:102']['state'] === 'available' && $by['movie:103']['state'] === 'pending'
  && $by['movie:105']['state'] === 'approved' && $by['tv:201']['state'] === 'partial' && $by['movie:101']['state'] === 'requestable');
check('search: pages capped (1..10)', ($r['j']['pages'] ?? 0) === 3 && ($r['j']['page'] ?? 0) === 1);
check('search: empty result is an empty list', (call(['action' => 'search', 'session' => $A, 'query' => 'zzzz', 'page' => 1])['j']['results'] ?? null) === []);
check('search: 1 character refused', call(['action' => 'search', 'session' => $A, 'query' => 'a', 'page' => 1])['j']['error'] === 'query');
check('search: 81 characters refused', call(['action' => 'search', 'session' => $A, 'query' => str_repeat('a', 81), 'page' => 1])['j']['error'] === 'query');
check('search: page 0, 11 and "2" refused', call(['action' => 'search', 'session' => $A, 'query' => 'alpha', 'page' => 0])['j']['error'] === 'page'
  && call(['action' => 'search', 'session' => $A, 'query' => 'alpha', 'page' => 11])['j']['error'] === 'page' && call(['action' => 'search', 'session' => $A, 'query' => 'alpha', 'page' => '2'])['j']['error'] === 'page');
check('search: read as administrator, never as the requester user', !array_filter(st()['calls'], fn($c) => $c['p'] === '/search' && $c['user'] !== 0));

// ---------------- details ----------------
$d = call(['action' => 'details', 'session' => $A, 'type' => 'movie', 'id' => 101])['j']['details'] ?? [];
check('details: artwork, plain-text synopsis, requestable', $d['backdrop'] === 'https://image.tmdb.org/t/p/w780/alpha-b.jpg' && $d['overview'] === 'Alpha synopsis' && $d['state'] === 'requestable' && $d['can_request'] === true);
$states = []; foreach ([102, 103, 104, 105] as $id) $states[$id] = call(['action' => 'details', 'session' => $A, 'type' => 'movie', 'id' => $id])['j']['details'];
check('details: available, pending, declined, approved, none requestable', $states[102]['state'] === 'available' && $states[103]['state'] === 'pending'
  && $states[104]['state'] === 'declined' && $states[105]['state'] === 'approved' && !array_filter($states, fn($x) => $x['can_request']));
$t = call(['action' => 'details', 'session' => $A, 'type' => 'tv', 'id' => 201]); $tv = $t['j']['details'] ?? [];
$ss = []; foreach ($tv['seasons'] ?? [] as $s) $ss[$s['number']] = $s['state'];
check('TV: specials (season 0) left out; S1 available, S2 pending, S3/S4 requestable', $ss === [1 => 'available', 2 => 'pending', 3 => 'requestable', 4 => 'requestable']);
check('TV: some seasons present = partially available, never fully available', $tv['state'] === 'partial' && $tv['can_request'] === true);
$c = call(['action' => 'details', 'session' => $A, 'type' => 'tv', 'id' => 202])['j']['details'];
check('TV: every season present = available, nothing to request', $c['state'] === 'available' && $c['can_request'] === false);
check('details: no Seerr users, emails or tokens in the answer', !preg_match('/secret-user|TOKEN-XYZ|requestedBy|email|plexToken/i', $t['raw']));
check('details: invalid type / id refused', call(['action' => 'details', 'session' => $A, 'type' => 'person', 'id' => 5])['j']['error'] === 'type'
  && call(['action' => 'details', 'session' => $A, 'type' => 'movie', 'id' => -4])['j']['error'] === 'id' && call(['action' => 'details', 'session' => $A, 'type' => 'movie', 'id' => '101'])['j']['error'] === 'id');

// ---------------- requests ----------------
$r = call(['action' => 'request', 'session' => $A, 'type' => 'movie', 'id' => 101]);
$post = st()['posts'][0] ?? [];
check('movie request accepted and Pending', $r['code'] === 200 && ($r['j']['request']['state'] ?? '') === 'pending');
check('submitted as the dedicated requester (X-API-User 7), standard quality, no admin options', ($post['user'] ?? 0) === 7 && $post['is4k'] === false
  && !array_filter(st()['calls'], fn($c) => $c['m'] === 'POST' && (isset($c['body']['userId']) || isset($c['body']['serverId']) || isset($c['body']['rootFolder']))));
check('request stays Pending in Seerr (not auto-approved)', end(st()['requests'])['status'] === 1);
check('duplicate: same movie again refused before Seerr (409)', call(['action' => 'request', 'session' => $B, 'type' => 'movie', 'id' => 101])['code'] === 409 && count(st()['posts']) === 1);
check('available / pending / approved / declined movies refused (409)', call(['action' => 'request', 'session' => $A, 'type' => 'movie', 'id' => 102])['code'] === 409
  && call(['action' => 'request', 'session' => $A, 'type' => 'movie', 'id' => 103])['code'] === 409 && call(['action' => 'request', 'session' => $A, 'type' => 'movie', 'id' => 105])['code'] === 409
  && call(['action' => 'request', 'session' => $A, 'type' => 'movie', 'id' => 104])['code'] === 409 && count(st()['posts']) === 1);
$pdo->exec('DELETE FROM tv_request_rate');
check('TV: available or pending season refused', call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => [1]])['code'] === 409
  && call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => [2, 3]])['code'] === 409);
$r = call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => [3]]);
check('TV: only the chosen season is requested (never "all")', $r['code'] === 200 && end(st()['posts'])['seasons'] === [3]);
check('TV: the same season again refused', call(['action' => 'request', 'session' => $B, 'type' => 'tv', 'id' => 201, 'seasons' => [3]])['code'] === 409);
check('TV: specials, unknown, empty, non-integer and movie seasons refused (400)', call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => [0]])['code'] === 400
  && call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => [9]])['code'] === 400 && call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => []])['code'] === 400
  && call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => ['4']])['code'] === 400 && call(['action' => 'request', 'session' => $A, 'type' => 'movie', 'id' => 106, 'seasons' => [1]])['code'] === 400);

$pdo->exec('DELETE FROM tv_request_rate');
// concurrent: two customers request the same movie at the same moment -> exactly one request
setst(function ($s) { $s['slow_post'] = true; return $s; });
$mh = curl_multi_init(); $hs = [];
foreach ([$A, $B, $A, $B] as $sess) { $ch = curl_init("http://127.0.0.1:$port/api/tv-requests.php");
  curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_POST => true, CURLOPT_POSTFIELDS => json_encode(['action' => 'request', 'session' => $sess, 'type' => 'movie', 'id' => 106]), CURLOPT_HTTPHEADER => ['Content-Type: application/json']]);
  curl_multi_add_handle($mh, $ch); $hs[] = $ch; }
do { curl_multi_exec($mh, $run); curl_multi_select($mh, 0.2); } while ($run);
$codes = array_map(fn($h) => curl_getinfo($h, CURLINFO_RESPONSE_CODE), $hs); sort($codes);
setst(function ($s) { unset($s['slow_post']); return $s; });
check('concurrent submissions of one title: one request, the rest refused (' . implode(',', $codes) . ')', count(array_filter(st()['posts'], fn($p) => $p['key'] === 'movie:106')) === 1 && $codes[0] === 200 && $codes[3] === 409);

$pdo->exec('DELETE FROM tv_request_rate');
// ---------------- history and ownership ----------------
$ha = call(['action' => 'history', 'session' => $A, 'page' => 1])['j']['requests'] ?? []; $hb = call(['action' => 'history', 'session' => $B, 'page' => 1])['j']['requests'] ?? [];
$titles = fn($h) => array_map(fn($x) => $x['title'], $h);
check('My Requests: only the customer\'s own requests (' . count($ha) . '+' . count($hb) . ' of ' . count(st()['posts']) . ')', in_array('Alpha Movie', $titles($ha), true) && !in_array('Alpha Movie', $titles($hb), true)
  && count($ha) + count($hb) === count(st()['posts']));
check('My Requests: title, poster, date, seasons, state', ($ha[count($ha) - 1]['title'] ?? '') === 'Alpha Movie' && $ha[count($ha) - 1]['poster'] === 'https://image.tmdb.org/t/p/w342/alpha.jpg'
  && $ha[count($ha) - 1]['requested_at'] > 0 && in_array([3], array_map(fn($x) => $x['seasons'], $ha), true));
// statuses change in Seerr (approve, decline, available, deleted)
setst(function ($s) { foreach ($s['requests'] as &$r) { if ($r['key'] === 'movie:101') $r['status'] = 2; if ($r['key'] === 'tv:201' && $r['seasons'] === [3]) $r['status'] = 3; } return $s; });
$pdo->exec('UPDATE tv_media_requests SET last_checked_ts=0');
$ha = call(['action' => 'history', 'session' => $A, 'page' => 1])['j']['requests']; $m = []; foreach ($ha as $x) $m[$x['title'] . implode(',', $x['seasons'])] = $x['state'];
check('My Requests: Approved and Declined reflected', ($m['Alpha Movie'] ?? '') === 'approved' && ($m['Alpha Show3'] ?? '') === 'declined');
setst(function ($s) { $s['media']['movie:101']['status'] = 5; foreach ($s['requests'] as &$r) if ($r['key'] === 'movie:101') $r['status'] = 5; return $s; });
$pdo->exec('UPDATE tv_media_requests SET last_checked_ts=0');
$m = []; foreach (call(['action' => 'history', 'session' => $A, 'page' => 1])['j']['requests'] as $x) $m[$x['title']] = $x['state'];
check('My Requests: Available once Seerr reports it', ($m['Alpha Movie'] ?? '') === 'available');
check('My Requests: invalid page refused', call(['action' => 'history', 'session' => $A, 'page' => 0])['j']['error'] === 'page');

$pdo->exec('DELETE FROM tv_request_rate');
// ---------------- different customers at the same moment ----------------
// Three customers ask for overlapping seasons of one show at once: no season may reach Seerr twice,
// every accepted answer is exactly one Seerr request, and each customer only ever sees their own.
$C = login('carol', 'carol-pass');
setst(function ($s) { $s['slow_post'] = true; return $s; });
$jobs = [[$A, [1, 2]], [$B, [2, 3]], [$C, [3]], [$C, [1]], [$B, [4, 5]], [$A, [5]]];
$mh = curl_multi_init(); $hs = [];
foreach ($jobs as [$sess, $sea]) { $ch = curl_init("http://127.0.0.1:$port/api/tv-requests.php");
  curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_POST => true, CURLOPT_POSTFIELDS => json_encode(['action' => 'request', 'session' => $sess, 'type' => 'tv', 'id' => 203, 'seasons' => $sea]), CURLOPT_HTTPHEADER => ['Content-Type: application/json']]);
  curl_multi_add_handle($mh, $ch); $hs[] = $ch; }
do { curl_multi_exec($mh, $run); curl_multi_select($mh, 0.2); } while ($run);
$codes = array_map(fn($h) => curl_getinfo($h, CURLINFO_RESPONSE_CODE), $hs);
setst(function ($s) { unset($s['slow_post']); return $s; });
$racePosts = array_values(array_filter(st()['posts'], fn($p) => $p['key'] === 'tv:203'));
$sent = array_merge(...array_map(fn($p) => $p['seasons'], $racePosts ?: [['seasons' => []]]));
check('concurrent customers, overlapping seasons: no season sent to Seerr twice (' . implode(',', $codes) . ')',
  count($sent) === count(array_unique($sent)) && count($racePosts) === count(array_filter($codes, fn($c) => $c === 200))
  && count(array_filter($codes, fn($c) => !in_array($c, [200, 409], true))) === 0 && count($racePosts) >= 1);
$owned = []; $leak = false;
foreach (['alice' => $A, 'bob' => $B, 'carol' => $C] as $who => $sess) {
  $raw = call(['action' => 'history', 'session' => $sess, 'page' => 1]);
  foreach ($raw['j']['requests'] ?? [] as $x) if ($x['title'] === 'Race Show') $owned[$who][] = $x['seasons'];
  foreach (['alice', 'bob', 'carol', 'requestedBy', 'email', 'secret-user', 'plexToken', 'username'] as $bad) if (stripos(json_encode($raw['j']), $bad) !== false) $leak = true;
}
$ownedSeasons = array_merge(...array_values(array_map(fn($l) => array_merge(...$l), $owned ?: ['x' => [[]]])));
sort($ownedSeasons); $sortedSent = $sent; sort($sortedSent);
$rowsOk = (int)$pdo->query("SELECT COUNT(*) FROM tv_media_requests WHERE tmdb_id=203")->fetchColumn() === count($racePosts);
check('concurrent customers: each accepted request belongs to exactly one customer, recorded once', $rowsOk && $ownedSeasons === $sortedSent);
check('concurrent customers: My Requests never shows another customer, their name or Seerr user data', !$leak);
check('every Seerr submission so far was made as the requester (X-API-User 7), never as the admin key user',
  count(array_filter(st()['posts'], fn($p) => $p['user'] !== 7)) === 0);
check('every request created in Seerr so far is Pending (no auto-approval)',
  count(array_filter(st()['requests'], fn($r) => $r['user'] === 7 && $r['status'] !== 1 && !in_array($r['key'], ['movie:101', 'tv:201'], true))) === 0);

$pdo->exec('DELETE FROM tv_request_rate');
// The requester gains Auto-Approve while the service runs: the very next submission is refused.
setst(function ($s) { $s['users']['7']['permissions'] = 32 | 128; return $s; });
$before = count(st()['posts']);
$r2 = call(['action' => 'request', 'session' => $C, 'type' => 'tv', 'id' => 201, 'seasons' => [4]]);
check('requester given Auto-Approve later: the next submissions are refused at once, nothing sent', $r2['code'] === 503 && count(st()['posts']) === $before);
setst(function ($s) { $s['users']['7']['permissions'] = 32; return $s; });

// ---------------- requester safety ----------------
foreach ([8 => 'AUTO_APPROVE', 11 => 'AUTO_APPROVE_MOVIE', 12 => 'AUTO_APPROVE_TV', 13 => 'MANAGE_REQUESTS', 9 => 'ADMIN', 10 => 'no request permission', 1 => 'user 1 (administrator)'] as $uid => $label) {
  proc_terminate($server); usleep(300000); @unlink("$storage/tv-requests-requester.json");
  $server = serve(['TVQ_REQUESTER' => (string)$uid]); for ($i = 0; $i < 50 && !@fsockopen('127.0.0.1', $port); $i++) usleep(100000);
  $before = count(st()['posts'] ?? []);
  $r = call(['action' => 'request', 'session' => login('bob', 'bob-pass'), 'type' => 'tv', 'id' => 201, 'seasons' => [4]]);
  check("requester with $label: refused (503), nothing sent to Seerr", $r['code'] === 503 && $r['j']['message'] === $unavailable && count(st()['posts'] ?? []) === $before);
}
proc_terminate($server); usleep(300000); @unlink("$storage/tv-requests-requester.json");
$server = serve(); for ($i = 0; $i < 50 && !@fsockopen('127.0.0.1', $port); $i++) usleep(100000);
$A = login('alice', 'alice-pass'); $B = login('bob', 'bob-pass');

$pdo->exec('DELETE FROM tv_request_rate');
// ---------------- Flix Town catalog, checked on the server ----------------
$D = login('dave', 'dave-pass'); $E = login('erin', 'erin-pass'); $G = login('gina', 'gina-pass'); $H = login('hank', 'hank-pass');
$posts0 = count(st()['posts']);
$m = call(['action' => 'details', 'session' => $D, 'type' => 'movie', 'id' => 107])['j']['details'] ?? [];
check('catalog: a movie already in the customer\'s Flix Town is "local" (In Flix Town) and cannot be requested', ($m['state'] ?? '') === 'local' && $m['can_request'] === false && $m['flix_checked'] === true);
$sr = []; foreach (call(['action' => 'search', 'session' => $D, 'query' => 'movie', 'page' => 1])['j']['results'] ?? [] as $x) $sr[$x['id']] = $x['state'];
check('catalog: search shows that movie as In Flix Town, others unchanged', ($sr[107] ?? '') === 'local' && ($sr[108] ?? '') === 'requestable');
$r = call(['action' => 'request', 'session' => $D, 'type' => 'movie', 'id' => 107]);
check('catalog: a direct request for a movie already in Flix Town is refused (409), nothing sent to Seerr', $r['code'] === 409 && ($r['j']['message'] ?? '') === 'This is already in Flix Town.' && count(st()['posts']) === $posts0);
$r = call(['action' => 'request', 'session' => $D, 'type' => 'movie', 'id' => 108]);
check('catalog: a Flix entry with this TMDB id but a far-off year (mis-tagged) does not block', $r['code'] === 200);
$t = call(['action' => 'details', 'session' => $D, 'type' => 'tv', 'id' => 204])['j']['details'] ?? []; $ss = []; foreach ($t['seasons'] ?? [] as $x) $ss[$x['number']] = $x['state'];
check('catalog: a season with every episode in Flix Town is In Flix Town; a partly present season stays requestable', $ss === [1 => 'local', 2 => 'requestable', 3 => 'requestable'] && $t['state'] === 'partial' && $t['can_request'] === true, json_encode($ss));
$p0 = count(st()['posts']);
check('catalog: requests for a season already in Flix Town are refused even when the app sends them (409)',
  call(['action' => 'request', 'session' => $D, 'type' => 'tv', 'id' => 204, 'seasons' => [1]])['code'] === 409
  && call(['action' => 'request', 'session' => $D, 'type' => 'tv', 'id' => 204, 'seasons' => [1, 3]])['code'] === 409 && count(st()['posts']) === $p0);
$r = call(['action' => 'request', 'session' => $D, 'type' => 'tv', 'id' => 204, 'seasons' => [3]]);
check('catalog: the missing season goes through on its own', $r['code'] === 200 && end(st()['posts'])['seasons'] === [3]);
$f = call(['action' => 'details', 'session' => $D, 'type' => 'tv', 'id' => 205])['j']['details'] ?? []; $fs = []; foreach ($f['seasons'] ?? [] as $x) $fs[$x['number']] = $x['state'];
check('catalog: servers that list episodes flat (with "season") are read too', $fs === [1 => 'local', 2 => 'requestable'], json_encode($fs));
check('catalog: read with each customer\'s own Flix Town line', !array_filter(st()['player_calls'], fn($c) => !$c['own_password']) && count(st()['player_calls']) > 0);
$cache = ''; foreach (glob("$storage/tv-requests-catalog/*") as $file) $cache .= file_get_contents($file);
check('catalog: the cache holds ids and years only, no passwords or usernames', $cache !== '' && !preg_match('/-pass|dave|erin/', $cache));
setst(function ($s) { $s['player_down'] = true; return $s; });
foreach (glob("$storage/tv-requests-catalog/*") as $file) unlink($file);   // nothing cached: the catalog must be read
$p0 = count(st()['posts']);
$d1 = call(['action' => 'details', 'session' => $E, 'type' => 'movie', 'id' => 109]);
$r = call(['action' => 'request', 'session' => $E, 'type' => 'movie', 'id' => 109]);
check('catalog unreadable: details still shown (marked unchecked), the request is refused (503), nothing sent', $d1['code'] === 200 && $d1['j']['details']['flix_checked'] === false
  && $r['code'] === 503 && $r['j']['message'] === $unavailable && count(st()['posts']) === $p0);
setst(function ($s) { unset($s['player_down']); return $s; });

// ---------------- MySQL: ownership records ----------------
$r = call(['action' => 'request', 'session' => $E, 'type' => 'movie', 'id' => 110]);
$hist = call(['action' => 'history', 'session' => $E, 'page' => 1])['j']['requests'] ?? [];
check('MySQL utf8mb4: a title with accents and emoji is stored and returned exactly', $r['code'] === 200 && in_array('Café 🎬 Night', array_column($hist, 'title'), true));
$row = $pdo->query("SELECT username, media_type, seasons, last_status FROM tv_media_requests WHERE tmdb_id=204 AND media_type='tv'")->fetchAll();
check('ownership row: customer, type, seasons and Pending status recorded', $row === [['username' => 'dave', 'media_type' => 'tv', 'seasons' => '3', 'last_status' => 1]], json_encode($row));
$dupOk = false; try { $pdo->exec("INSERT INTO tv_media_requests(username,seerr_request_id,media_type,tmdb_id,created_ts) SELECT 'zed', seerr_request_id, 'movie', 1, 1 FROM tv_media_requests LIMIT 1"); } catch (PDOException $e) { $dupOk = $e->getCode() === '23000'; }
check('MySQL refuses a second owner for one Seerr request (unique key, SQLSTATE 23000)', $dupOk);
$pdo->exec("INSERT INTO tv_media_requests(username,seerr_request_id,media_type,tmdb_id,created_ts,last_status) VALUES('zed',950,'movie',111," . time() . ",1)");
$p0 = count(st()['posts']); $r = call(['action' => 'request', 'session' => $E, 'type' => 'movie', 'id' => 111]);
check('own records: a pending request recorded in OnePanel blocks a duplicate even if Seerr has not caught up', $r['code'] === 409 && count(st()['posts']) === $p0);
$pdo->exec('DELETE FROM tv_request_rate');

// ---------------- MySQL: duplicates across two web servers sharing the database ----------------
$port2 = $port + 1; $storage2 = "$work/storage-2";
$server2 = proc_open([PHP_BINARY, '-S', "127.0.0.1:$port2", '-t', $work], [1 => ['file', '/dev/null', 'w'], 2 => ['file', $log, 'a']], $p2, null, ['TVQ_STORAGE' => $storage2] + $env);
for ($i = 0; $i < 50 && !@fsockopen('127.0.0.1', $port2); $i++) usleep(100000);
setst(function ($s) { $s['slow_post'] = true; return $s; });
$mh = curl_multi_init(); $hs = [];
foreach ([[$port, $D], [$port2, $E], [$port, $G], [$port2, $H], [$port2, $D], [$port, $E], [$port2, $G], [$port, $H]] as [$pt, $sess]) {
  $ch = curl_init("http://127.0.0.1:$pt/api/tv-requests.php");
  curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_POST => true, CURLOPT_TIMEOUT => 60, CURLOPT_POSTFIELDS => json_encode(['action' => 'request', 'session' => $sess, 'type' => 'movie', 'id' => 112]), CURLOPT_HTTPHEADER => ['Content-Type: application/json']]);
  curl_multi_add_handle($mh, $ch); $hs[] = $ch; }
do { curl_multi_exec($mh, $run); curl_multi_select($mh, 0.2); } while ($run);
$codes = array_map(fn($h) => curl_getinfo($h, CURLINFO_RESPONSE_CODE), $hs); sort($codes);
setst(function ($s) { unset($s['slow_post']); return $s; });
$owners = (int)$pdo->query('SELECT COUNT(*) FROM tv_media_requests WHERE tmdb_id=112')->fetchColumn();
check('two web servers, four customers, eight simultaneous submissions of one movie: one Seerr request, one owner row (' . implode(',', $codes) . ')',
  count(array_filter(st()['posts'], fn($p) => $p['key'] === 'movie:112')) === 1 && $owners === 1 && $codes[0] === 200 && $codes[1] === 409 && $codes[7] === 409);
proc_terminate($server2);
// A submission that cannot get the title lock within 15 s gives up politely (429), sending nothing.
$hold = 'tvq:' . substr(hash('sha256', 'title:movie:113'), 0, 40);
$admin->query("SELECT GET_LOCK('$hold', 0)")->fetchColumn();
$p0 = count(st()['posts']); $t0 = microtime(true);
$r = call(['action' => 'request', 'session' => $G, 'type' => 'movie', 'id' => 113]);
$admin->query("SELECT RELEASE_LOCK('$hold')")->fetchColumn();
check('MySQL title lock held elsewhere: waits about 15 s, then 429 "try again", nothing sent', $r['code'] === 429 && ($r['j']['error'] ?? '') === 'busy' && count(st()['posts']) === $p0 && microtime(true) - $t0 >= 14);
check('lock released: the same submission then goes through', call(['action' => 'request', 'session' => $G, 'type' => 'movie', 'id' => 113])['code'] === 200);

// ---------------- MySQL: rate limiting under concurrency ----------------
$pdo->exec('DELETE FROM tv_request_rate');
$workers = [];
for ($w = 0; $w < 16; $w++) { $proc = proc_open([PHP_BINARY, __DIR__ . '/rate_worker.php', $work, 'race-key', '100', '60', '25'], [1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pp, null, $env); $workers[] = [$proc, $pp]; }
$allowed = 0; $errors = '';
foreach ($workers as [$proc, $pp]) { $allowed += (int)stream_get_contents($pp[1]); $errors .= stream_get_contents($pp[2]); fclose($pp[1]); fclose($pp[2]); if (proc_close($proc) !== 0) $errors .= 'worker failed;'; }
$row = $pdo->query("SELECT hits FROM tv_request_rate WHERE k='race-key'")->fetchColumn();
check("rate limit under 16 parallel processes x 25 hits (limit 100): exactly 100 allowed, all 400 counted, no deadlock ($allowed allowed)", $allowed === 100 && (int)$row === 400 && $errors === '', substr($errors, 0, 200));
$pdo->exec("UPDATE tv_request_rate SET window_start=window_start-61 WHERE k='race-key'");
$one = proc_open([PHP_BINARY, __DIR__ . '/rate_worker.php', $work, 'race-key', '100', '60', '1'], [1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pp, null, $env);
$again = (int)stream_get_contents($pp[1]); proc_close($one);
check('rate limit: after the window passes the count starts again at 1', $again === 1 && (int)$pdo->query("SELECT hits FROM tv_request_rate WHERE k='race-key'")->fetchColumn() === 1);

$pdo->exec('DELETE FROM tv_request_rate');
// ---------------- failures and limits ----------------
foreach (['down' => 'Seerr down', 'timeout' => 'Seerr timeout', '500' => 'Seerr 500', 'malformed' => 'malformed Seerr answer'] as $mode => $label) {
  setst(function ($s) use ($mode) { $s['seerr_mode'] = $mode; return $s; });
  $r = call(['action' => 'search', 'session' => $A, 'query' => 'alpha', 'page' => 1]); $d2 = call(['action' => 'details', 'session' => $A, 'type' => 'movie', 'id' => 101]);
  check("$label: \"Requests are temporarily unavailable.\" (503)", $r['code'] === 503 && $r['j']['message'] === $unavailable && $d2['code'] === 503 && !preg_match('/boom|refused|timed out/i', $r['raw']));
}
$h = call(['action' => 'history', 'session' => $A, 'page' => 1]);
check('Seerr down: My Requests still lists the last known states', $h['code'] === 200 && count($h['j']['requests']) >= 2);
setst(function ($s) { unset($s['seerr_mode']); return $s; });
setst(function ($s) { $s['flix_down'] = true; return $s; });
check('Flix Town account check down: sign-in unavailable (503), not "wrong password"', call(['action' => 'session', 'username' => 'alice', 'password' => 'alice-pass'])['code'] === 503);
setst(function ($s) { unset($s['flix_down']); return $s; });
$codes = []; for ($i = 0; $i < 45; $i++) $codes[] = call(['action' => 'search', 'session' => $B, 'query' => 'alpha', 'page' => 1])['code'];
check('search rate limit (40 per minute per customer) answers 429', in_array(429, $codes, true) && $codes[0] === 200);
$pdo->exec('DELETE FROM tv_request_rate');
$pdo->exec("INSERT INTO tv_media_requests(username,seerr_request_id,media_type,tmdb_id,created_ts,last_status) VALUES('alice',901,'movie',9001," . time() . ",1),('alice',902,'movie',9002," . time() . ",1),('alice',903,'movie',9003," . time() . ",1)");
check('daily limit (5 per customer) answers 429', call(['action' => 'request', 'session' => $A, 'type' => 'tv', 'id' => 201, 'seasons' => [4]])['j']['error'] === 'daily_limit');
$bad = []; for ($i = 0; $i < 12; $i++) $bad[] = call(['action' => 'session', 'username' => 'alice', 'password' => 'guess' . $i])['code'];
check('password guessing throttled (429)', in_array(429, $bad, true));

// ---------------- feature switch ----------------
proc_terminate($server); usleep(300000);
$server = serve(['TVQ_OFF' => '1']); for ($i = 0; $i < 50 && !@fsockopen('127.0.0.1', $port); $i++) usleep(100000);
check('customer_requests_enabled off: everything 503, unavailable', call(['action' => 'session', 'username' => 'alice', 'password' => 'alice-pass'])['code'] === 503);

// ---------------- secrets ----------------
$logText = (string)@file_get_contents($log);
check('no API key, Flix token, password, Seerr user or email in any answer', !preg_match('/SEERR-API-KEY|FLIX-ADMIN-TOKEN|alice-pass|bob-pass|secret-user|requester@|permissions|TOKEN-XYZ/', $bodies));
check('no API key, token or password in the server log', !preg_match('/SEERR-API-KEY|FLIX-ADMIN-TOKEN|alice-pass|bob-pass/', $logText));

proc_terminate($server);
if (!$fail) { system('rm -rf ' . escapeshellarg($work)); $pdo = null; $admin->exec("DROP DATABASE `$dbName`"); } else echo "kept $work and database $dbName\n";
echo "\nTV requests API: $pass passed, $fail failed\n";
exit($fail ? 1 : 0);
