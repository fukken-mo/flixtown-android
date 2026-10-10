<?php
/*
 * End-to-end test of the TV renewal API: the real api/tv-renewal.php and api/square-webhook.php
 * served by `php -S`, with tests/fake_bootstrap.php standing in for OnePanel's bootstrap (SQLite,
 * fake Flix Town adapter, fake Square). Run: php panel/onepanel-tv-renewal/tests/tv_renewal_test.php
 */
$root = dirname(__DIR__);
$work = sys_get_temp_dir() . '/tvr-test-' . getmypid();
@mkdir($work . '/api', 0777, true); @mkdir($work . '/includes', 0777, true);
copy("$root/api/tv-renewal.php", "$work/api/tv-renewal.php");
copy("$root/api/square-webhook.php", "$work/api/square-webhook.php");
copy("$root/includes/tv_renewal.php", "$work/includes/tv_renewal.php");
copy(__DIR__ . '/fake_bootstrap.php', "$work/includes/bootstrap.php");
$db = "$work/test.sqlite"; $state = "$work/state.json";
putenv("TVR_DB=$db"); putenv("TVR_STATE=$state");

$pdo = new PDO("sqlite:$db", null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
$pdo->exec("PRAGMA journal_mode=WAL");
$mysql = file_get_contents("$root/sql/migrations/2026-10-10-tv-renewal.sql");
// The migration itself, made SQLite-friendly (engine/charset and inline INDEX clauses removed).
$sqlite = preg_replace(['/\) ENGINE=[^;]*;/', '/^\s*INDEX [^\n]*\n/m', '/--[^\n]*/', '/ UNSIGNED/', '/,\s*\)/'], [');', '', '', '', ')'], $mysql);
$pdo->exec($sqlite);
$pdo->exec("CREATE TABLE customer_services (id INTEGER PRIMARY KEY, customer_id INT, service TEXT, username TEXT, provider_user_id TEXT, status TEXT, max_connections INT, expires_at TEXT, last_plan_months INT);
CREATE TABLE payments (id INTEGER PRIMARY KEY, customer_id INT, method TEXT, amount REAL, status TEXT, reference TEXT, paid_at TEXT);
CREATE TABLE payment_items (id INTEGER PRIMARY KEY, payment_id INT, service TEXT, months INT, amount REAL);
CREATE TABLE renewals (id INTEGER PRIMARY KEY, customer_id INT, service TEXT, payment_id INT, months INT, previous_expiration TEXT, new_expiration TEXT, state TEXT, provider_mode TEXT, provider_reference TEXT, completed_at TEXT);
CREATE TABLE activity_logs (id INTEGER PRIMARY KEY, admin_id INT, customer_id INT, action TEXT, details TEXT);");
$pdo->exec("INSERT INTO customer_services(customer_id,service,username,status,max_connections,expires_at,last_plan_months) VALUES(7,'flix_town','0048213977','expired',2,'2026-09-01 00:00:00',3)");

$past = gmdate('Y-m-d\TH:i:s', time() - 86400 * 5);
file_put_contents($state, json_encode(['users' => [
  '0048213977' => ['id' => 11, 'username' => '0048213977', 'password' => '0000731946205518', 'expires_at' => $past, 'max_connections' => 2, 'enabled' => true],
  '0011111111' => ['id' => 12, 'username' => '0011111111', 'password' => '1234', 'expires_at' => null, 'max_connections' => 1, 'enabled' => true],
  '0022222222' => ['id' => 13, 'username' => '0022222222', 'password' => '5678', 'expires_at' => $past, 'max_connections' => 1, 'enabled' => false],
  '0033333333' => ['id' => 14, 'username' => '0033333333', 'password' => '9999', 'expires_at' => $past, 'max_connections' => 10, 'enabled' => true],
]]));

$port = 18000 + getmypid() % 1000;
$server = proc_open([PHP_BINARY, '-S', "127.0.0.1:$port", '-t', $work], [1 => ['file', '/dev/null', 'w'], 2 => ['file', "$work/server.log", 'w']], $pipes, null, ['TVR_DB' => $db, 'TVR_STATE' => $state]);
for ($i = 0; $i < 50 && !@fsockopen('127.0.0.1', $port); $i++) usleep(100000);

$pass = 0; $fail = 0;
function check(string $name, bool $ok, string $extra = ''): void { global $pass, $fail; echo ($ok ? 'PASS  ' : 'FAIL  ') . $name . ($ok || $extra === '' ? '' : "  [$extra]") . "\n"; $ok ? $pass++ : $fail++; }
function call(array $body, string $method = 'POST', string $path = '/api/tv-renewal.php', array $headers = []): array {
  global $port;
  $ch = curl_init("http://127.0.0.1:$port$path");
  curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CUSTOMREQUEST => $method, CURLOPT_HTTPHEADER => array_merge(['Content-Type: application/json'], $headers)]);
  if ($method === 'POST') curl_setopt($ch, CURLOPT_POSTFIELDS, is_string($body['__raw'] ?? null) ? $body['__raw'] : json_encode($body));
  $raw = curl_exec($ch); $code = curl_getinfo($ch, CURLINFO_RESPONSE_CODE); curl_close($ch);
  return ['code' => $code, 'raw' => (string)$raw, 'json' => json_decode((string)$raw, true) ?: []];
}
function st(): array { return json_decode(file_get_contents(getenv('TVR_STATE')), true); }
function setst(callable $f): void { file_put_contents(getenv('TVR_STATE'), json_encode($f(st()))); }
function pay(string $order, string $pid, string $status, int $cents): void {
  setst(function ($s) use ($order, $pid, $status, $cents) { $s['orders'][$order]['tenders'] = [['id' => 'T' . $pid, 'payment_id' => $pid]];
    $s['payments'][$pid] = ['id' => $pid, 'status' => $status, 'order_id' => $order, 'amount_money' => ['amount' => $cents, 'currency' => 'USD']]; return $s; });
}
$U = '0048213977'; $P = '0000731946205518';

