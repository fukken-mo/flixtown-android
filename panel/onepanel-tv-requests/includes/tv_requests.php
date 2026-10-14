<?php
/*
 * OnePanel · Flix Town TV media requests (customer side)
 *
 * The TV app's bridge to Seerr: Flix Town app -> api/tv-requests.php -> this file -> Seerr.
 * Admin pages (media-requests.php, approve/decline) are untouched; this file only reads the
 * existing Seerr settings through includes/seerr.php (seerr_config, seerr_parse_response).
 *
 * Identity: the TV signs in once with the customer's own Flix Town username/password, checked
 * here against the Flix Town backend; it then holds a short HMAC-signed session. Nothing the app
 * sends about who it is is trusted without that check.
 *
 * Requester: every request is created in Seerr *as one dedicated Seerr user* (X-API-User,
 * config requester_user_id). Seerr auto-approves a request when the acting user has ADMIN,
 * MANAGE_REQUESTS or AUTO_APPROVE(_MOVIE/_TV) (server/entity/MediaRequest.ts), and an API-key
 * call without X-API-User acts as admin user 1 (server/middleware/auth.ts). So before each
 * request this checks that user's permissions and refuses (fails closed) if it could approve
 * anything itself. Requests therefore stay Pending until approved in OnePanel or Seerr.
 *
 * Ownership: OnePanel records which Flix Town customer made each Seerr request
 * (tv_media_requests); a customer's history only ever reads their own rows.
 *
 * Flix catalog: before a request is sent, the customer's own Flix Town line is asked for its
 * catalog (player_api.php get_vod_streams / get_series / get_series_info, the same lists the TV
 * app shows). A movie already there, or a season whose episodes are all there, is refused here,
 * whatever the app sent. If the catalog cannot be read, the request is refused (fails closed).
 *
 * Database: MySQL/MariaDB (OnePanel's own). Duplicate protection uses MySQL named locks
 * (GET_LOCK), which hold across PHP workers and web servers sharing the database.
 */

