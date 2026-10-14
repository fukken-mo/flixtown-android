<?php
/*
 * OnePanel — Seerr (media requests) client.
 *
 * Isolated on purpose: only media-requests.php and media-requests-action.php load this file.
 * Nothing in customer, provider, payment or sync code calls Seerr, so a Seerr outage
 * can only affect the Media Requests page.
 *
 * Security: the API key is read from config/config.php and is only ever sent server-side
 * in the X-Api-Key request header. It is never echoed, put in a URL, returned to the
 * browser, or written to any log.
 *
 * API reference (Seerr seerr-api.yml, base path /api/v1):
 *   GET  /request?take&skip&filter&sort&sortDirection   list requests (pageInfo + results)
 *   GET  /request/count                                 totals by status
 *   GET  /request/{id}                                  one request
 *   POST /request/{id}/approve | /request/{id}/decline  200 ok · 409 not pending · 404 missing
 *   GET  /movie/{tmdbId} · GET /tv/{tmdbId}             title / name, dates, posterPath
 */

const SEERR_STATUS_PENDING   = 1;
const SEERR_STATUS_APPROVED  = 2;
const SEERR_STATUS_DECLINED  = 3;
const SEERR_STATUS_FAILED    = 4;
const SEERR_STATUS_COMPLETED = 5;

function seerr_config(): array {
  global $config;
  $c = [];
  if (isset($config['services']['seerr']) && is_array($config['services']['seerr'])) $c = $config['services']['seerr'];
  elseif (isset($config['seerr']) && is_array($config['seerr'])) $c = $config['seerr'];
  $base = rtrim(trim((string)($c['base_url'] ?? '')), '/');
  $base = preg_replace('#/api/v1$#i', '', $base);
  return [
    'enabled'         => !isset($c['enabled']) || (bool)$c['enabled'],
    'base_url'        => $base,
    'api_key'         => trim((string)($c['api_key'] ?? '')),
    'connect_timeout' => max(2, (int)($c['connect_timeout'] ?? 5)),
    'timeout'         => max(4, (int)($c['request_timeout'] ?? 15)),
    'verify_ssl'      => !isset($c['verify_ssl']) || (bool)$c['verify_ssl'],
    // Read-only safety switch. Fails closed: only a literal boolean true enables Approve/Decline.
    'actions_enabled' => isset($c['actions_enabled']) && $c['actions_enabled'] === true,
  ];
}

/** True only when config sets 'actions_enabled' => true. Missing, false or anything else = read-only. */
function seerr_actions_enabled(): bool {
  return seerr_config()['actions_enabled'] === true;
}

const SEERR_READ_ONLY_MESSAGE = 'Media Requests is currently in read-only mode.';

/** Returns '' when Seerr is configured, otherwise a human-readable reason (never contains the key). */
function seerr_config_problem(): string {
  $c = seerr_config();
  if (!$c['enabled']) return 'Media Requests is turned off in config/config.php (services → seerr → enabled).';
  if ($c['base_url'] === '' || !preg_match('#^https?://[^/\s]+#i', $c['base_url'])) return 'The Seerr URL is missing or invalid in config/config.php (services → seerr → base_url).';
  if ($c['api_key'] === '' || stripos($c['api_key'], 'PASTE_') === 0 || stripos($c['api_key'], 'YOUR_') === 0) return 'The Seerr API key has not been added to config/config.php yet (services → seerr → api_key).';
  return '';
}

/** Remove anything key-like from a message before it can reach the browser or a log. */
function seerr_scrub(string $msg): string {
  $key = seerr_config()['api_key'];
  if ($key !== '') $msg = str_replace($key, '[hidden]', $msg);
  $msg = trim(preg_replace('/\s+/', ' ', strip_tags($msg)));
  return strlen($msg) > 220 ? substr($msg, 0, 217) . '...' : $msg;
}