// ---- requests that must be refused ----
check('GET is refused', call([], 'GET')['code'] === 405);
check('non-JSON body is refused', call(['__raw' => 'username=x'])['json']['error'] === 'bad_request');
$r = call(['action' => 'context', 'username' => $U, 'password' => 'wrong']);
check('wrong password: 401, no session', $r['code'] === 401 && !isset($r['json']['session']) && $r['json']['error'] === 'auth');
check('unknown account: same answer as a wrong password', call(['action' => 'context', 'username' => '0099999999', 'password' => 'x'])['json']['error'] === 'auth');
check('quote without a session is refused', call(['action' => 'quote', 'months' => 1])['json']['error'] === 'session');

// ---- context ----
$r = call(['action' => 'context', 'username' => $U, 'password' => $P]); $c = $r['json'];
check('context: signed in with the line\'s own credentials', $r['code'] === 200 && !empty($c['session']));
check('context: Expired, expiry date, 2 devices', ($c['account']['status'] ?? '') === 'Expired' && ($c['account']['expires_at'] ?? 0) === strtotime($past . ' UTC') && ($c['account']['devices'] ?? 0) === 2);
check('context: current plan from OnePanel (3 Months)', ($c['account']['plan_months'] ?? 0) === 3 && ($c['account']['plan_label'] ?? '') === '3 Months');
check('context: plans and prices from OnePanel config', json_encode(array_map(fn($p) => $p['label'] . '=' . $p['price'], $c['plans'] ?? [])) === json_encode(['1 Month=15.00', '3 Months=40.00', '6 Months=75.00', '12 Months=135.00']));
check('context: renewable, Cash App Pay available', ($c['account']['renewable'] ?? false) === true && ($c['payments_available'] ?? false) === true);
check('context: nothing secret in the answer', !preg_match('/SQUARE-SECRET|flix-admin-token|0000731946205518|LOC1/', $r['raw']));
$S = $c['session'];

// ---- quote ----
check('quote: server price for 3 months', (call(['action' => 'quote', 'session' => $S, 'months' => 3])['json']['quote']['price'] ?? '') === '40.00');
check('quote: plan not offered is refused (2 months)', call(['action' => 'quote', 'session' => $S, 'months' => 2])['json']['error'] === 'plan');
check('quote: fractional or text months refused', call(['action' => 'quote', 'session' => $S, 'months' => 1.5])['json']['error'] === 'plan' && call(['action' => 'quote', 'session' => $S, 'months' => '1 OR 1'])['json']['error'] === 'plan');
check('quote: a price sent by the TV is ignored', (call(['action' => 'quote', 'session' => $S, 'months' => 1, 'price' => '0.01'])['json']['quote']['price'] ?? '') === '15.00');
check('tampered session refused', call(['action' => 'quote', 'session' => substr($S, 0, -2) . 'xx', 'months' => 1])['json']['error'] === 'session');