if (!function_exists('tvq_config')) {

define('TVQ_SESSION_TTL', 1800);
define('TVQ_IMG', 'https://image.tmdb.org/t/p/');
// Seerr enums (server/constants/media.ts)
define('TVQ_REQ_PENDING', 1); define('TVQ_REQ_APPROVED', 2); define('TVQ_REQ_DECLINED', 3);
define('TVQ_REQ_FAILED', 4); define('TVQ_REQ_COMPLETED', 5);
define('TVQ_MEDIA_UNKNOWN', 1); define('TVQ_MEDIA_PENDING', 2); define('TVQ_MEDIA_PROCESSING', 3);
define('TVQ_MEDIA_PARTIAL', 4); define('TVQ_MEDIA_AVAILABLE', 5); define('TVQ_MEDIA_BLOCKLISTED', 6);
define('TVQ_MEDIA_DELETED', 7);
// Seerr permissions (server/lib/permissions.ts)
define('TVQ_P_ADMIN', 2); define('TVQ_P_MANAGE_USERS', 8); define('TVQ_P_MANAGE_REQUESTS', 16); define('TVQ_P_REQUEST', 32);
define('TVQ_P_AUTO_APPROVE', 128); define('TVQ_P_AUTO_APPROVE_MOVIE', 256); define('TVQ_P_AUTO_APPROVE_TV', 512);
define('TVQ_P_REQUEST_MOVIE', 262144); define('TVQ_P_REQUEST_TV', 524288);
define('TVQ_UNAVAILABLE', 'Requests are temporarily unavailable.');

class TvqException extends Exception {
  public $http; public $code_name;
  function __construct(int $http, string $code, string $message) { parent::__construct($message); $this->http = $http; $this->code_name = $code; }
}
function tvq_fail(int $http, string $code, string $message): void { throw new TvqException($http, $code, $message); }
function tvq_unavailable(string $why): void {
  // The reason is for the server log only (it never contains keys or passwords).
  error_log('tv-requests unavailable: ' . $why);
  tvq_fail(503, 'unavailable', TVQ_UNAVAILABLE);
}

function tvq_config(): array {
  $c = function_exists('seerr_config') ? seerr_config() : [];
  global $config;
  $raw = $config['services']['seerr'] ?? ($config['seerr'] ?? []);
  return [
    'seerr' => $c,
    // Off unless explicitly turned on with a literal true.
    'enabled' => isset($raw['customer_requests_enabled']) && $raw['customer_requests_enabled'] === true,
    'requester' => (int)($raw['requester_user_id'] ?? 0),
    'secret' => (string)($raw['customer_session_secret'] ?? ''),
    'daily_limit' => max(1, (int)($raw['customer_daily_limit'] ?? 5)),
    'open_limit' => max(1, (int)($raw['customer_open_limit'] ?? 10)),
    'allow_expired' => ($raw['customer_allow_expired'] ?? false) === true,
    'rerequest_declined' => ($raw['customer_allow_rerequest_declined'] ?? false) === true,
    // Flix Town player API (catalog check). Defaults to the Flix Town adapter address.
    'player_url' => rtrim((string)($raw['customer_player_api_url'] ?? ($config['services']['flix_town']['base_url'] ?? '')), '/'),
    // Optional line used for the catalog check when the Flix Town adapter gives no line password.
    'catalog_line' => [(string)($raw['customer_catalog_username'] ?? ''), (string)($raw['customer_catalog_password'] ?? '')],
  ];
}
/** '' when the customer API may run, else the (log-only) reason. */
function tvq_problem(): string {
  $c = tvq_config();
  if (!$c['enabled']) return 'customer_requests_enabled is not true';
  if (function_exists('seerr_config_problem') && seerr_config_problem() !== '') return 'Seerr is not configured';
  if ($c['requester'] <= 1) return 'requester_user_id is missing (it must be a dedicated non-admin Seerr user, never 1)';
  if (strlen($c['secret']) < 32) return 'customer_session_secret is missing or shorter than 32 characters';
  return '';
}

/* ---------------- sessions ---------------- */

function tvq_b64(string $raw): string { return rtrim(strtr(base64_encode($raw), '+/', '-_'), '='); }
function tvq_unb64(string $s) { return base64_decode(strtr($s, '-_', '+/') . str_repeat('=', (4 - strlen($s) % 4) % 4), true); }
function tvq_valid_username($u): bool { return is_string($u) && $u !== '' && strlen($u) <= 100 && preg_match('/^[A-Za-z0-9._@-]+$/', $u); }
function tvq_session_issue(string $username, ?int $now = null): string {
  $now = $now ?? time();
  $body = tvq_b64(json_encode(['u' => $username, 'exp' => $now + TVQ_SESSION_TTL, 'p' => 'requests', 'n' => bin2hex(random_bytes(6))]));
  return $body . '.' . tvq_b64(hash_hmac('sha256', $body, tvq_config()['secret'], true));
}
function tvq_session_user($token, ?int $now = null): ?string {
  if (!is_string($token) || strlen($token) > 400 || substr_count($token, '.') !== 1) return null;
  [$body, $sig] = explode('.', $token);
  if (!hash_equals(tvq_b64(hash_hmac('sha256', $body, tvq_config()['secret'], true)), $sig)) return null;
  $d = json_decode((string)tvq_unb64($body), true);
  if (!is_array($d) || ($d['p'] ?? '') !== 'requests' || (int)($d['exp'] ?? 0) < ($now ?? time())) return null;
  return tvq_valid_username($d['u'] ?? null) ? (string)$d['u'] : null;
}

/* ---------------- rate limits and locks ---------------- */

function tvq_rate(PDO $pdo, string $key, int $max, int $window): bool {
  // One atomic statement per hit (no select-then-insert, no gap-lock deadlocks): a new key starts
  // at 1, an expired window restarts at 1, otherwise the count goes up by one. The count this hit
  // produced comes back through LAST_INSERT_ID(expr) (per connection), so another process counting
  // the same key at the same moment can never change what this hit sees.
  $now = time(); $key = substr($key, 0, 120); $old = $now - $window;
  $st = $pdo->prepare('INSERT INTO tv_request_rate(k,window_start,hits) VALUES(?,?,1)
      ON DUPLICATE KEY UPDATE hits=LAST_INSERT_ID(IF(window_start<=?,1,hits+1)), window_start=IF(window_start<=?,?,window_start)');
  $st->execute([$key, $now, $old, $old, $now]);
  if ($st->rowCount() === 1) return 1 <= $max;   // a new row: this is hit 1
  $q = $pdo->query('SELECT LAST_INSERT_ID()'); $hits = (int)$q->fetchColumn(); $q->closeCursor();
  return $hits <= $max;
}
/**
 * Exclusive lock (MySQL GET_LOCK, held by this request's database connection) so two customers,
 * or two submissions of one customer, cannot work on the same thing at once. Waits up to 15 s.
 */
function tvq_lock(PDO $pdo, string $name): string {
  $lock = 'tvq:' . substr(hash('sha256', $name), 0, 40);
  $q = $pdo->prepare('SELECT GET_LOCK(?, 15)'); $q->execute([$lock]); $got = $q->fetchColumn(); $q->closeCursor();
  if ($got === null || $got === false) tvq_unavailable('GET_LOCK failed');
  if ((int)$got !== 1) tvq_fail(429, 'busy', 'Please try again in a moment.');
  return $lock;
}
function tvq_unlock(PDO $pdo, string $lock): void {
  try { $q = $pdo->prepare('SELECT RELEASE_LOCK(?)'); $q->execute([$lock]); $q->closeCursor(); } catch (Throwable $e) { /* released with the connection */ }
}

/* ---------------- Flix Town identity ---------------- */

/** The Flix Town line if $password is really its password; null otherwise. */
function tvq_flix_login(string $username, string $password): ?array {
  if ($password === '' || strlen($password) > 100) return null;
  $r = flix_api_request('GET', '/api/onepanel/users/' . rawurlencode($username));
  if ((int)$r['status'] === 404) return null;
  if (!$r['ok']) tvq_unavailable('Flix Town account lookup failed');
  $data = is_array($r['data']) ? $r['data'] : [];
  $user = is_array($data['user'] ?? null) ? $data['user'] : $data;
  if (!is_array($user) || (string)($user['username'] ?? '') !== $username) return null;
  if (isset($user['password']) && (string)$user['password'] !== '') return hash_equals((string)$user['password'], $password) ? $user : null;
  // No password in the adapter answer: ask the Flix Town player API with the customer's own credentials.
  global $config;
  $base = rtrim((string)($config['services']['flix_town']['base_url'] ?? ''), '/');
  if (isset($GLOBALS['tvq_player_api']) && is_callable($GLOBALS['tvq_player_api'])) $info = ($GLOBALS['tvq_player_api'])($username, $password);
  else {
    $ch = curl_init($base . '/player_api.php');
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 6, CURLOPT_TIMEOUT => 12, CURLOPT_POST => true,
      CURLOPT_POSTFIELDS => http_build_query(['username' => $username, 'password' => $password])]);
    $raw = curl_exec($ch); curl_close($ch);
    $info = is_string($raw) ? (json_decode($raw, true)['user_info'] ?? null) : null;
  }
  return is_array($info) && in_array((string)($info['status'] ?? ''), ['Active', 'Expired'], true)
    && (string)($info['username'] ?? '') === $username ? $user : null;
}
function tvq_account_active(array $user): bool {
  if (array_key_exists('enabled', $user) && !$user['enabled']) return false;
  $exp = $user['expires_at'] ?? null;
  if (!$exp) return true;   // Never Expire
  try { return (new DateTimeImmutable((string)$exp, new DateTimeZone('UTC')))->getTimestamp() > time(); } catch (Throwable $e) { return false; }
}

