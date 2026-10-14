<?php
/*
 * Test stand-in for OnePanel's includes/bootstrap.php: a real MySQL database opened exactly as
 * OnePanel opens it (same DSN form and PDO options), a fake Flix Town adapter and player API
 * (catalog), and a fake Seerr that follows the verified Seerr rules (server/middleware/auth.ts, entity/MediaRequest.ts):
 * an API-key call acts as user 1 unless X-API-User names another user; a request is approved at
 * once when the acting user has ADMIN/MANAGE_REQUESTS/AUTO_APPROVE*, otherwise Pending.
 * All state lives in one JSON file (TVQ_STATE) shared by the test and the php -S workers.
 */
$config = [
  'db' => ['pass' => 'test'], 'app' => ['base_url' => 'http://test'],
  'services' => [
    'flix_town' => ['base_url' => 'http://127.0.0.1:9', 'token' => 'FLIX-ADMIN-TOKEN-SECRET'],
    'seerr' => ['enabled' => true, 'base_url' => 'https://requests.example.test', 'api_key' => 'SEERR-API-KEY-SECRET-0123456789',
      'actions_enabled' => false, 'customer_requests_enabled' => getenv('TVQ_OFF') ? false : true,
      'requester_user_id' => (int)(getenv('TVQ_REQUESTER') ?: 7), 'customer_session_secret' => str_repeat('s', 40),
      'customer_daily_limit' => 5, 'customer_open_limit' => 10],
  ],
];
date_default_timezone_set('America/Denver');
// OnePanel includes/bootstrap.php: mysql:host=..;dbname=..;charset=utf8mb4 with these options.
$pdo = new PDO(sprintf('mysql:host=%s;port=%s;dbname=%s;charset=%s', getenv('TVQ_MYSQL_HOST'), getenv('TVQ_MYSQL_PORT'), getenv('TVQ_MYSQL_DB'), 'utf8mb4'),
  getenv('TVQ_MYSQL_USER'), getenv('TVQ_MYSQL_PASS'), [
  PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
  PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
  PDO::ATTR_EMULATE_PREPARES => false,
]);
function onepanel_storage_dir(): string { $d = getenv('TVQ_STORAGE'); if (!is_dir($d)) @mkdir($d, 0777, true); return $d; }

function tvq_test_state(?callable $change = null): array {
  $h = fopen(getenv('TVQ_STATE'), 'c+'); flock($h, LOCK_EX);
  $s = json_decode(stream_get_contents($h) ?: '{}', true) ?: [];
  if ($change) { $s = $change($s); ftruncate($h, 0); rewind($h); fwrite($h, json_encode($s)); }
  flock($h, LOCK_UN); fclose($h); return $s;
}
function flix_api_request(string $method, string $path, ?array $payload = null): array {
  $s = tvq_test_state();
  if (!empty($s['flix_down'])) return ['ok' => false, 'status' => 0, 'data' => null, 'error' => 'down'];
  if (!preg_match('#^/api/onepanel/users/([^/]+)$#', $path, $m)) return ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'no'];
  $u = $s['flix'][rawurldecode($m[1])] ?? null;
  if (!$u) return ['ok' => false, 'status' => 404, 'data' => ['detail' => 'not found'], 'error' => 'not found'];
  return ['ok' => true, 'status' => 200, 'data' => ['success' => true, 'user' => $u], 'error' => ''];
}

/**
 * The fake Flix Town player API (catalog). Answers get_vod_streams / get_series / get_series_info
 * for the line whose password matches the adapter's record; records each call without the password.
 */
$GLOBALS['tvq_player_http'] = function (array $q) {
  $out = null;
  tvq_test_state(function ($s) use ($q, &$out) {
    $u = $s['flix'][$q['username']] ?? null;
    $okLine = $u && ($u['password'] ?? null) === $q['password'] || (($s['catalog_line'] ?? null) === [$q['username'], $q['password']]);
    $s['player_calls'][] = ['action' => $q['action'] ?? '', 'username' => $q['username'], 'own_password' => (bool)$okLine, 'series_id' => $q['series_id'] ?? null];
    if (!empty($s['player_down']) || !$okLine) return $s;   // null = unreadable / refused
    $c = $s['flix_catalog'] ?? [];
    if ($q['action'] === 'get_vod_streams') $out = $c['vod'] ?? [];
    elseif ($q['action'] === 'get_series') $out = $c['series'] ?? [];
    elseif ($q['action'] === 'get_series_info') $out = $c['info'][(string)$q['series_id']] ?? ['episodes' => []];
    return $s;
  });
  return $out;
};

