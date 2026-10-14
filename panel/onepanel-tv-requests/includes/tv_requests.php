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
  // Safe when several requests count the same key at once: insert-or-increment, never select-then-insert.
  $now = time(); $key = substr($key, 0, 120);
  $pdo->prepare('DELETE FROM tv_request_rate WHERE k=? AND window_start<=?')->execute([$key, $now - $window]);
  try { $pdo->prepare('INSERT INTO tv_request_rate(k,window_start,hits) VALUES(?,?,1)')->execute([$key, $now]); return true; }
  catch (PDOException $e) { /* the key exists: count this hit */ }
  $pdo->prepare('UPDATE tv_request_rate SET hits=hits+1 WHERE k=?')->execute([$key]);
  $q = $pdo->prepare('SELECT hits FROM tv_request_rate WHERE k=?'); $q->execute([$key]);
  $hits = (int)$q->fetchColumn(); $q->closeCursor();
  return $hits <= $max;
}
/** Exclusive lock per title (file lock), so two customers cannot request the same title at once. */
function tvq_lock(string $name) {
  $dir = onepanel_storage_dir() . '/locks';
  if (!is_dir($dir)) @mkdir($dir, 0750, true);
  $h = @fopen($dir . '/tvq-' . preg_replace('/[^a-z0-9_-]/', '', strtolower($name)) . '.lock', 'c');
  if (!$h) tvq_unavailable('cannot create lock file');
  $waited = 0;
  while (!flock($h, LOCK_EX | LOCK_NB)) { if (($waited += 100) > 15000) { fclose($h); tvq_fail(429, 'busy', 'Please try again in a moment.'); } usleep(100000); }
  return $h;
}
function tvq_unlock($h): void { if ($h) { flock($h, LOCK_UN); fclose($h); } }

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
 * Cached for 10 minutes; any doubt fails closed.
 */
function tvq_check_requester(string $type): void {
  static $ok = [];
  $id = tvq_config()['requester'];
  $cacheKey = $id . ':' . $type;
  if (isset($ok[$cacheKey])) return;
  $cache = onepanel_storage_dir() . '/tv-requests-requester.json';
  $c = is_file($cache) ? json_decode((string)@file_get_contents($cache), true) : null;
  if (is_array($c) && ($c['id'] ?? 0) === $id && ($c['t'] ?? 0) > time() - 600) $perms = (int)$c['perms'];
  else {
    $u = tvq_seerr('GET', '/user/' . $id);
    if (!$u['ok'] || !is_array($u['data']) || !isset($u['data']['permissions'])) tvq_unavailable('cannot read requester user ' . $id);
    $perms = (int)$u['data']['permissions'];
    @file_put_contents($cache, json_encode(['id' => $id, 'perms' => $perms, 't' => time()]), LOCK_EX);
  }
  $approves = TVQ_P_ADMIN | TVQ_P_MANAGE_REQUESTS | TVQ_P_AUTO_APPROVE | TVQ_P_AUTO_APPROVE_MOVIE | TVQ_P_AUTO_APPROVE_TV;
  if ($perms & $approves) tvq_unavailable('requester user ' . $id . ' can approve requests (ADMIN/MANAGE_REQUESTS/AUTO_APPROVE); refusing to submit');
  $need = $type === 'tv' ? (TVQ_P_REQUEST | TVQ_P_REQUEST_TV) : (TVQ_P_REQUEST | TVQ_P_REQUEST_MOVIE);
  if (!($perms & $need)) tvq_unavailable('requester user ' . $id . ' has no ' . $type . ' request permission');
  $ok[$cacheKey] = true;
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
function tvq_search(string $query, int $page): array {
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
  return ['page' => max(1, (int)($d['page'] ?? $page)), 'pages' => max(1, min(10, (int)($d['totalPages'] ?? 1))), 'results' => $out];
}
/** Customer-safe details, with season states for series. */
function tvq_details(string $type, int $id): array {
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
  return $out;
}

/**
 * Creates one Pending request as the dedicated requester and records it for the customer.
 * Duplicates are refused here (inside a per-title lock) before Seerr is asked.
 */
function tvq_request(PDO $pdo, string $username, string $type, int $id, array $seasons): array {
  $cfg = tvq_config();
  // One submission at a time per customer (limits stay exact), then one at a time per title.
  $userLock = tvq_lock('u-' . substr(hash('sha256', $username), 0, 20));
  try { return tvq_request_locked($pdo, $username, $type, $id, $seasons, $cfg); } finally { tvq_unlock($userLock); }
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
  $lock = tvq_lock($type . '-' . $id);
  try {
    $d = tvq_details($type, $id);   // fresh, inside the lock
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
      } catch (PDOException $e) { error_log('tv-requests: owner insert retry (database busy)'); usleep(200000); }
    }
    if (!$saved) error_log('tv-requests ALERT: Seerr request ' . (int)$r['data']['id'] . ' was created but its owner could not be recorded (tv_media_requests).');
    return ['request' => ['type' => $type, 'id' => $id, 'title' => $d['title'], 'seasons' => $type === 'tv' ? array_values($seasons) : [],
      'state' => tvq_history_state($status, TVQ_MEDIA_PENDING)]];
  } finally { tvq_unlock($lock); }
}
function tvq_state_message(string $state): string {
  $m = ['available' => 'This is already available.', 'partial' => 'This is already partly available.', 'approved' => 'This was already approved and is being prepared.',
    'pending' => 'This was already requested and is waiting for approval.', 'declined' => 'This request was declined.', 'unavailable' => 'This title cannot be requested.'];
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