/* ---------------- Flix Town catalog ---------------- */
/** array_is_list() for PHP before 8.1. */
function tvq_is_list(array $a): bool { $i = 0; foreach ($a as $k => $v) { if ($k !== $i++) return false; } return true; }

/** The customer's own Flix Town line for catalog reads; the password never leaves this request. */
function tvq_flix_line(string $username): array {
  static $lines = [];
  if (isset($lines[$username])) return $lines[$username];
  $r = flix_api_request('GET', '/api/onepanel/users/' . rawurlencode($username));
  if (!$r['ok']) tvq_unavailable('Flix Town account lookup failed (catalog check)');
  $data = is_array($r['data']) ? $r['data'] : [];
  $user = is_array($data['user'] ?? null) ? $data['user'] : $data;
  if ((string)($user['username'] ?? '') === $username && (string)($user['password'] ?? '') !== '') return $lines[$username] = [$username, (string)$user['password']];
  $line = tvq_config()['catalog_line'];
  if ($line[0] !== '' && $line[1] !== '') return $lines[$username] = $line;
  tvq_unavailable('no Flix Town line password for the catalog check (set customer_catalog_username/password)');
  return [];
}
/** One player_api.php read. Any failure or unreadable answer: unavailable (callers fail closed). */
function tvq_player(array $line, array $params): array {
  $query = ['username' => $line[0], 'password' => $line[1]] + $params;
  if (isset($GLOBALS['tvq_player_http']) && is_callable($GLOBALS['tvq_player_http'])) $data = ($GLOBALS['tvq_player_http'])($query);
  else {
    $base = tvq_config()['player_url'];
    if ($base === '') tvq_unavailable('Flix Town player API address is not configured');
    $ch = curl_init($base . '/player_api.php?' . http_build_query($query, '', '&', PHP_QUERY_RFC3986));
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 6, CURLOPT_TIMEOUT => 30,
      CURLOPT_FOLLOWLOCATION => false, CURLOPT_USERAGENT => 'OnePanel/1.0 (tv-requests catalog)']);
    $raw = curl_exec($ch); $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE); curl_close($ch);
    $data = ($raw !== false && $status === 200) ? json_decode((string)$raw, true) : null;
  }
  if (!is_array($data)) tvq_unavailable('Flix Town catalog read failed (' . preg_replace('/[^a-z_]/', '', (string)($params['action'] ?? '')) . ')');
  return $data;
}
/** Year of a catalog entry: "year", a release date, else "(2019)" at the end of the name; 0 if unknown. */
function tvq_flix_year(array $row): int {
  $y = (int)($row['year'] ?? 0); if ($y > 1870 && $y < 2100) return $y;
  foreach (['releaseDate', 'release_date', 'releasedate', 'first_air_date'] as $f)
    if (preg_match('/^(\d{4})-\d{2}-\d{2}/', (string)($row[$f] ?? ''), $m)) return (int)$m[1];
  return preg_match('/\((\d{4})\)\s*$/', (string)($row['name'] ?? ''), $m) ? (int)$m[1] : 0;
}
/**
 * TMDB id -> [[catalog id, year], ...] for this line's movies ("movie") or series ("tv").
 * Kept up to $maxAge seconds in storage (ids and years only; the file name is a hash of the line).
 */