// ---- create ----
$r = call(['action' => 'create', 'session' => $S, 'months' => 1]); $p = $r['json']['payment'] ?? [];
$link = st()['square_calls'][0]['body'] ?? [];
check('create: waiting, $15.00, checkout URL for the QR', ($p['state'] ?? '') === 'waiting' && ($p['amount'] ?? '') === '15.00' && ($p['checkout_url'] ?? '') === 'https://square.link/u/test1');
check('create: Square link for 1500 USD with Cash App Pay', ($link['quick_pay']['price_money'] ?? null) === ['amount' => 1500, 'currency' => 'USD'] && ($link['checkout_options']['accepted_payment_methods']['cash_app_pay'] ?? false) === true);
check('create: no Square ids or secrets returned', !preg_match('/ORDER1|LINK1|SQUARE-SECRET|LOC1/', $r['raw']));
$id1 = $p['id'] ?? '';
check('status: unpaid stays waiting', (call(['action' => 'status', 'session' => $S, 'payment_id' => $id1])['json']['payment']['state'] ?? '') === 'waiting');

// another customer cannot see it
$other = call(['action' => 'context', 'username' => '0033333333', 'password' => '9999'])['json']['session'];
check('another account cannot read this payment', call(['action' => 'status', 'session' => $other, 'payment_id' => $id1])['json']['error'] === 'not_found');

// ---- processing, then completed ----
pay('ORDER1', 'PAY1', 'APPROVED', 1500);
check('status: approved payment is "processing" (not failed)', (call(['action' => 'status', 'session' => $S, 'payment_id' => $id1])['json']['payment']['state'] ?? '') === 'processing');
pay('ORDER1', 'PAY1', 'COMPLETED', 1500);
$r = call(['action' => 'status', 'session' => $S, 'payment_id' => $id1]); $p = $r['json']['payment'] ?? [];
$s = st(); $newIso = $s['users'][$U]['expires_at'];
check('status: completed payment renews: "renewed" with the new expiry', ($p['state'] ?? '') === 'renewed' && ($p['new_expires_at'] ?? 0) === strtotime($newIso . ' UTC'));
check('renewed 1 month at Flix Town', ($s['renews'] ?? []) === [[$U, 1]]);
for ($i = 0; $i < 3; $i++) call(['action' => 'status', 'session' => $S, 'payment_id' => $id1]);
check('repeated status checks never renew twice', count(st()['renews']) === 1);
$rows = $pdo->query("SELECT (SELECT COUNT(*) FROM payments) p,(SELECT COUNT(*) FROM renewals) r,(SELECT last_plan_months FROM customer_services) m,(SELECT status FROM customer_services) s,(SELECT amount FROM payments) a")->fetch();
check('recorded in OnePanel: payment $15, renewal, plan 1 month, active', (int)$rows['p'] === 1 && (int)$rows['r'] === 1 && (int)$rows['m'] === 1 && $rows['s'] === 'active' && (float)$rows['a'] === 15.0);
check('cancel after payment does not cancel', (call(['action' => 'cancel', 'session' => $S, 'payment_id' => $id1])['json']['payment']['state'] ?? '') === 'renewed');

// ---- wrong amount ----
$id2 = call(['action' => 'create', 'session' => $S, 'months' => 3])['json']['payment']['id'];
pay('ORDER2', 'PAY2', 'COMPLETED', 100);
check('paid amount differs from the price: needs assistance, no renewal', (call(['action' => 'status', 'session' => $S, 'payment_id' => $id2])['json']['payment']['state'] ?? '') === 'needs_assistance' && count(st()['renews']) === 1);

// ---- renewal fails after payment ----
$id3 = call(['action' => 'create', 'session' => $S, 'months' => 6])['json']['payment']['id'];
setst(function ($s) { $s['renew_fails'] = true; return $s; });
pay('ORDER3', 'PAY3', 'COMPLETED', 7500);
check('paid but Flix Town renewal fails: needs assistance (never "failed")', (call(['action' => 'status', 'session' => $S, 'payment_id' => $id3])['json']['payment']['state'] ?? '') === 'needs_assistance');
setst(function ($s) { unset($s['renew_fails']); return $s; });

