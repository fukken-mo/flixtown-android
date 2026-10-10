<?php
/*
 * OnePanel · Flix Town TV renewal (Cash App Pay through Square)
 *
 * Server side of the in-app renewal on the Flix Town Android TV app. The TV never holds a Square,
 * OnePanel or Flix Town secret: it signs in once with the customer's own Flix Town username and
 * password (checked here against the Flix Town backend), receives a short signed session, and
 * afterwards only sends that session, the chosen plan length and the payment id.
 *
 * OnePanel is the source of truth:
 *   - prices come from $config['pricing']['flix_town'] (never from the TV);
 *   - the payment is a Square payment link with Cash App Pay enabled, created with the Square
 *     access token kept in config.php; the TV only receives its public checkout URL and draws it
 *     as a QR code, which the customer opens on their phone;
 *   - the account is renewed only after Square reports the payment COMPLETED for exactly the
 *     quoted amount, and only once per payment (an atomic state change guards against double
 *     renewals from parallel status checks, the webhook and the sweep).
 *
 * Requires includes/bootstrap.php (for $config, $pdo, flix_api_request, renewal_expiration,
 * flix_expiration_local).
 */

if (!function_exists('tvr_config')) {

define('TVR_SESSION_TTL', 1800);       // seconds a TV session stays valid
define('TVR_LINK_TTL', 900);           // seconds a payment code is offered before it expires
define('TVR_RENEW_STUCK', 300);        // a renewal still "renewing" after this needs a person

function tvr_config(): array {
  global $config;
  return [
    'enabled' => (bool)($config['tv_api']['enabled'] ?? false),
    'secret' => (string)($config['tv_api']['session_secret'] ?? ''),
    'square_env' => (string)($config['square']['environment'] ?? 'production'),
    'square_token' => (string)($config['square']['access_token'] ?? ''),
    'square_location' => (string)($config['square']['location_id'] ?? ''),
    'square_version' => (string)($config['square']['version'] ?? '2025-01-23'),
    'webhook_key' => (string)($config['square']['webhook_signature_key'] ?? ''),
    'webhook_url' => (string)($config['square']['webhook_url'] ?? ''),
  ];
}

/* ---------------- plans (server-side prices) ---------------- */

function tvr_plans(): array {
  global $config;
  $prices = $config['pricing']['flix_town'] ?? [1 => 15.00, 3 => 40.00, 6 => 75.00, 12 => 135.00];
  $out = [];
  foreach ($prices as $months => $price) {
    $m = (int)$months; $p = round((float)$price, 2);
    if ($m < 1 || $p <= 0) continue;
    $out[$m] = ['months' => $m, 'label' => tvr_plan_label($m), 'price' => number_format($p, 2, '.', ''), 'cents' => (int)round($p * 100)];
  }
  ksort($out);
  return $out;
}
function tvr_plan_label(int $months): string { return $months . ' ' . ($months === 1 ? 'Month' : 'Months'); }
/** Quote for a plan length sent by the TV; null for anything that is not an offered plan. */
function tvr_quote($months): ?array {
  if (!is_int($months) && !(is_string($months) && ctype_digit($months))) return null;
  $plans = tvr_plans();
  return $plans[(int)$months] ?? null;
}

/* ---------------- sessions ---------------- */

function tvr_secret(): string {
  global $config;
  $s = tvr_config()['secret'];
  // Without a configured secret, derive one from server-only values (same idea as credential_key()).
  return strlen($s) >= 32 ? $s : hash('sha256', ($config['db']['pass'] ?? '') . '|' . ($config['app']['base_url'] ?? '') . '|tv-renewal-session');
}
function tvr_b64(string $raw): string { return rtrim(strtr(base64_encode($raw), '+/', '-_'), '='); }
function tvr_unb64(string $s) { return base64_decode(strtr($s, '-_', '+/') . str_repeat('=', (4 - strlen($s) % 4) % 4), true); }
function tvr_session_issue(string $username, ?int $now = null): string {
  $now = $now ?? time();
  $body = tvr_b64(json_encode(['u' => $username, 'exp' => $now + TVR_SESSION_TTL, 'n' => bin2hex(random_bytes(6))]));
  return $body . '.' . tvr_b64(hash_hmac('sha256', $body, tvr_secret(), true));
}
/** Username of a valid, unexpired session, or null. */
function tvr_session_user($token, ?int $now = null): ?string {
  if (!is_string($token) || strlen($token) > 400 || substr_count($token, '.') !== 1) return null;
  [$body, $sig] = explode('.', $token);
  $want = tvr_b64(hash_hmac('sha256', $body, tvr_secret(), true));
  if (!hash_equals($want, $sig)) return null;
  $data = json_decode((string)tvr_unb64($body), true);
  if (!is_array($data) || !isset($data['u'], $data['exp'])) return null;
  if ((int)$data['exp'] < ($now ?? time())) return null;
  $u = (string)$data['u'];
  return tvr_valid_username($u) ? $u : null;
}
function tvr_valid_username($u): bool { return is_string($u) && $u !== '' && strlen($u) <= 100 && preg_match('/^[A-Za-z0-9._@-]+$/', $u); }

/* ---------------- rate limits ---------------- */

/** Counts one hit for $key; false when more than $max hits happened in the current $window seconds. */
function tvr_rate(PDO $pdo, string $key, int $max, int $window): bool {
  $now = time(); $key = substr($key, 0, 120);
  $q = $pdo->prepare('SELECT window_start,hits FROM tv_api_rate WHERE k=?'); $q->execute([$key]); $row = $q->fetch();
  if (!$row || (int)$row['window_start'] + $window <= $now) {
    $pdo->prepare('DELETE FROM tv_api_rate WHERE k=?')->execute([$key]);
    $pdo->prepare('INSERT INTO tv_api_rate(k,window_start,hits) VALUES(?,?,1)')->execute([$key, $now]);
    return true;
  }
  $pdo->prepare('UPDATE tv_api_rate SET hits=hits+1 WHERE k=?')->execute([$key]);
  return (int)$row['hits'] + 1 <= $max;
}

/* ---------------- Flix Town account ---------------- */

/** The Flix Town line as the backend reports it (admin adapter), or ['error'=>...]. */
function tvr_flix_user(string $username): array {
  $r = flix_api_request('GET', '/api/onepanel/users/' . rawurlencode($username));
  if ((int)$r['status'] === 404) return ['error' => 'not_found'];
  if (!$r['ok']) return ['error' => 'unavailable'];
  $data = is_array($r['data']) ? $r['data'] : [];
  $user = is_array($data['user'] ?? null) ? $data['user'] : $data;
  return is_array($user) && isset($user['username']) ? $user : ['error' => 'unavailable'];
}
/** True when $password is this line's password: compared with the backend's copy, or by the player API. */
function tvr_check_password(array $user, string $password): bool {
  if ($password === '' || strlen($password) > 100) return false;
  if (isset($user['password']) && (string)$user['password'] !== '') return hash_equals((string)$user['password'], $password);
  global $config;
  $base = rtrim((string)($config['services']['flix_town']['base_url'] ?? ''), '/');
  $ch = curl_init($base . '/player_api.php?username=' . rawurlencode((string)$user['username']) . '&password=' . rawurlencode($password));
  curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 8, CURLOPT_TIMEOUT => 15]);
  $raw = curl_exec($ch); curl_close($ch);
  $info = is_string($raw) ? (json_decode($raw, true)['user_info'] ?? null) : null;
  return is_array($info) && in_array((string)($info['status'] ?? ''), ['Active', 'Expired'], true)
    && (string)($info['username'] ?? '') === (string)$user['username'];
}
function tvr_expires_ts(array $user): ?int {
  $iso = $user['expires_at'] ?? null;
  if (!$iso) return null;
  try { return (new DateTimeImmutable((string)$iso, new DateTimeZone('UTC')))->getTimestamp(); } catch (Throwable $e) { return null; }
}
/** What the TV may show: status, expiry (null = never), devices, last plan length when OnePanel knows it. */
function tvr_account(PDO $pdo, array $user): array {
  $exp = tvr_expires_ts($user);
  $enabled = array_key_exists('enabled', $user) ? (bool)$user['enabled'] : true;
  $q = $pdo->prepare("SELECT last_plan_months FROM customer_services WHERE service='flix_town' AND username=? LIMIT 1");
  $q->execute([(string)$user['username']]); $row = $q->fetch();
  $plan = $row && (int)$row['last_plan_months'] > 0 ? (int)$row['last_plan_months'] : null;
  return [
    'username' => (string)$user['username'],
    'status' => !$enabled ? 'Disabled' : ($exp !== null && $exp <= time() ? 'Expired' : 'Active'),
    'expires_at' => $exp,
    'devices' => max(1, (int)($user['max_connections'] ?? 1)),
    'plan_months' => $plan,
    'plan_label' => $plan ? tvr_plan_label($plan) : null,
    // Never Expire lines have nothing to renew; a disabled line needs a person.
    'renewable' => $enabled && $exp !== null,
  ];
}