function tvq_flix_index(array $line, string $kind, int $maxAge): array {
  $dir = onepanel_storage_dir() . '/tv-requests-catalog';
  if (!is_dir($dir)) @mkdir($dir, 0750, true);
  $file = $dir . '/' . hash('sha256', $kind . '|' . $line[0]) . '.json';
  $c = is_file($file) ? json_decode((string)@file_get_contents($file), true) : null;
  if (is_array($c) && ($c['t'] ?? 0) > time() - $maxAge && is_array($c['i'] ?? null)) return $c['i'];
  $list = tvq_player($line, ['action' => $kind === 'movie' ? 'get_vod_streams' : 'get_series']);
  if (!tvq_is_list($list)) tvq_unavailable('Flix Town catalog answer is not a list');
  $index = [];
  foreach ($list as $row) {
    if (!is_array($row)) continue;
    $tmdb = trim((string)($row['tmdb'] ?? ($row['tmdb_id'] ?? '')));
    if (!preg_match('/^\d{1,9}$/', $tmdb) || $tmdb === '0') continue;
    $index[$tmdb][] = [(string)($kind === 'movie' ? ($row['stream_id'] ?? '') : ($row['series_id'] ?? '')), tvq_flix_year($row)];
  }
  $tmp = $file . '.' . getmypid() . '.tmp';
  if (@file_put_contents($tmp, json_encode(['t' => time(), 'i' => $index])) !== false) @rename($tmp, $file);
  return $index;
}
/** Catalog ids with this TMDB id whose year agrees (within a year; an unknown year counts). */
function tvq_flix_matches(array $index, int $tmdb, string $year): array {
  $out = [];
  foreach ($index[(string)$tmdb] ?? [] as $e) if ($year === '' || (int)$e[1] === 0 || abs((int)$e[1] - (int)$year) <= 1) $out[] = (string)$e[0];
  return $out;
}
/** Is this movie already in the customer's Flix Town catalog? */
function tvq_flix_has_movie(string $username, int $tmdb, string $year, int $maxAge): bool {
  return (bool)tvq_flix_matches(tvq_flix_index(tvq_flix_line($username), 'movie', $maxAge), $tmdb, $year);
}
/** Season number -> episodes in the customer's Flix Town catalog for this series (all matching entries). */
function tvq_flix_season_episodes(string $username, int $tmdb, string $year, int $maxAge): array {
  $line = tvq_flix_line($username);
  $counts = [];
  foreach (tvq_flix_matches(tvq_flix_index($line, 'tv', $maxAge), $tmdb, $year) as $sid) {
    if (!preg_match('/^\d{1,12}$/', $sid)) continue;
    $info = tvq_player($line, ['action' => 'get_series_info', 'series_id' => $sid]);
    $episodes = $info['episodes'] ?? [];
    if (!is_array($episodes)) continue;
    $per = [];
    // A map of season -> episodes, or (some servers) one flat list of episodes carrying "season".
    $groups = tvq_is_list($episodes) && isset($episodes[0]) && is_array($episodes[0]) && isset($episodes[0]['season']) ? ['' => $episodes] : $episodes;
    foreach ($groups as $key => $group) {
      if (!is_array($group)) continue;
      foreach ($group as $i => $ep) {
        if (!is_array($ep)) continue;
        $n = (int)($ep['season'] ?? (is_numeric($key) ? $key : 0));
        if ($n > 0) $per[$n][(string)($ep['episode_num'] ?? ($ep['id'] ?? $i))] = true;
      }
    }
    foreach ($per as $n => $eps) $counts[$n] = max($counts[$n] ?? 0, count($eps));
  }
  return $counts;
}
/**
 * Adds what the customer's Flix Town catalog already has to customer-safe details: a movie there
 * becomes "local" (In Flix Town); a season whose episodes are all there becomes "local". Nothing
 * "local" can be requested. A season only partly there stays requestable.
 */
function tvq_apply_flix(array $d, string $username, int $maxAge): array {
  if ($d['type'] === 'movie') {
    if (tvq_flix_has_movie($username, $d['id'], $d['year'], $maxAge)) { $d['state'] = 'local'; $d['can_request'] = false; }
    return $d;
  }
  $have = tvq_flix_season_episodes($username, $d['id'], $d['year'], $maxAge);
  foreach ($d['seasons'] as &$s) {
    $in = $have[$s['number']] ?? 0;
    if ($in > 0 && $in >= $s['episodes'] && $s['state'] !== 'available') $s['state'] = 'local';
  }
  unset($s);
  $states = array_column($d['seasons'], 'state');
  if ($states && !array_diff($states, ['available', 'local']) && in_array('local', $states, true)) $d['state'] = 'local';
  elseif (in_array('local', $states, true) && $d['state'] === 'requestable') $d['state'] = 'partial';
  $d['can_request'] = (bool)array_filter($d['seasons'], fn($s) => tvq_can($s['state']));
  return $d;
}

/* ---------------- Seerr ---------------- */

/**
 * One Seerr call. $asRequester adds X-API-User so Seerr acts as the dedicated requester user;
 * without it the API key acts as the Seerr administrator (used only for reads).
 * Returns ['ok','status','data','error'] like seerr_api(); tests replace $GLOBALS['tvq_seerr_http'].
 */
