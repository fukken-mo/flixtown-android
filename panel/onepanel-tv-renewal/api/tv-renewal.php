<?php
/*
 * OnePanel public API for the Flix Town TV app's in-app renewal.
 *
 * POST JSON {"action": ...}. Actions:
 *   context  {username, password}        -> session, account (status, expires_at, devices, plan), plans
 *   quote    {session, months}            -> the server's price for that plan
 *   create   {session, months}            -> a Cash App Pay checkout (checkout_url for the TV's QR code)
 *   status   {session, payment_id}        -> waiting | processing | renewed | cancelled | expired | failed | needs_assistance
 *   cancel   {session, payment_id}        -> cancels an unpaid code
 * Every answer is {"ok":true,...} or {"ok":false,"error":code,"message":text}. Nothing secret is returned.
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function tvr_out(array $data, int $code = 200): void { http_response_code($code); echo json_encode($data, JSON_UNESCAPED_SLASHES); exit; }
function tvr_fail(string $error, string $message, int $code = 400): void { tvr_out(['ok' => false, 'error' => $error, 'message' => $message], $code); }

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') tvr_fail('method', 'Use POST.', 405);
$raw = file_get_contents('php://input', false, null, 0, 8192);
$in = json_decode((string)$raw, true);
if (!is_array($in)) tvr_fail('bad_request', 'Send a JSON object.');

require __DIR__ . '/../includes/bootstrap.php';
require __DIR__ . '/../includes/tv_renewal.php';
if (session_status() === PHP_SESSION_ACTIVE) session_write_close();   // the TV API never uses the admin session

if (!tvr_config()['enabled']) tvr_fail('disabled', 'Renewing on the TV is not available right now.', 503);
$ip = (string)($_SERVER['REMOTE_ADDR'] ?? '');
if (!tvr_rate($pdo, 'ip:' . $ip, 300, 900)) tvr_fail('busy', 'Too many requests. Please wait a few minutes.', 429);

$action = (string)($in['action'] ?? '');
try {
  if ($action === 'context') {
    $username = trim((string)($in['username'] ?? '')); $password = (string)($in['password'] ?? '');
    if (!tvr_valid_username($username) || $password === '') tvr_fail('auth', 'Please sign in again.', 401);
    if (!tvr_rate($pdo, 'ctx:' . $ip, 30, 900)) tvr_fail('busy', 'Too many requests. Please wait a few minutes.', 429);
    $user = tvr_flix_user($username);
    if (($user['error'] ?? '') === 'unavailable') tvr_fail('unavailable', 'We could not reach your account. Please try again.', 503);
    if (isset($user['error']) || !tvr_check_password($user, $password)) {
      tvr_rate($pdo, 'bad:' . $ip, 10, 900) || tvr_fail('busy', 'Too many requests. Please wait a few minutes.', 429);
      tvr_fail('auth', 'Please sign in again.', 401);
    }
    $plans = array_values(array_map(fn($p) => ['months' => $p['months'], 'label' => $p['label'], 'price' => $p['price']], tvr_plans()));
    tvr_out(['ok' => true, 'session' => tvr_session_issue($username), 'session_ttl' => TVR_SESSION_TTL,
      'account' => tvr_account($pdo, $user), 'plans' => $plans, 'currency' => 'USD',
      'payments_available' => tvr_square_ready()]);
  }

  $username = tvr_session_user($in['session'] ?? null);
  if ($username === null) tvr_fail('session', 'Your session ended. Please try again.', 401);

  if ($action === 'quote' || $action === 'create') {
    $quote = tvr_quote($in['months'] ?? null);
    if (!$quote) tvr_fail('plan', 'Choose one of the plans shown.');
    $user = tvr_flix_user($username);
    if (isset($user['error'])) tvr_fail('unavailable', 'We could not reach your account. Please try again.', 503);
    $account = tvr_account($pdo, $user);
    if (!$account['renewable']) tvr_fail('not_renewable', $account['status'] === 'Disabled'
      ? 'This account needs help from support before it can be renewed.' : 'Your plan never expires, so there is nothing to renew.', 409);
    if ($action === 'quote') tvr_out(['ok' => true, 'quote' => ['months' => $quote['months'], 'label' => $quote['label'], 'price' => $quote['price'], 'devices' => $account['devices']]]);
    if (!tvr_rate($pdo, 'create:' . $username, 12, 3600)) tvr_fail('busy', 'Too many payment codes were requested. Please wait a while.', 429);
    $row = tvr_create_payment($pdo, $username, $quote, $account['devices']);
    if (isset($row['error'])) tvr_fail('payments_unavailable', 'Cash App Pay is not available right now. Please try again later.', 503);
    tvr_out(['ok' => true, 'payment' => tvr_public_payment($row)]);
  }

  if ($action === 'status' || $action === 'cancel') {
    $id = (string)($in['payment_id'] ?? '');
    $row = preg_match('/^tvp_[a-f0-9]{24}$/', $id) ? tvr_payment_row($pdo, $id) : null;
    if (!$row || !hash_equals((string)$row['username'], $username)) tvr_fail('not_found', 'That payment was not found.', 404);
    $row = $action === 'cancel' ? tvr_cancel($pdo, $id) : tvr_advance($pdo, $id);
    tvr_out(['ok' => true, 'payment' => tvr_public_payment($row)]);
  }
  tvr_fail('bad_request', 'Unknown action.');
} catch (Throwable $e) {
  error_log('tv-renewal: ' . $e->getMessage());
  tvr_fail('server', 'Something went wrong. Please try again.', 500);
}