/* ---------------- Square ---------------- */

function tvr_square_base(): string {
  return tvr_config()['square_env'] === 'sandbox' ? 'https://connect.squareupsandbox.com' : 'https://connect.squareup.com';
}
/** ['status'=>int,'data'=>array|null]; tests replace $GLOBALS['tvr_square_http']. */
function tvr_square(string $method, string $path, ?array $body = null): array {
  if (isset($GLOBALS['tvr_square_http']) && is_callable($GLOBALS['tvr_square_http'])) return ($GLOBALS['tvr_square_http'])($method, $path, $body);
  $c = tvr_config();
  $ch = curl_init(tvr_square_base() . $path);
  $headers = ['Authorization: Bearer ' . $c['square_token'], 'Square-Version: ' . $c['square_version'], 'Accept: application/json'];
  $opts = [CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 8, CURLOPT_TIMEOUT => 20, CURLOPT_CUSTOMREQUEST => $method];
  if ($body !== null) { $headers[] = 'Content-Type: application/json'; $opts[CURLOPT_POSTFIELDS] = json_encode($body, JSON_UNESCAPED_SLASHES); }
  $opts[CURLOPT_HTTPHEADER] = $headers;
  curl_setopt_array($ch, $opts);
  $raw = curl_exec($ch); $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE); curl_close($ch);
  return ['status' => $status, 'data' => is_string($raw) ? json_decode($raw, true) : null];
}
function tvr_square_ready(): bool { $c = tvr_config(); return $c['square_token'] !== '' && $c['square_location'] !== ''; }