function tvq_seerr(string $method, string $path, array $query = [], ?array $body = null, bool $asRequester = false): array {
  if (isset($GLOBALS['tvq_seerr_http']) && is_callable($GLOBALS['tvq_seerr_http']))
    return ($GLOBALS['tvq_seerr_http'])($method, $path, $query, $body, $asRequester ? tvq_config()['requester'] : 0);
  $c = tvq_config()['seerr'];
  $url = $c['base_url'] . '/api/v1' . $path . ($query ? '?' . http_build_query($query, '', '&', PHP_QUERY_RFC3986) : '');
  $headers = ['Accept: application/json', 'X-Api-Key: ' . $c['api_key']];
  if ($asRequester) $headers[] = 'X-API-User: ' . tvq_config()['requester'];
  $ch = curl_init($url);
  $opts = [CURLOPT_RETURNTRANSFER => true, CURLOPT_CUSTOMREQUEST => $method, CURLOPT_CONNECTTIMEOUT => $c['connect_timeout'],
    CURLOPT_TIMEOUT => min(15, $c['timeout']), CURLOPT_FOLLOWLOCATION => false, CURLOPT_USERAGENT => 'OnePanel/1.0 (tv-requests)',
    CURLOPT_SSL_VERIFYPEER => $c['verify_ssl'], CURLOPT_SSL_VERIFYHOST => $c['verify_ssl'] ? 2 : 0];
  if ($body !== null) { $headers[] = 'Content-Type: application/json'; $opts[CURLOPT_POSTFIELDS] = json_encode($body); }
  $opts[CURLOPT_HTTPHEADER] = $headers;
  curl_setopt_array($ch, $opts);
  $raw = curl_exec($ch);
  $res = seerr_parse_response($ch, $raw);
  curl_close($ch);
  return $res;
}
/** A read that must succeed; any failure (down, timeout, malformed) becomes "temporarily unavailable". */
function tvq_read(string $path, array $query = []): array {
  $r = tvq_seerr('GET', $path, $query);
  if (!$r['ok'] || !is_array($r['data'])) tvq_unavailable('Seerr read ' . preg_replace('/\d+/', 'N', $path) . ' failed (HTTP ' . (int)$r['status'] . ')');
  return $r['data'];
}
/**
 * The requester user must be able to request and must NOT be able to approve anything itself.
 * Read from Seerr before every submission; any doubt fails closed.
 */
function tvq_check_requester(string $type): void {
  // Read fresh before every submission (never cached): if someone later gives this Seerr user an
  // approving permission, requests stop at once instead of being auto-approved.
  $id = tvq_config()['requester'];
  $u = tvq_seerr('GET', '/user/' . $id);
  if (!$u['ok'] || !is_array($u['data']) || !isset($u['data']['permissions'])) tvq_unavailable('cannot read requester user ' . $id);
  $perms = (int)$u['data']['permissions'];
  $approves = TVQ_P_ADMIN | TVQ_P_MANAGE_REQUESTS | TVQ_P_AUTO_APPROVE | TVQ_P_AUTO_APPROVE_MOVIE | TVQ_P_AUTO_APPROVE_TV;
  if ($perms & $approves) tvq_unavailable('requester user ' . $id . ' can approve requests (ADMIN/MANAGE_REQUESTS/AUTO_APPROVE); refusing to submit');
  $need = $type === 'tv' ? (TVQ_P_REQUEST | TVQ_P_REQUEST_TV) : (TVQ_P_REQUEST | TVQ_P_REQUEST_MOVIE);
  if (!($perms & $need)) tvq_unavailable('requester user ' . $id . ' has no ' . $type . ' request permission');
}

/* ---------------- sanitising ---------------- */