function seerr_curl_handle(string $method, string $path, array $query = [], ?array $body = null) {
  $c = seerr_config();
  $url = $c['base_url'] . '/api/v1' . $path . ($query ? '?' . http_build_query($query) : '');
  $headers = ['Accept: application/json', 'X-Api-Key: ' . $c['api_key']];
  $ch = curl_init($url);
  $opts = [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_CUSTOMREQUEST  => strtoupper($method),
    CURLOPT_CONNECTTIMEOUT => $c['connect_timeout'],
    CURLOPT_TIMEOUT        => $c['timeout'],
    CURLOPT_FOLLOWLOCATION => false,
    CURLOPT_USERAGENT      => 'OnePanel/1.0 (media-requests)',
    CURLOPT_SSL_VERIFYPEER => $c['verify_ssl'],
    CURLOPT_SSL_VERIFYHOST => $c['verify_ssl'] ? 2 : 0,
  ];
  if ($body !== null) {
    $headers[] = 'Content-Type: application/json';
    $opts[CURLOPT_POSTFIELDS] = json_encode($body);
  } elseif (strtoupper($method) === 'POST') {
    $opts[CURLOPT_POSTFIELDS] = '';
  }
  $opts[CURLOPT_HTTPHEADER] = $headers;
  curl_setopt_array($ch, $opts);
  return $ch;
}

function seerr_parse_response($ch, $raw): array {
  $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
  if ($raw === false || $raw === null) {
    $err = curl_error($ch);
    return ['ok' => false, 'status' => 0, 'data' => null, 'error' => 'Seerr could not be reached' . ($err !== '' ? ' (' . seerr_scrub($err) . ')' : '') . '.'];
  }
  $data = json_decode((string)$raw, true);
  if ($status >= 200 && $status < 300) {
    if (!is_array($data)) return ['ok' => false, 'status' => $status, 'data' => null, 'error' => 'Seerr returned an unreadable response.'];
    return ['ok' => true, 'status' => $status, 'data' => $data, 'error' => ''];
  }
  $msg = is_array($data) && isset($data['message']) ? seerr_scrub((string)$data['message']) : '';
  if ($status === 401 || $status === 403) $msg = 'Seerr rejected the API key (HTTP ' . $status . '). Check services → seerr → api_key in config/config.php.';
  else $msg = 'Seerr returned HTTP ' . $status . ($msg !== '' ? ': ' . rtrim($msg, '.') : '') . '.';
  return ['ok' => false, 'status' => $status, 'data' => is_array($data) ? $data : null, 'error' => $msg];
}

function seerr_api(string $method, string $path, array $query = [], ?array $body = null): array {
  $problem = seerr_config_problem();
  if ($problem !== '') return ['ok' => false, 'status' => 0, 'data' => null, 'error' => $problem];
  if (!function_exists('curl_init')) return ['ok' => false, 'status' => 0, 'data' => null, 'error' => 'PHP cURL extension is not available.'];
  $ch = seerr_curl_handle($method, $path, $query, $body);
  $raw = curl_exec($ch);
  $res = seerr_parse_response($ch, $raw);
  curl_close($ch);
  return $res;
}

/* ---------------- Media details (title, year, poster) ---------------- */

function seerr_cache_path(): string { return onepanel_storage_dir() . '/seerr-media-cache.json'; }

function seerr_cache_read(): array {
  $p = seerr_cache_path();
  if (!is_file($p)) return [];
  $d = json_decode((string)@file_get_contents($p), true);
  return is_array($d) ? $d : [];
}

function seerr_cache_write(array $cache): void {
  // Keep the newest 600 titles; the cache only holds public title/year/poster data.
  if (count($cache) > 600) {
    uasort($cache, function ($a, $b) { return (int)($b['t'] ?? 0) - (int)($a['t'] ?? 0); });
    $cache = array_slice($cache, 0, 600, true);
  }
  @file_put_contents(seerr_cache_path(), json_encode($cache), LOCK_EX);
}

function seerr_poster_path($p): string {
  $p = (string)$p;
  return preg_match('#^/[A-Za-z0-9._-]+\.(jpg|jpeg|png|webp)$#i', $p) ? $p : '';
}