/* ---------------- payments ---------------- */

function tvr_payment_row(PDO $pdo, string $id): ?array {
  $q = $pdo->prepare('SELECT * FROM tv_payments WHERE id=?'); $q->execute([$id]); $r = $q->fetch();
  return $r ?: null;
}
/** Creates the Square payment link for a plan; returns the payment row or ['error'=>...]. */
function tvr_create_payment(PDO $pdo, string $username, array $quote, int $devices): array {
  if (!tvr_square_ready()) return ['error' => 'payments_unavailable'];
  $id = 'tvp_' . bin2hex(random_bytes(12));
  $res = tvr_square('POST', '/v2/online-checkout/payment-links', [
    'idempotency_key' => $id,
    'quick_pay' => [
      'name' => 'Flix Town · ' . $quote['label'],
      'price_money' => ['amount' => $quote['cents'], 'currency' => 'USD'],
      'location_id' => tvr_config()['square_location'],
    ],
    'checkout_options' => ['accepted_payment_methods' => ['cash_app_pay' => true], 'allow_tipping' => false, 'ask_for_shipping_address' => false],
    'payment_note' => 'Flix Town renewal ' . $username . ' · ' . $quote['label'] . ' · ' . $id,
  ]);
  $link = $res['data']['payment_link'] ?? null;
  if ($res['status'] < 200 || $res['status'] >= 300 || !is_array($link) || empty($link['url']) || empty($link['order_id'])) return ['error' => 'payments_unavailable'];
  $now = time();
  $pdo->prepare('INSERT INTO tv_payments(id,username,months,amount_cents,devices,state,square_link_id,square_order_id,checkout_url,created_ts,expires_ts,updated_ts) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)')
      ->execute([$id, $username, $quote['months'], $quote['cents'], $devices, 'waiting', (string)($link['id'] ?? ''), (string)$link['order_id'], (string)$link['url'], $now, $now + TVR_LINK_TTL, $now]);
  return tvr_payment_row($pdo, $id);
}
function tvr_set_state(PDO $pdo, string $id, string $state, array $extra = []): void {
  $cols = ['state=?', 'updated_ts=?']; $vals = [$state, time()];
  foreach ($extra as $k => $v) { $cols[] = $k . '=?'; $vals[] = $v; }
  $vals[] = $id;
  $pdo->prepare('UPDATE tv_payments SET ' . implode(',', $cols) . ' WHERE id=?')->execute($vals);
}
/** Square's view of the order: 'none' (unpaid), 'processing', 'completed' (+payment id) or 'failed'. */
function tvr_square_payment_state(array $row): array {
  $o = tvr_square('GET', '/v2/orders/' . rawurlencode((string)$row['square_order_id']));
  if ($o['status'] < 200 || $o['status'] >= 300 || !is_array($o['data']['order'] ?? null)) return ['state' => 'unknown'];
  $tenders = $o['data']['order']['tenders'] ?? [];
  if (!$tenders) return ['state' => (($o['data']['order']['state'] ?? '') === 'CANCELED') ? 'failed' : 'none'];
  $best = ['state' => 'none'];
  foreach ($tenders as $t) {
    $pid = (string)($t['payment_id'] ?? $t['id'] ?? '');
    if ($pid === '') continue;
    $p = tvr_square('GET', '/v2/payments/' . rawurlencode($pid));
    $pay = $p['data']['payment'] ?? null;
    if (!is_array($pay)) return ['state' => 'unknown'];
    $status = (string)($pay['status'] ?? '');
    $amount = (int)($pay['amount_money']['amount'] ?? -1); $currency = (string)($pay['amount_money']['currency'] ?? '');
    if ($status === 'COMPLETED') {
      // Only the exact quoted amount, in USD, for this order renews the account.
      if ($amount === (int)$row['amount_cents'] && $currency === 'USD' && (string)($pay['order_id'] ?? $row['square_order_id']) === (string)$row['square_order_id'])
        return ['state' => 'completed', 'payment_id' => $pid];
      return ['state' => 'mismatch', 'payment_id' => $pid];
    }
    if ($status === 'APPROVED' || $status === 'PENDING') $best = ['state' => 'processing', 'payment_id' => $pid];
    elseif (($status === 'FAILED' || $status === 'CANCELED') && $best['state'] === 'none') $best = ['state' => 'failed', 'payment_id' => $pid];
  }
  return $best;
}