function tvq_img($path, string $size): string {
  $p = (string)$path;
  return preg_match('#^/[A-Za-z0-9._-]{1,80}\.(jpg|jpeg|png|webp)$#i', $p) ? TVQ_IMG . $size . $p : '';
}
function tvq_year($date): string { return preg_match('/^(\d{4})-\d{2}-\d{2}/', (string)$date, $m) ? $m[1] : ''; }
function tvq_text($s, int $max): string {
  $s = trim(preg_replace('/\s+/u', ' ', strip_tags((string)$s)));
  if (function_exists('mb_strlen') && mb_strlen($s) > $max) return rtrim(mb_substr($s, 0, $max - 1)) . '…';
  return strlen($s) > $max ? substr($s, 0, $max) : $s;
}
/** List-level state from the media availability alone (search results carry no requests). */
function tvq_media_state(int $status): string {
  switch ($status) {
    case TVQ_MEDIA_AVAILABLE: return 'available';
    case TVQ_MEDIA_PARTIAL: return 'partial';
    case TVQ_MEDIA_PROCESSING: return 'approved';
    case TVQ_MEDIA_PENDING: return 'pending';
    case TVQ_MEDIA_BLOCKLISTED: return 'unavailable';
  }
  return 'requestable';
}
/** Standard (non-4K) requests of a media item, as plain [status, seasons[]] pairs (no user data). */
function tvq_requests(?array $mediaInfo): array {
  $out = [];
  foreach ((array)($mediaInfo['requests'] ?? []) as $r) {
    if (!is_array($r) || !empty($r['is4k'])) continue;
    $seasons = [];
    foreach ((array)($r['seasons'] ?? []) as $s) if (isset($s['seasonNumber'])) $seasons[] = (int)$s['seasonNumber'];
    $out[] = ['status' => (int)($r['status'] ?? 0), 'seasons' => $seasons, 'id' => (int)($r['id'] ?? 0)];
  }
  return $out;
}
/** Movie: available / partial / approved / pending / declined / unavailable / requestable. */
function tvq_movie_state(?array $mediaInfo): string {
  $ms = (int)($mediaInfo['status'] ?? TVQ_MEDIA_UNKNOWN);
  if ($ms === TVQ_MEDIA_AVAILABLE) return 'available';
  if ($ms === TVQ_MEDIA_PARTIAL) return 'partial';
  if ($ms === TVQ_MEDIA_BLOCKLISTED) return 'unavailable';
  $reqs = tvq_requests($mediaInfo); $st = array_column($reqs, 'status');
  if (in_array(TVQ_REQ_APPROVED, $st, true) || in_array(TVQ_REQ_FAILED, $st, true) || $ms === TVQ_MEDIA_PROCESSING) return 'approved';
  if (in_array(TVQ_REQ_PENDING, $st, true) || $ms === TVQ_MEDIA_PENDING) return 'pending';
  if (in_array(TVQ_REQ_DECLINED, $st, true)) return 'declined';
  return 'requestable';
}
/** TV: one state per real season (specials and empty seasons are skipped), plus the series state. */
function tvq_tv_seasons(array $tv): array {
  $info = is_array($tv['mediaInfo'] ?? null) ? $tv['mediaInfo'] : null;
  $mediaSeason = [];
  foreach ((array)($info['seasons'] ?? []) as $s) if (isset($s['seasonNumber'])) $mediaSeason[(int)$s['seasonNumber']] = (int)($s['status'] ?? TVQ_MEDIA_UNKNOWN);
  $byReq = [];
  foreach (tvq_requests($info) as $r) foreach ($r['seasons'] as $n) $byReq[$n][] = $r['status'];
  $out = [];
  foreach ((array)($tv['seasons'] ?? []) as $s) {
    $n = (int)($s['seasonNumber'] ?? 0);
    if ($n <= 0 || (int)($s['episodeCount'] ?? 0) <= 0) continue;
    $ms = $mediaSeason[$n] ?? TVQ_MEDIA_UNKNOWN; $rs = $byReq[$n] ?? [];
    if ($ms === TVQ_MEDIA_AVAILABLE) $state = 'available';
    elseif ($ms === TVQ_MEDIA_PARTIAL) $state = 'partial';
    elseif (in_array(TVQ_REQ_APPROVED, $rs, true) || in_array(TVQ_REQ_FAILED, $rs, true) || $ms === TVQ_MEDIA_PROCESSING) $state = 'approved';
    elseif (in_array(TVQ_REQ_PENDING, $rs, true) || $ms === TVQ_MEDIA_PENDING) $state = 'pending';
    elseif (in_array(TVQ_REQ_DECLINED, $rs, true)) $state = 'declined';
    else $state = 'requestable';
    $out[] = ['number' => $n, 'name' => tvq_text($s['name'] ?? ('Season ' . $n), 60), 'episodes' => (int)$s['episodeCount'], 'state' => $state];
  }
  return $out;
}
function tvq_series_state(array $seasons, ?array $info): string {
  if ((int)($info['status'] ?? 0) === TVQ_MEDIA_BLOCKLISTED) return 'unavailable';
  $states = array_column($seasons, 'state');
  if (!$states) return tvq_media_state((int)($info['status'] ?? TVQ_MEDIA_UNKNOWN));
  if (count(array_filter($states, fn($s) => $s === 'available')) === count($states)) return 'available';
  // Some seasons on the server but not all: never shown as fully available.
  if (array_intersect($states, ['available', 'partial'])) return 'partial';
  foreach (['approved', 'pending', 'declined'] as $s) if (in_array($s, $states, true) && !in_array('requestable', $states, true)) return $s;
  return 'requestable';
}
function tvq_can(string $state): bool { return $state === 'requestable' || ($state === 'declined' && tvq_config()['rerequest_declined']); }

/** Customer-safe search results: only movies and series, only public fields. */
function tvq_search(string $query, int $page, string $username = ''): array {
  $d = tvq_read('/search', ['query' => $query, 'page' => $page, 'language' => 'en']);
  if (!isset($d['results']) || !is_array($d['results'])) tvq_unavailable('malformed search answer');
  $out = [];
  foreach ($d['results'] as $r) {
    if (!is_array($r)) continue;
    $type = (string)($r['mediaType'] ?? '');
    if ($type !== 'movie' && $type !== 'tv') continue;
    $id = (int)($r['id'] ?? 0);
    if ($id <= 0) continue;
    $state = tvq_media_state((int)($r['mediaInfo']['status'] ?? TVQ_MEDIA_UNKNOWN));
    $out[] = ['type' => $type, 'id' => $id, 'title' => tvq_text($type === 'tv' ? ($r['name'] ?? '') : ($r['title'] ?? ''), 120),
      'year' => tvq_year($type === 'tv' ? ($r['firstAirDate'] ?? '') : ($r['releaseDate'] ?? '')),
      'poster' => tvq_img($r['posterPath'] ?? '', 'w342'), 'state' => $state];
  }
  // Movies already in this customer's Flix Town catalog show as such (best effort: a catalog
  // problem never breaks search; the request itself is always checked fresh).
  if ($username !== '' && array_filter($out, fn($x) => $x['type'] === 'movie')) {
    try {
      $index = tvq_flix_index(tvq_flix_line($username), 'movie', 600);
      foreach ($out as &$x) if ($x['type'] === 'movie' && tvq_flix_matches($index, $x['id'], $x['year'])) $x['state'] = 'local';
      unset($x);
    } catch (TvqException $e) { /* logged by tvq_unavailable */ }
  }
  return ['page' => max(1, (int)($d['page'] ?? $page)), 'pages' => max(1, min(10, (int)($d['totalPages'] ?? 1))), 'results' => $out];
}
/** Customer-safe details, with season states for series. */
function tvq_details(string $type, int $id, string $username = '', int $flixAge = 600, bool $strict = false): array {
  $d = tvq_read('/' . $type . '/' . $id, ['language' => 'en']);
  if ((int)($d['id'] ?? 0) !== $id) tvq_unavailable('details answer for a different id');
  $info = is_array($d['mediaInfo'] ?? null) ? $d['mediaInfo'] : null;
  $out = ['type' => $type, 'id' => $id, 'title' => tvq_text($type === 'tv' ? ($d['name'] ?? '') : ($d['title'] ?? ''), 120),
    'year' => tvq_year($type === 'tv' ? ($d['firstAirDate'] ?? '') : ($d['releaseDate'] ?? '')),
    'overview' => tvq_text($d['overview'] ?? '', 900), 'poster' => tvq_img($d['posterPath'] ?? '', 'w342'),
    'backdrop' => tvq_img($d['backdropPath'] ?? '', 'w780')];
  if ($type === 'tv') {
    $out['seasons'] = tvq_tv_seasons($d);
    $out['state'] = tvq_series_state($out['seasons'], $info);
    $out['can_request'] = (bool)array_filter($out['seasons'], fn($s) => tvq_can($s['state']));
  } else {
    $out['state'] = tvq_movie_state($info);
    $out['can_request'] = tvq_can($out['state']);
  }
  if ($username === '') return $out;
  // Browsing tolerates a catalog problem (the answer says it was not checked); a request does not.
  try { return tvq_apply_flix($out, $username, $flixAge) + ['flix_checked' => true]; }
  catch (TvqException $e) { if ($strict) throw $e; return $out + ['flix_checked' => false]; }
}