/**
 * Look up title/year/poster for many [type, tmdbId] pairs at once (parallel, cached 7 days).
 * Failures are fine: callers fall back to "TMDB #id" and a placeholder poster.
 */
function seerr_media_details(array $items): array {
  $out = []; $need = [];
  $cache = seerr_cache_read(); $now = time(); $dirty = false;
  foreach ($items as $it) {
    $type = $it[0] === 'tv' ? 'tv' : 'movie'; $id = (int)$it[1];
    if ($id <= 0) continue;
    $k = $type . ':' . $id;
    if (isset($cache[$k]) && ($now - (int)($cache[$k]['t'] ?? 0)) < 7 * 86400) { $out[$k] = $cache[$k]; continue; }
    $need[$k] = [$type, $id];
  }
  if ($need && function_exists('curl_multi_init') && seerr_config_problem() === '') {
    $mh = curl_multi_init(); $handles = [];
    foreach ($need as $k => $it) {
      $ch = seerr_curl_handle('GET', '/' . $it[0] . '/' . $it[1]);
      curl_setopt($ch, CURLOPT_TIMEOUT, min(10, seerr_config()['timeout']));
      curl_multi_add_handle($mh, $ch); $handles[$k] = $ch;
    }
    $running = 0;
    do {
      $st = curl_multi_exec($mh, $running);
      if ($running) curl_multi_select($mh, 1.0);
    } while ($running && $st === CURLM_OK);
    foreach ($handles as $k => $ch) {
      $res = seerr_parse_response($ch, curl_multi_getcontent($ch));
      curl_multi_remove_handle($mh, $ch); curl_close($ch);
      if (!$res['ok']) continue;
      $d = $res['data']; $isTv = strpos($k, 'tv:') === 0;
      $title = trim((string)($isTv ? ($d['name'] ?? $d['originalName'] ?? '') : ($d['title'] ?? $d['originalTitle'] ?? '')));
      $date = (string)($isTv ? ($d['firstAirDate'] ?? '') : ($d['releaseDate'] ?? ''));
      $row = ['title' => $title, 'year' => preg_match('/^(\d{4})/', $date, $m) ? $m[1] : '', 'poster' => seerr_poster_path($d['posterPath'] ?? ''), 't' => $now];
      $out[$k] = $row; $cache[$k] = $row; $dirty = true;
    }
    curl_multi_close($mh);
  }
  if ($dirty) seerr_cache_write($cache);
  return $out;
}

/* ---------------- Requests ---------------- */

function seerr_status_key(int $s): string {
  switch ($s) {
    case SEERR_STATUS_PENDING: return 'pending';
    case SEERR_STATUS_APPROVED: return 'approved';
    case SEERR_STATUS_DECLINED: return 'declined';
    case SEERR_STATUS_FAILED: return 'failed';
    case SEERR_STATUS_COMPLETED: return 'completed';
  }
  return 'unknown';
}

function seerr_status_label(int $s): string {
  $map = ['pending' => 'Pending', 'approved' => 'Approved', 'declined' => 'Declined', 'failed' => 'Failed', 'completed' => 'Available', 'unknown' => 'Unknown'];
  return $map[seerr_status_key($s)];
}

function seerr_requester_name($u): string {
  if (!is_array($u)) return '';
  foreach (['displayName', 'username', 'jellyfinUsername', 'plexUsername', 'email'] as $f) {
    $v = trim((string)($u[$f] ?? ''));
    if ($v !== '') return $v;
  }
  return '';
}