/**
 * Brings a payment up to date with Square and renews the account once it is paid.
 * Safe to call from the TV's status checks, the webhook and the sweep at the same time.
 */
function tvr_advance(PDO $pdo, string $id): ?array {
  $row = tvr_payment_row($pdo, $id);
  if (!$row) return null;
  $state = (string)$row['state'];
  if ($state === 'renewing' && time() - (int)$row['updated_ts'] > TVR_RENEW_STUCK) {
    tvr_set_state($pdo, $id, 'needs_assistance', ['note' => 'Renewal did not finish; check the Flix Town line before retrying.']);
    return tvr_payment_row($pdo, $id);
  }
  if (!in_array($state, ['waiting', 'processing', 'paid'], true)) return $row;

  if ($state !== 'paid') {
    $sq = tvr_square_payment_state($row);
    if ($sq['state'] === 'completed') {
      $pdo->prepare("UPDATE tv_payments SET state='paid',square_payment_id=?,paid_ts=?,updated_ts=? WHERE id=? AND state IN ('waiting','processing')")
          ->execute([$sq['payment_id'], time(), time(), $id]);
    } elseif ($sq['state'] === 'mismatch') {
      tvr_set_state($pdo, $id, 'needs_assistance', ['square_payment_id' => $sq['payment_id'], 'note' => 'Paid amount does not match the plan price.']);
      return tvr_payment_row($pdo, $id);
    } elseif ($sq['state'] === 'processing') {
      if ($state === 'waiting') tvr_set_state($pdo, $id, 'processing', ['square_payment_id' => $sq['payment_id']]);
      return tvr_payment_row($pdo, $id);
    } elseif ($sq['state'] === 'failed') {
      tvr_set_state($pdo, $id, 'failed');
      return tvr_payment_row($pdo, $id);
    } else {
      // Unpaid (or Square unreachable): expire the code only when Square confirmed nothing was paid.
      if ($sq['state'] === 'none' && $state === 'waiting' && time() > (int)$row['expires_ts']) {
        if ($row['square_link_id']) tvr_square('DELETE', '/v2/online-checkout/payment-links/' . rawurlencode((string)$row['square_link_id']));
        tvr_set_state($pdo, $id, 'expired');
      }
      return tvr_payment_row($pdo, $id);
    }
  }
  return tvr_renew($pdo, $id);
}

