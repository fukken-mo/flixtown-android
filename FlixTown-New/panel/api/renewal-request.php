<?php
require __DIR__ . '/bootstrap.php';
$input = postData();
$username = trim((string)($input['username'] ?? ''));
$phone = trim((string)($input['phone'] ?? ''));
$plan = (string)($input['plan'] ?? '');
if ($username === '' || strlen($username) > 128 || !preg_match('/^[+0-9(). -]{7,32}$/D', $phone) || !in_array($plan, ['1m','3m','6m','12m'], true)) response(['error' => 'Check account, phone and plan'], 400);
$ip = hash_hmac('sha256', (string)($_SERVER['REMOTE_ADDR'] ?? ''), $config['app_key']);
$q = $db->prepare('SELECT COUNT(*) FROM renewal_requests WHERE username=? AND ip_hash=? AND created_at > UTC_TIMESTAMP() - INTERVAL 1 DAY');
$q->execute([$username,$ip]);
if ((int)$q->fetchColumn() >= 3) response(['error' => 'A recent request is already on file'], 429);
$q = $db->prepare('INSERT INTO renewal_requests (username,phone,plan,ip_hash,created_at) VALUES (?,?,?,?,UTC_TIMESTAMP())');
$q->execute([$username,$phone,$plan,$ip]);
response(['status' => 'pending']);