/**
 * Creates one Pending request as the dedicated requester and records it for the customer.
 * Duplicates are refused here (inside a per-title lock) before Seerr is asked.
 */
function tvq_request(PDO $pdo, string $username, string $type, int $id, array $seasons): array {
  $cfg = tvq_config();
  // One submission at a time per customer (limits stay exact), then one at a time per title.
  $userLock = tvq_lock($pdo, 'user:' . $username);
  try { return tvq_request_locked($pdo, $username, $type, $id, $seasons, $cfg); } finally { tvq_unlock($pdo, $userLock); }
}
function tvq_request_locked(PDO $pdo, string $username, string $type, int $id, array $seasons, array $cfg): array {
  $today = (int)(floor(time() / 86400) * 86400);
  $q = $pdo->prepare('SELECT COUNT(*) FROM tv_media_requests WHERE username=? AND created_ts>=?'); $q->execute([$username, $today]);
  $n = (int)$q->fetchColumn(); $q->closeCursor();
  if ($n >= $cfg['daily_limit']) tvq_fail(429, 'daily_limit', 'You have reached today\'s request limit. Please try again tomorrow.');
  $q = $pdo->prepare('SELECT COUNT(*) FROM tv_media_requests WHERE username=? AND last_status=?'); $q->execute([$username, TVQ_REQ_PENDING]);
  $n = (int)$q->fetchColumn(); $q->closeCursor();
  if ($n >= $cfg['open_limit']) tvq_fail(429, 'open_limit', 'You already have several requests waiting for approval.');

  tvq_check_requester($type);
  $lock = tvq_lock($pdo, 'title:' . $type . ':' . $id);
  try {
    // Fresh, inside the lock: Seerr's state and the customer's Flix Town catalog (at most 2 minutes old).
    $d = tvq_details($type, $id, $username, 120, true);
    tvq_check_own_records($pdo, $type, $id, $seasons);
    if ($type === 'movie') {
      if (!$d['can_request']) tvq_fail(409, 'duplicate', tvq_state_message($d['state']));
      $body = ['mediaType' => 'movie', 'mediaId' => $id, 'is4k' => false];
    } else {
      $byNumber = []; foreach ($d['seasons'] as $s) $byNumber[$s['number']] = $s['state'];
      foreach ($seasons as $n) {
        if (!isset($byNumber[$n])) tvq_fail(400, 'season', 'That season is not available to request.');
        if (!tvq_can($byNumber[$n])) tvq_fail(409, 'duplicate', 'Season ' . $n . ': ' . tvq_state_message($byNumber[$n]));
      }
      $body = ['mediaType' => 'tv', 'mediaId' => $id, 'seasons' => array_values($seasons), 'is4k' => false];
    }
    $r = tvq_seerr('POST', '/request', [], $body, true);
    if ((int)$r['status'] === 409 || (int)$r['status'] === 202) tvq_fail(409, 'duplicate', 'This was already requested.');
    if ((int)$r['status'] === 403) tvq_unavailable('Seerr refused the request (permission, quota or blocklist)');
    if (!$r['ok'] || !is_array($r['data']) || (int)($r['data']['id'] ?? 0) <= 0) tvq_unavailable('Seerr request failed (HTTP ' . (int)$r['status'] . ')');
    $status = (int)($r['data']['status'] ?? 0);
    if ($status !== TVQ_REQ_PENDING) error_log('tv-requests ALERT: Seerr request ' . (int)$r['data']['id'] . ' came back with status ' . $status . ' instead of Pending; check the requester user permissions.');
    // The request now exists in Seerr: record its owner, retrying a busy database; if that still
    // fails, the customer is told it worked (it did) and the server log names the request to link.
    $saved = false;
    for ($try = 0; $try < 3 && !$saved; $try++) {
      try {
        $pdo->prepare('INSERT INTO tv_media_requests(username,seerr_request_id,media_type,tmdb_id,seasons,title,year,poster,created_ts,last_status,last_media_status,last_checked_ts) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)')
            ->execute([$username, (int)$r['data']['id'], $type, $id, $type === 'tv' ? implode(',', $seasons) : '', $d['title'], $d['year'], $d['poster'], time(), $status, TVQ_MEDIA_PENDING, time()]);
        $saved = true;
      } catch (PDOException $e) { error_log('tv-requests: owner insert retry (database busy, SQLSTATE ' . $e->getCode() . ')'); usleep(200000); }
    }
    if (!$saved) error_log('tv-requests ALERT: Seerr request ' . (int)$r['data']['id'] . ' was created but its owner could not be recorded (tv_media_requests).');
    return ['request' => ['type' => $type, 'id' => $id, 'title' => $d['title'], 'seasons' => $type === 'tv' ? array_values($seasons) : [],
      'state' => tvq_history_state($status, TVQ_MEDIA_PENDING)]];
  } finally { tvq_unlock($pdo, $lock); }
}
/**
 * A second guard from OnePanel's own records: a request of any customer for this movie, or for
 * one of these seasons, that is still pending or approved is a duplicate (even if Seerr has not
 * caught up yet). Runs inside the title lock.
 */