/** The fake Seerr. Delay/outage/malformed modes come from the state file. */
$GLOBALS['tvq_seerr_http'] = function (string $method, string $path, array $query, ?array $body, int $asUser) {
  $out = null;
  tvq_test_state(function ($s) use ($method, $path, $query, $body, $asUser, &$out) {
    $s['calls'][] = ['m' => $method, 'p' => $path, 'user' => $asUser, 'body' => $body];
    $mode = (string)($s['seerr_mode'] ?? 'ok');
    if ($mode === 'down') { $out = ['ok' => false, 'status' => 0, 'data' => null, 'error' => 'Seerr could not be reached (Connection refused).']; return $s; }
    if ($mode === 'timeout') { $out = ['ok' => false, 'status' => 0, 'data' => null, 'error' => 'Seerr could not be reached (Operation timed out).']; return $s; }
    if ($mode === '500') { $out = ['ok' => false, 'status' => 500, 'data' => ['message' => 'boom'], 'error' => 'Seerr returned HTTP 500.']; return $s; }
    if ($mode === 'malformed') { $out = ['ok' => true, 'status' => 200, 'data' => ['unexpected' => true], 'error' => '']; return $s; }
    $acting = $asUser ?: 1;   // API key without X-API-User = administrator user 1
    if ($method === 'GET' && $path === '/search') {
      $q = strtolower($query['query'] ?? '');
      $res = array_values(array_filter($s['catalog'], fn($c) => strpos(strtolower($c['title'] ?? $c['name'] ?? ''), $q) !== false));
      $res = array_map(function ($c) use ($s) {
        $r = $c; unset($r['seasons'], $r['overview_full']);
        $mk = $c['mediaType'] . ':' . $c['id'];
        if (isset($s['media'][$mk])) $r['mediaInfo'] = ['status' => $s['media'][$mk]['status'], 'id' => 99];
        return $r;
      }, $res);
      $res[] = ['mediaType' => 'person', 'id' => 5, 'name' => 'Some Person ' . $q];
      $out = ['ok' => true, 'status' => 200, 'data' => ['page' => (int)$query['page'], 'totalPages' => 3, 'totalResults' => count($res), 'results' => $res], 'error' => '']; return $s;
    }
    if ($method === 'GET' && preg_match('#^/(movie|tv)/(\d+)$#', $path, $m)) {
      $key = $m[1] . ':' . $m[2];
      $c = null; foreach ($s['catalog'] as $x) if ($x['mediaType'] . ':' . $x['id'] === $key) $c = $x;
      if (!$c) { $out = ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'Seerr returned HTTP 404.']; return $s; }
      $d = $c; $d['overview'] = $c['overview_full'] ?? 'Overview'; unset($d['overview_full']);
      if ($m[1] === 'tv') $d['seasons'] = $c['seasons'];
      if (isset($s['media'][$key])) {
        $media = $s['media'][$key];
        $reqs = [];
        foreach ($s['requests'] ?? [] as $r) if ($r['key'] === $key) $reqs[] = ['id' => $r['id'], 'status' => $r['status'], 'is4k' => false,
          'seasons' => array_map(fn($n) => ['seasonNumber' => $n], $r['seasons']),
          'requestedBy' => ['id' => $r['user'], 'email' => 'secret-user@example.test', 'plexToken' => 'TOKEN-XYZ']];
        $d['mediaInfo'] = ['status' => $media['status'], 'seasons' => array_map(fn($n, $st) => ['seasonNumber' => (int)$n, 'status' => $st], array_keys($media['seasons'] ?? []), $media['seasons'] ?? []), 'requests' => $reqs];
      }
      $out = ['ok' => true, 'status' => 200, 'data' => $d, 'error' => '']; return $s;
    }
    if ($method === 'GET' && preg_match('#^/user/(\d+)$#', $path, $m)) {
      $u = $s['users'][$m[1]] ?? null;
      $out = $u ? ['ok' => true, 'status' => 200, 'data' => ['id' => (int)$m[1], 'permissions' => $u['permissions'], 'email' => 'requester@example.test'], 'error' => '']
                : ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'Seerr returned HTTP 404.'];
      return $s;
    }
    if ($method === 'POST' && $path === '/request') {
      $key = $body['mediaType'] . ':' . $body['mediaId'];
      $perms = $s['users'][(string)$acting]['permissions'] ?? 2;
      $auto = ($perms & (2 | 16 | 128 | ($body['mediaType'] === 'tv' ? 512 : 256))) !== 0;
      if (!empty($s['slow_post'])) usleep(400000);
      // Seerr's own duplicate rule for movies (first existing request not declined/completed)
      foreach ($s['requests'] ?? [] as $r) if ($r['key'] === $key && $body['mediaType'] === 'movie' && !in_array($r['status'], [3, 5], true)) {
        $out = ['ok' => false, 'status' => 409, 'data' => ['message' => 'Request for this media already exists.'], 'error' => 'Seerr returned HTTP 409.']; return $s; }
      $id = ($s['next_id'] ?? 100) + 1; $s['next_id'] = $id;
      $s['requests'][] = ['id' => $id, 'key' => $key, 'status' => $auto ? 2 : 1, 'user' => $acting, 'seasons' => $body['seasons'] ?? []];
      if (!isset($s['media'][$key])) $s['media'][$key] = ['status' => $auto ? 3 : 2, 'seasons' => []];
      $s['posts'][] = ['key' => $key, 'user' => $acting, 'seasons' => $body['seasons'] ?? [], 'is4k' => $body['is4k'] ?? null];
      $out = ['ok' => true, 'status' => 201, 'data' => ['id' => $id, 'status' => $auto ? 2 : 1, 'type' => $body['mediaType'],
        'requestedBy' => ['id' => $acting, 'email' => 'secret-user@example.test'], 'media' => ['status' => 2]], 'error' => '']; return $s;
    }
    if ($method === 'GET' && preg_match('#^/request/(\d+)$#', $path, $m)) {
      foreach ($s['requests'] ?? [] as $r) if ($r['id'] === (int)$m[1]) {
        $out = ['ok' => true, 'status' => 200, 'data' => ['id' => $r['id'], 'status' => $r['status'], 'media' => ['status' => $s['media'][$r['key']]['status'] ?? 1],
          'requestedBy' => ['id' => $r['user'], 'email' => 'secret-user@example.test']], 'error' => '']; return $s; }
      $out = ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'Seerr returned HTTP 404.']; return $s;
    }
    $out = ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'Seerr returned HTTP 404.']; return $s;
  });
  return $out;
};