/** Normalise one Seerr MediaRequest into the fields the page needs (plain data, no HTML). */
function seerr_normalize_request(array $r, array $details = []): array {
  $type = (($r['type'] ?? '') === 'tv' || (($r['media']['mediaType'] ?? '') === 'tv')) ? 'tv' : 'movie';
  $tmdb = (int)($r['media']['tmdbId'] ?? 0);
  $d = $details[$type . ':' . $tmdb] ?? [];
  $seasons = [];
  if (!empty($r['seasons']) && is_array($r['seasons'])) {
    foreach ($r['seasons'] as $s) if (isset($s['seasonNumber'])) $seasons[] = (int)$s['seasonNumber'];
    sort($seasons);
  }
  $status = (int)($r['status'] ?? 0);
  return [
    'id'        => (int)($r['id'] ?? 0),
    'type'      => $type,
    'tmdb'      => $tmdb,
    'title'     => ($d['title'] ?? '') !== '' ? $d['title'] : ($tmdb > 0 ? 'TMDB #' . $tmdb : 'Unknown title'),
    'title_known' => ($d['title'] ?? '') !== '',
    'year'      => (string)($d['year'] ?? ''),
    'poster'    => ($d['poster'] ?? '') !== '' ? 'https://image.tmdb.org/t/p/w185' . $d['poster'] : '',
    'requester' => seerr_requester_name($r['requestedBy'] ?? null),
    'created'   => (string)($r['createdAt'] ?? ''),
    'status'    => $status,
    'status_key'=> seerr_status_key($status),
    'is4k'      => !empty($r['is4k']),
    'seasons'   => $seasons,
  ];
}

function seerr_counts(): array {
  $res = seerr_api('GET', '/request/count');
  if (!$res['ok']) return [];
  $d = $res['data'];
  return ['pending' => (int)($d['pending'] ?? 0), 'approved' => (int)($d['approved'] ?? 0), 'declined' => (int)($d['declined'] ?? 0), 'all' => (int)($d['total'] ?? 0)];
}

/**
 * List one page of requests for a tab.
 * pending / approved / all use Seerr's own filter + paging.
 * Seerr has no "declined" list filter, so the declined tab reads the most recently
 * updated requests (100 per call, at most SEERR_DECLINED_SCAN) and keeps status 3.
 */
const SEERR_DECLINED_SCAN = 1000;

function seerr_list(string $tab, int $page, int $perPage = 20): array {
  $page = max(1, $page); $perPage = max(5, min(50, $perPage));
  if ($tab === 'declined') {
    $want = $page * $perPage + 1; $found = []; $skip = 0; $scanned = 0; $total = null; $complete = false;
    while (count($found) < $want && $scanned < SEERR_DECLINED_SCAN) {
      $res = seerr_api('GET', '/request', ['take' => 100, 'skip' => $skip, 'filter' => 'all', 'sort' => 'modified', 'sortDirection' => 'desc']);
      if (!$res['ok']) return ['ok' => false, 'error' => $res['error']];
      $rows = is_array($res['data']['results'] ?? null) ? $res['data']['results'] : [];
      $total = (int)($res['data']['pageInfo']['results'] ?? 0);
      foreach ($rows as $r) if ((int)($r['status'] ?? 0) === SEERR_STATUS_DECLINED) $found[] = $r;
      $scanned += count($rows); $skip += 100;
      if (count($rows) < 100 || $scanned >= $total) { $complete = true; break; }
    }
    $slice = array_slice($found, ($page - 1) * $perPage, $perPage);
    $hasMore = count($found) > $page * $perPage;
    return ['ok' => true, 'rows' => $slice, 'page' => $page, 'pages' => $hasMore ? $page + 1 : $page, 'has_more' => $hasMore, 'total' => null,
            'scan_limited' => !$complete && !$hasMore, 'scanned' => $scanned];
  }
  $filter = $tab === 'pending' ? 'pending' : ($tab === 'approved' ? 'approved' : 'all');
  $res = seerr_api('GET', '/request', ['take' => $perPage, 'skip' => ($page - 1) * $perPage, 'filter' => $filter, 'sort' => 'added', 'sortDirection' => 'desc']);
  if (!$res['ok']) return ['ok' => false, 'error' => $res['error']];
  $rows = is_array($res['data']['results'] ?? null) ? $res['data']['results'] : [];
  $total = (int)($res['data']['pageInfo']['results'] ?? count($rows));
  $pages = max(1, (int)ceil($total / $perPage));
  return ['ok' => true, 'rows' => $rows, 'page' => $page, 'pages' => $pages, 'has_more' => $page < $pages, 'total' => $total, 'scan_limited' => false, 'scanned' => 0];
}

