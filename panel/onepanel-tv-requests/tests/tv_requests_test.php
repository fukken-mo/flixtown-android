<?php
/*
 * End-to-end test of the customer Requests API: the real api/tv-requests.php and
 * includes/tv_requests.php, plus OnePanel's own includes/seerr.php (fixture copy, unmodified),
 * served by `php -S` with several workers, against tests/fake_bootstrap.php (SQLite, fake Flix Town,
 * fake Seerr following the verified Seerr permission and duplicate rules). Never contacts a real
 * Seerr. Run: php panel/onepanel-tv-requests/tests/tv_requests_test.php
 */
$root = dirname(__DIR__);
$work = sys_get_temp_dir() . '/tvq-test-' . getmypid();
@mkdir("$work/api", 0777, true); @mkdir("$work/includes", 0777, true);
copy("$root/api/tv-requests.php", "$work/api/tv-requests.php");
copy("$root/includes/tv_requests.php", "$work/includes/tv_requests.php");
copy(__DIR__ . '/fixtures/seerr.php', "$work/includes/seerr.php");
copy(__DIR__ . '/fake_bootstrap.php', "$work/includes/bootstrap.php");
$db = "$work/t.sqlite"; $state = "$work/state.json"; $storage = "$work/storage"; $log = "$work/server.log";
$env = ['TVQ_DB' => $db, 'TVQ_STATE' => $state, 'TVQ_STORAGE' => $storage, 'PHP_CLI_SERVER_WORKERS' => '4'];
foreach ($env as $k => $v) putenv("$k=$v");

$pdo = new PDO("sqlite:$db", null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
$pdo->exec('PRAGMA journal_mode=WAL');
$sql = file_get_contents("$root/sql/migrations/2026-10-14-tv-requests.sql");
$sql = preg_replace(['/\) ENGINE=[^;]*;/', '/^\s*(UNIQUE KEY|INDEX) [^\n]*\n/m', '/--[^\n]*/', '/ UNSIGNED/', '/BIGINT AUTO_INCREMENT PRIMARY KEY/', '/,\s*\)/'],
                    [');', '', '', '', 'INTEGER PRIMARY KEY AUTOINCREMENT', ')'], $sql);
$pdo->exec($sql);
$pdo->exec('CREATE UNIQUE INDEX uq_seerr ON tv_media_requests(seerr_request_id)');

$future = gmdate('Y-m-d\TH:i:s', time() + 86400 * 30); $past = gmdate('Y-m-d\TH:i:s', time() - 86400);
$seasons = function ($n) { $o = [['seasonNumber' => 0, 'name' => 'Specials', 'episodeCount' => 3]];
  for ($i = 1; $i <= $n; $i++) $o[] = ['seasonNumber' => $i, 'name' => "Season $i", 'episodeCount' => 8]; return $o; };
file_put_contents($state, json_encode([
  'flix' => [
    'alice' => ['username' => 'alice', 'password' => 'alice-pass', 'expires_at' => $future, 'enabled' => true],
    'bob' => ['username' => 'bob', 'password' => 'bob-pass', 'expires_at' => null, 'enabled' => true],
    'carol' => ['username' => 'carol', 'password' => 'carol-pass', 'expires_at' => $future, 'enabled' => true],
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
    ['mediaType' => 'tv', 'id' => 202, 'name' => 'Complete Show', 'firstAirDate' => '2010-01-01', 'seasons' => $seasons(2)],
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
if (!$fail) system('rm -rf ' . escapeshellarg($work)); else echo "kept $work\n";
echo "\nTV requests API: $pass passed, $fail failed\n";
exit($fail ? 1 : 0);
