<?php
/*
 * Test stand-in for OnePanel's includes/bootstrap.php: SQLite instead of MySQL, and a fake Flix
 * Town adapter and fake Square API that read and write one shared JSON state file (TVR_STATE), so
 * the real api/tv-renewal.php can be driven over HTTP by tv_renewal_test.php.
 */
$config = [
  'db' => ['pass' => 'test'], 'app' => ['base_url' => 'http://test'],
  'services' => ['flix_town' => ['base_url' => 'http://127.0.0.1:9', 'token' => 'flix-admin-token-never-sent']],
  'pricing' => ['flix_town' => [1 => 15.00, 3 => 40.00, 6 => 75.00, 12 => 135.00]],
  'tv_api' => ['enabled' => getenv('TVR_DISABLED') ? false : true, 'session_secret' => str_repeat('k', 40)],
  'square' => ['environment' => 'sandbox', 'access_token' => 'SQUARE-SECRET-TOKEN', 'location_id' => 'LOC1',
    'webhook_signature_key' => 'whkey', 'webhook_url' => 'https://onepanel.test/api/square-webhook.php'],
];
date_default_timezone_set('America/Denver');
$pdo = new PDO('sqlite:' . getenv('TVR_DB'), null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
$pdo->exec('PRAGMA busy_timeout=5000');

function tvr_state(?callable $change = null): array {
  $f = getenv('TVR_STATE');
  $h = fopen($f, 'c+'); flock($h, LOCK_EX);
  $s = json_decode(stream_get_contents($h) ?: '{}', true) ?: [];
  if ($change) { $s = $change($s); ftruncate($h, 0); rewind($h); fwrite($h, json_encode($s)); }
  flock($h, LOCK_UN); fclose($h);
  return $s;
}
function flix_api_request(string $method, string $path, ?array $payload = null): array {
  $out = null;
  tvr_state(function ($s) use ($method, $path, $payload, &$out) {
    $s['flix_calls'][] = $method . ' ' . $path;
    if (!preg_match('#^/api/onepanel/users/([^/]+)(/renew)?$#', $path, $m)) { $out = ['ok' => false, 'status' => 404, 'data' => null, 'error' => 'no route']; return $s; }
    $u = rawurldecode($m[1]);
    if (!isset($s['users'][$u])) { $out = ['ok' => false, 'status' => 404, 'data' => ['detail' => 'not found'], 'error' => 'not found']; return $s; }
    if (!empty($m[2])) {
      if (!empty($s['renew_fails'])) { $out = ['ok' => false, 'status' => 502, 'data' => null, 'error' => 'provider down']; return $s; }
      $s['renews'][] = [$u, $payload['months']];
      $base = max(time(), strtotime($s['users'][$u]['expires_at'] . ' UTC'));
      $s['users'][$u]['expires_at'] = gmdate('Y-m-d\TH:i:s', strtotime('+' . (int)$payload['months'] . ' months', $base));
    }
    $out = ['ok' => true, 'status' => 200, 'data' => ['success' => true, 'user' => $s['users'][$u]], 'error' => ''];
    return $s;
  });
  return $out;
}
function flix_expiration_local(?string $iso): ?string {
  if (!$iso) return null;
  try { return (new DateTimeImmutable($iso, new DateTimeZone('UTC')))->setTimezone(new DateTimeZone(date_default_timezone_get()))->format('Y-m-d H:i:s'); }
  catch (Throwable $e) { return null; }
}
function renewal_expiration(?string $current, int $months): string {
  $now = new DateTimeImmutable('now');
  $base = ($current && new DateTimeImmutable($current) > $now) ? new DateTimeImmutable($current) : $now;
  return $base->modify('+' . $months . ' months')->format('Y-m-d H:i:s');
}
$GLOBALS['tvr_square_http'] = function (string $method, string $path, ?array $body) {
  $out = null;
  tvr_state(function ($s) use ($method, $path, $body, &$out) {
    $s['square_calls'][] = ['method' => $method, 'path' => $path, 'body' => $body];
    if ($method === 'POST' && $path === '/v2/online-checkout/payment-links') {
      $n = $s['link_n'] = ($s['link_n'] ?? 0) + 1;
      $link = ['id' => 'LINK' . $n, 'order_id' => 'ORDER' . $n, 'url' => 'https://square.link/u/test' . $n];
      $s['links'][$link['id']] = $link; $s['orders'][$link['order_id']] = ['state' => 'OPEN', 'tenders' => []];
      $out = ['status' => 200, 'data' => ['payment_link' => $link]]; return $s;
    }
    if ($method === 'DELETE' && preg_match('#^/v2/online-checkout/payment-links/(.+)$#', $path, $m)) { unset($s['links'][$m[1]]); $s['deleted'][] = $m[1]; $out = ['status' => 200, 'data' => []]; return $s; }
    if ($method === 'GET' && preg_match('#^/v2/orders/(.+)$#', $path, $m)) { $out = isset($s['orders'][$m[1]]) ? ['status' => 200, 'data' => ['order' => $s['orders'][$m[1]]]] : ['status' => 404, 'data' => null]; return $s; }
    if ($method === 'GET' && preg_match('#^/v2/payments/(.+)$#', $path, $m)) { $out = isset($s['payments'][$m[1]]) ? ['status' => 200, 'data' => ['payment' => $s['payments'][$m[1]]]] : ['status' => 404, 'data' => null]; return $s; }
    $out = ['status' => 404, 'data' => null]; return $s;
  });
  return $out;
};