/** Attach title/year/poster to raw rows and normalise them. */
function seerr_hydrate(array $rows): array {
  $pairs = [];
  foreach ($rows as $r) {
    $type = (($r['type'] ?? '') === 'tv' || (($r['media']['mediaType'] ?? '') === 'tv')) ? 'tv' : 'movie';
    $pairs[] = [$type, (int)($r['media']['tmdbId'] ?? 0)];
  }
  $details = $pairs ? seerr_media_details($pairs) : [];
  $out = [];
  foreach ($rows as $r) $out[] = seerr_normalize_request($r, $details);
  return $out;
}

/**
 * Approve or decline one request. Re-reads it first so an already-handled request gets a
 * clear answer, then lets Seerr decide (Seerr itself returns 409 for non-pending requests).
 */
function seerr_set_status(int $requestId, string $action): array {
  // Second guard (the action handler checks first): never send a request-changing call in read-only mode.
  if (!seerr_actions_enabled()) return ['ok' => false, 'code' => 'read_only', 'error' => SEERR_READ_ONLY_MESSAGE];
  if ($requestId <= 0 || $requestId > 2147483647) return ['ok' => false, 'code' => 'invalid_id', 'error' => 'Invalid request ID.'];
  if (!in_array($action, ['approve', 'decline'], true)) return ['ok' => false, 'code' => 'invalid_action', 'error' => 'Unsupported action.'];

  $cur = seerr_api('GET', '/request/' . $requestId);
  if (!$cur['ok']) {
    if ($cur['status'] === 404) return ['ok' => false, 'code' => 'not_found', 'error' => 'Seerr has no request #' . $requestId . '. It may have been deleted.'];
    return ['ok' => false, 'code' => 'seerr_error', 'error' => $cur['error']];
  }
  $before = (int)($cur['data']['status'] ?? 0);
  if ($before !== SEERR_STATUS_PENDING) {
    $req = seerr_hydrate([$cur['data']]);
    return ['ok' => false, 'code' => 'not_pending', 'request' => $req[0],
            'error' => 'Request #' . $requestId . ' is already ' . strtolower(seerr_status_label($before)) . ' in Seerr, so nothing was changed.'];
  }

  $res = seerr_api('POST', '/request/' . $requestId . '/' . $action);
  if (!$res['ok']) {
    if ($res['status'] === 409) {
      $again = seerr_api('GET', '/request/' . $requestId);
      $req = $again['ok'] ? seerr_hydrate([$again['data']]) : [null];
      return ['ok' => false, 'code' => 'not_pending', 'request' => $req[0], 'error' => 'Seerr says request #' . $requestId . ' is no longer pending, so nothing was changed.'];
    }
    if ($res['status'] === 404) return ['ok' => false, 'code' => 'not_found', 'error' => 'Seerr has no request #' . $requestId . '.'];
    return ['ok' => false, 'code' => 'seerr_error', 'error' => 'Seerr did not ' . $action . ' the request: ' . $res['error']];
  }
  $after = (int)($res['data']['status'] ?? 0);
  $expected = $action === 'approve' ? SEERR_STATUS_APPROVED : SEERR_STATUS_DECLINED;
  if ($after !== $expected) {
    // Approval can be immediately followed by Seerr's own processing, so re-read before deciding.
    $again = seerr_api('GET', '/request/' . $requestId);
    $after = $again['ok'] ? (int)($again['data']['status'] ?? 0) : $after;
    $okStates = $action === 'approve' ? [SEERR_STATUS_APPROVED, SEERR_STATUS_COMPLETED] : [SEERR_STATUS_DECLINED];
    if (!in_array($after, $okStates, true)) {
      return ['ok' => false, 'code' => 'unconfirmed', 'error' => 'Seerr answered, but the request now shows as "' . seerr_status_label($after) . '". Check it in Seerr before trying again.'];
    }
    if ($again['ok']) $res['data'] = $again['data'];
  }
  $req = seerr_hydrate([$res['data']]);
  return ['ok' => true, 'code' => 'ok', 'request' => $req[0]];
}
