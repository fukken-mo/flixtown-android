<?php
/*
 * OnePanel customer API for media requests from the Flix Town TV app (Seerr behind it).
 *
 * POST JSON {"action": ...}:
 *   session  {username, password}               -> session (30 min). Checked against Flix Town.
 *   search   {session, query, page}             -> movies and series with title, year, poster, state
 *   details  {session, type, id}                -> artwork, synopsis, state, seasons, can_request
 *   request  {session, type, id, seasons?}      -> a Pending request (duplicates refused)
 *   history  {session, page}                    -> this customer's own requests only
 * States: available, partial, approved, pending, declined, unavailable, requestable (+ removed in history).
 * Errors: {"ok":false,"error":code,"message":text}. Any Seerr/OnePanel problem answers 503
 * "Requests are temporarily unavailable." Answers never contain Seerr users, keys or tokens.
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function tvq_out(array $data, int $code = 200): void { http_response_code($code); echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE); exit; }
function tvq_err(int $code, string $error, string $message): void { tvq_out(['ok' => false, 'error' => $error, 'message' => $message], $code); }

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') tvq_err(405, 'method', 'Use POST.');
$in = json_decode((string)file_get_contents('php://input', false, null, 0, 8192), true);
if (!is_array($in)) tvq_err(400, 'bad_request', 'Send a JSON object.');

require __DIR__ . '/../includes/bootstrap.php';
require __DIR__ . '/../includes/seerr.php';
require __DIR__ . '/../includes/tv_requests.php';
if (session_status() === PHP_SESSION_ACTIVE) session_write_close();   // never touches the admin session

$problem = tvq_problem();
if ($problem !== '') { error_log('tv-requests disabled: ' . $problem); tvq_err(503, 'unavailable', TVQ_UNAVAILABLE); }
$ip = (string)($_SERVER['REMOTE_ADDR'] ?? '');

try {
  if (!tvq_rate($pdo, 'ip:' . $ip, 600, 900)) tvq_err(429, 'busy', 'Too many requests. Please wait a few minutes.');
  $action = (string)($in['action'] ?? '');

  if ($action === 'session') {
    $username = trim((string)($in['username'] ?? '')); $password = (string)($in['password'] ?? '');
    if (!tvq_valid_username($username) || $password === '') tvq_err(401, 'auth', 'Please sign in again.');
    if (!tvq_rate($pdo, 'login:' . $ip, 30, 900)) tvq_err(429, 'busy', 'Too many requests. Please wait a few minutes.');
    $user = tvq_flix_login($username, $password);
    if (!$user) { tvq_rate($pdo, 'bad:' . $ip, 10, 900) || tvq_err(429, 'busy', 'Too many requests. Please wait a few minutes.'); tvq_err(401, 'auth', 'Please sign in again.'); }
    if (!tvq_config()['allow_expired'] && !tvq_account_active($user)) tvq_err(403, 'inactive', 'Requests are available while your subscription is active.');
    tvq_out(['ok' => true, 'session' => tvq_session_issue($username), 'session_ttl' => TVQ_SESSION_TTL]);
  }

  $username = tvq_session_user($in['session'] ?? null);
  if ($username === null) tvq_err(401, 'session', 'Please sign in again.');

  if ($action === 'search') {
    $query = trim(preg_replace('/[\x00-\x1F\x7F]+/u', ' ', (string)($in['query'] ?? '')));
    $len = function_exists('mb_strlen') ? mb_strlen($query) : strlen($query);
    if ($len < 2 || $len > 80) tvq_err(400, 'query', 'Type 2 to 80 characters.');
    $page = $in['page'] ?? 1;
    if (!is_int($page) || $page < 1 || $page > 10) tvq_err(400, 'page', 'Invalid page.');
    if (!tvq_rate($pdo, 'search:' . $username, 40, 60) || !tvq_rate($pdo, 'searchh:' . $username, 400, 3600)) tvq_err(429, 'busy', 'Too many searches. Please wait a moment.');
    tvq_out(['ok' => true] + tvq_search($query, $page));
  }

  if ($action === 'details' || $action === 'request') {
    $type = (string)($in['type'] ?? ''); $id = $in['id'] ?? null;
    if ($type !== 'movie' && $type !== 'tv') tvq_err(400, 'type', 'Invalid media type.');
    if (!is_int($id) || $id < 1 || $id > 99999999) tvq_err(400, 'id', 'Invalid title.');
    if ($action === 'details') {
      if (!tvq_rate($pdo, 'details:' . $username, 60, 60)) tvq_err(429, 'busy', 'Please wait a moment.');
      tvq_out(['ok' => true, 'details' => tvq_details($type, $id)]);
    }
    $seasons = $in['seasons'] ?? [];
    if (!is_array($seasons)) tvq_err(400, 'season', 'Invalid seasons.');
    if ($type === 'movie' && $seasons) tvq_err(400, 'season', 'Movies have no seasons.');
    if ($type === 'tv') {
      if (!$seasons || count($seasons) > 50) tvq_err(400, 'season', 'Choose at least one season.');
      foreach ($seasons as $n) if (!is_int($n) || $n < 1 || $n > 200) tvq_err(400, 'season', 'Invalid season.');
      $seasons = array_values(array_unique($seasons)); sort($seasons);
    }
    if (!tvq_rate($pdo, 'submit:' . $username, 6, 600)) tvq_err(429, 'busy', 'Too many requests. Please wait a few minutes.');
    tvq_out(['ok' => true] + tvq_request($pdo, $username, $type, $id, $seasons));
  }

  if ($action === 'history') {
    $page = $in['page'] ?? 1;
    if (!is_int($page) || $page < 1 || $page > 50) tvq_err(400, 'page', 'Invalid page.');
    if (!tvq_rate($pdo, 'history:' . $username, 30, 60)) tvq_err(429, 'busy', 'Please wait a moment.');
    tvq_out(['ok' => true] + tvq_history($pdo, $username, $page));
  }
  tvq_err(400, 'bad_request', 'Unknown action.');
} catch (TvqException $e) {
  tvq_err($e->http, $e->code_name, $e->getMessage());
} catch (Throwable $e) {
  error_log('tv-requests error: ' . get_class($e));
  tvq_err(503, 'unavailable', TVQ_UNAVAILABLE);
}