/** Renews a paid payment's line exactly once and records it like a manual OnePanel transaction. */
function tvr_renew(PDO $pdo, string $id): ?array {
  $claim = $pdo->prepare("UPDATE tv_payments SET state='renewing',updated_ts=? WHERE id=? AND state='paid'");
  $claim->execute([time(), $id]);
  if ($claim->rowCount() !== 1) return tvr_payment_row($pdo, $id);   // someone else is renewing it
  $row = tvr_payment_row($pdo, $id);
  $username = (string)$row['username']; $months = (int)$row['months'];
  $before = tvr_flix_user($username);
  $result = flix_api_request('POST', '/api/onepanel/users/' . rawurlencode($username) . '/renew', ['months' => $months]);
  if (!$result['ok']) {
    tvr_set_state($pdo, $id, 'needs_assistance', ['note' => 'Paid; Flix Town renewal failed: ' . substr((string)$result['error'], 0, 180)]);
    return tvr_payment_row($pdo, $id);
  }
  $user = is_array($result['data']['user'] ?? null) ? $result['data']['user'] : tvr_flix_user($username);
  $newTs = is_array($user) ? tvr_expires_ts($user) : null;
  tvr_set_state($pdo, $id, 'renewed', ['renewed_ts' => time(), 'new_expires_ts' => $newTs]);
  try { tvr_record(
    $pdo, $row, isset($before['error']) ? null : $before, is_array($user) ? $user : []); } catch (Throwable $e) {
    // The renewal itself succeeded; a bookkeeping problem must not turn it into a failure for the customer.
    error_log('tv-renewal: renewed ' . $id . ' but could not record it: ' . $e->getMessage());
  }
  return tvr_payment_row($pdo, $id);
}
/** Payment, renewal and activity rows for a linked OnePanel customer (same tables as transaction.php). */
function tvr_record(PDO $pdo, array $row, ?array $before, array $after): void {
  $q = $pdo->prepare("SELECT * FROM customer_services WHERE service='flix_town' AND username=? LIMIT 1");
  $q->execute([(string)$row['username']]); $svc = $q->fetch();
  if (!$svc) return;   // line not linked to a OnePanel customer: the tv_payments row is the record
  $cid = (int)$svc['customer_id']; $months = (int)$row['months']; $amount = ((int)$row['amount_cents']) / 100;
  $prev = $before ? flix_expiration_local($before['expires_at'] ?? null) : ($svc['expires_at'] ?? null);
  $new = flix_expiration_local($after['expires_at'] ?? null) ?: renewal_expiration($svc['expires_at'] ?? null, $months);
  $pdo->beginTransaction();
  try {
    $pdo->prepare("INSERT INTO payments(customer_id,method,amount,status,reference,paid_at) VALUES(?,'cashapp',?,'approved',?,?)")
        ->execute([$cid, $amount, 'Cash App Pay (TV) ' . $row['square_payment_id'], date('Y-m-d H:i:s')]);
    $pid = (int)$pdo->lastInsertId();
    $pdo->prepare('INSERT INTO payment_items(payment_id,service,months,amount) VALUES(?,?,?,?)')->execute([$pid, 'flix_town', $months, $amount]);
    $pdo->prepare("INSERT INTO renewals(customer_id,service,payment_id,months,previous_expiration,new_expiration,state,provider_mode,provider_reference,completed_at) VALUES(?,?,?,?,?,?,'completed','automatic',?,?)")
        ->execute([$cid, 'flix_town', $pid, $months, $prev, $new, (string)($after['id'] ?? $svc['provider_user_id'] ?? ''), date('Y-m-d H:i:s')]);
    $pdo->prepare("UPDATE customer_services SET status='active',expires_at=?,last_plan_months=? WHERE id=?")->execute([$new, $months, $svc['id']]);
    $pdo->prepare('INSERT INTO activity_logs(admin_id,customer_id,action,details) VALUES(NULL,?,?,?)')
        ->execute([$cid, 'flix_town_tv_renewal', 'Renewed from the TV app: ' . tvr_plan_label($months) . ' · $' . number_format($amount, 2) . ' · Cash App Pay']);
    $pdo->commit();
  } catch (Throwable $e) { $pdo->rollBack(); throw $e; }
}
/** Customer cancels an unpaid code; a payment already made is never cancelled here. */
function tvr_cancel(PDO $pdo, string $id): ?array {
  $row = tvr_advance($pdo, $id);
  if (!$row || !in_array($row['state'], ['waiting'], true)) return $row;
  if ($row['square_link_id']) tvr_square('DELETE', '/v2/online-checkout/payment-links/' . rawurlencode((string)$row['square_link_id']));
  $pdo->prepare("UPDATE tv_payments SET state='cancelled',updated_ts=? WHERE id=? AND state='waiting'")->execute([time(), $id]);
  return tvr_payment_row($pdo, $id);
}