function tvq_check_own_records(PDO $pdo, string $type, int $id, array $seasons): void {
  $q = $pdo->prepare('SELECT seasons FROM tv_media_requests WHERE media_type=? AND tmdb_id=? AND last_status IN (?,?)');
  $q->execute([$type, $id, TVQ_REQ_PENDING, TVQ_REQ_APPROVED]); $rows = $q->fetchAll(PDO::FETCH_COLUMN); $q->closeCursor();
  if (!$rows) return;
  if ($type === 'movie') tvq_fail(409, 'duplicate', tvq_state_message('pending'));
  foreach ($rows as $list) foreach (array_filter(explode(',', (string)$list), 'strlen') as $n)
    if (in_array((int)$n, $seasons, true)) tvq_fail(409, 'duplicate', 'Season ' . (int)$n . ': ' . tvq_state_message('pending'));
}
function tvq_state_message(string $state): string {
  $m = ['available' => 'This is already available.', 'partial' => 'This is already partly available.', 'approved' => 'This was already approved and is being prepared.',
    'pending' => 'This was already requested and is waiting for approval.', 'declined' => 'This request was declined.', 'unavailable' => 'This title cannot be requested.', 'local' => 'This is already in Flix Town.'];
  return $m[$state] ?? 'This cannot be requested.';
}
function tvq_history_state(int $req, int $media): string {
  if ($req === 0) return 'removed';
  if ($req === TVQ_REQ_DECLINED) return 'declined';
  if ($req === TVQ_REQ_COMPLETED || $media === TVQ_MEDIA_AVAILABLE) return 'available';
  if ($media === TVQ_MEDIA_PARTIAL) return 'partial';
  if ($req === TVQ_REQ_APPROVED || $req === TVQ_REQ_FAILED) return 'approved';
  return 'pending';
}
/** This customer's own requests only, newest first; statuses refreshed from Seerr at most every 5 minutes. */
function tvq_history(PDO $pdo, string $username, int $page): array {
  $per = 20; $off = ($page - 1) * $per;
  $q = $pdo->prepare('SELECT * FROM tv_media_requests WHERE username=? ORDER BY id DESC LIMIT ' . ($per + 1) . ' OFFSET ' . $off);
  $q->execute([$username]); $rows = $q->fetchAll(); $q->closeCursor();
  $more = count($rows) > $per; $rows = array_slice($rows, 0, $per);
  $out = [];
  foreach ($rows as $row) {
    if ((int)$row['last_checked_ts'] < time() - 300 && (int)$row['last_status'] !== 0) {
      $r = tvq_seerr('GET', '/request/' . (int)$row['seerr_request_id']);
      if ($r['ok'] && is_array($r['data'])) {
        $row['last_status'] = (int)($r['data']['status'] ?? $row['last_status']);
        $row['last_media_status'] = (int)($r['data']['media']['status'] ?? $row['last_media_status']);
      } elseif ((int)$r['status'] === 404) { $row['last_status'] = 0; }
      // Seerr down: keep the last known status (the list still shows).
      if ($r['ok'] || (int)$r['status'] === 404)
        $pdo->prepare('UPDATE tv_media_requests SET last_status=?,last_media_status=?,last_checked_ts=? WHERE id=?')
            ->execute([(int)$row['last_status'], (int)$row['last_media_status'], time(), (int)$row['id']]);
    }
    $out[] = ['type' => $row['media_type'], 'id' => (int)$row['tmdb_id'], 'title' => (string)$row['title'], 'year' => (string)$row['year'],
      'poster' => (string)$row['poster'], 'seasons' => $row['seasons'] === '' ? [] : array_map('intval', explode(',', $row['seasons'])),
      'requested_at' => (int)$row['created_ts'], 'state' => tvq_history_state((int)$row['last_status'], (int)$row['last_media_status'])];
  }
  return ['page' => $page, 'has_more' => $more, 'requests' => $out];
}

}