// ---- failed payment ----
$id4 = call(['action' => 'create', 'session' => $S, 'months' => 1])['json']['payment']['id'];
pay('ORDER4', 'PAY4', 'FAILED', 1500);
check('declined payment: failed', (call(['action' => 'status', 'session' => $S, 'payment_id' => $id4])['json']['payment']['state'] ?? '') === 'failed');

// ---- cancel ----
$id5 = call(['action' => 'create', 'session' => $S, 'months' => 12])['json']['payment']['id'];
$r = call(['action' => 'cancel', 'session' => $S, 'payment_id' => $id5]);
check('cancel unpaid code: cancelled and the Square link deleted', ($r['json']['payment']['state'] ?? '') === 'cancelled' && in_array('LINK5', st()['deleted'] ?? [], true));

// ---- expiry ----
$id6 = call(['action' => 'create', 'session' => $S, 'months' => 1])['json']['payment']['id'];
$pdo->prepare('UPDATE tv_payments SET expires_ts=? WHERE id=?')->execute([time() - 5, $id6]);
check('unpaid code past its time: expired, link deleted', (call(['action' => 'status', 'session' => $S, 'payment_id' => $id6])['json']['payment']['state'] ?? '') === 'expired' && in_array('LINK6', st()['deleted'], true));
$id7 = call(['action' => 'create', 'session' => $S, 'months' => 1])['json']['payment']['id'];
$pdo->prepare('UPDATE tv_payments SET expires_ts=? WHERE id=?')->execute([time() - 5, $id7]);
pay('ORDER7', 'PAY7', 'COMPLETED', 1500);
check('paid just before the code expired: still renewed', (call(['action' => 'status', 'session' => $S, 'payment_id' => $id7])['json']['payment']['state'] ?? '') === 'renewed');

// ---- webhook (TV turned off) ----
$id8 = call(['action' => 'create', 'session' => $S, 'months' => 1])['json']['payment']['id'];
pay('ORDER8', 'PAY8', 'COMPLETED', 1500);
$body = json_encode(['type' => 'payment.updated', 'data' => ['object' => ['payment' => ['id' => 'PAY8', 'order_id' => 'ORDER8']]]]);
$bad = call(['__raw' => $body], 'POST', '/api/square-webhook.php', ['X-Square-HmacSha256-Signature: nope']);
check('webhook without a valid signature is ignored', $bad['code'] === 403 && $pdo->query("SELECT state FROM tv_payments WHERE id='$id8'")->fetchColumn() === 'waiting');
$sig = base64_encode(hash_hmac('sha256', 'https://onepanel.test/api/square-webhook.php' . $body, 'whkey', true));
call(['__raw' => $body], 'POST', '/api/square-webhook.php', ['X-Square-HmacSha256-Signature: ' . $sig]);
check('signed webhook renews without the TV asking', $pdo->query("SELECT state FROM tv_payments WHERE id='$id8'")->fetchColumn() === 'renewed' && count(st()['renews']) === 3);

// ---- accounts that cannot renew ----
$never = call(['action' => 'context', 'username' => '0011111111', 'password' => '1234'])['json'];
check('Never Expire: expires_at null, not renewable', $never['account']['expires_at'] === null && $never['account']['renewable'] === false && $never['account']['status'] === 'Active');
check('Never Expire: creating a payment is refused', call(['action' => 'create', 'session' => $never['session'], 'months' => 1])['json']['error'] === 'not_renewable');
$dis = call(['action' => 'context', 'username' => '0022222222', 'password' => '5678'])['json'];
check('disabled line: not renewable', $dis['account']['status'] === 'Disabled' && $dis['account']['renewable'] === false);
check('10 devices reported', (call(['action' => 'context', 'username' => '0033333333', 'password' => '9999'])['json']['account']['devices'] ?? 0) === 10);

// ---- password guessing ----
$codes = []; for ($i = 0; $i < 12; $i++) $codes[] = call(['action' => 'context', 'username' => $U, 'password' => 'guess' . $i])['code'];
check('repeated wrong passwords are throttled (429)', in_array(429, $codes, true));

// ---- library: session expiry ----
require "$work/includes/bootstrap.php"; require "$work/includes/tv_renewal.php";
$t = tvr_session_issue($U, time() - TVR_SESSION_TTL - 10);
check('session older than 30 minutes is refused', tvr_session_user($t) === null && tvr_session_user(tvr_session_issue($U)) === $U);

proc_terminate($server);
system("rm -rf " . escapeshellarg($work));
echo "\nTV renewal API: $pass passed, $fail failed\n";
exit($fail ? 1 : 0);