/** What the TV is told about a payment. No Square ids, no notes meant for staff. */
function tvr_public_payment(array $row): array {
  $map = ['waiting' => 'waiting', 'processing' => 'processing', 'paid' => 'processing', 'renewing' => 'processing',
    'renewed' => 'renewed', 'cancelled' => 'cancelled', 'expired' => 'expired', 'failed' => 'failed', 'needs_assistance' => 'needs_assistance'];
  $out = [
    'id' => (string)$row['id'], 'state' => $map[(string)$row['state']] ?? 'processing',
    'months' => (int)$row['months'], 'label' => tvr_plan_label((int)$row['months']),
    'amount' => number_format(((int)$row['amount_cents']) / 100, 2, '.', ''), 'expires_at' => (int)$row['expires_ts'],
    'expires_in' => max(0, (int)$row['expires_ts'] - time()),
  ];
  if ($out['state'] === 'waiting') $out['checkout_url'] = (string)$row['checkout_url'];
  if ($out['state'] === 'renewed') $out['new_expires_at'] = $row['new_expires_ts'] !== null ? (int)$row['new_expires_ts'] : null;
  return $out;
}

/* ---------------- webhook ---------------- */

function tvr_webhook_valid(string $body, string $signature): bool {
  $c = tvr_config();
  if ($c['webhook_key'] === '' || $c['webhook_url'] === '' || $signature === '') return false;
  return hash_equals(base64_encode(hash_hmac('sha256', $c['webhook_url'] . $body, $c['webhook_key'], true)), $signature);
}
/** Square payment.created / payment.updated: advance the matching TV payment (renews even if the TV was turned off). */
function tvr_webhook(PDO $pdo, array $event): ?string {
  $order = (string)($event['data']['object']['payment']['order_id'] ?? '');
  if ($order === '') return null;
  $q = $pdo->prepare('SELECT id FROM tv_payments WHERE square_order_id=? LIMIT 1'); $q->execute([$order]); $id = $q->fetchColumn();
  if (!$id) return null;
  tvr_advance($pdo, (string)$id);
  return (string)$id;
}

}
