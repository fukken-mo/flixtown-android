<?php
require __DIR__ . '/bootstrap.php';
postData();
$verifier = bin2hex(random_bytes(32));
$expires = gmdate('Y-m-d H:i:s', time() + 600);
$ipHash = hash_hmac('sha256', (string)($_SERVER['REMOTE_ADDR'] ?? ''), $config['app_key']);
$db->prepare('DELETE FROM pairings WHERE created_at < UTC_TIMESTAMP() - INTERVAL 1 DAY')->execute();
$limit = $db->prepare('SELECT COUNT(*) FROM pairings WHERE created_ip_hash=? AND created_at > UTC_TIMESTAMP() - INTERVAL 1 HOUR');
$limit->execute([$ipHash]);
if ((int)$limit->fetchColumn() >= 20) response(['error' => 'Too many codes. Try again later'], 429);
for ($attempt = 0; $attempt < 4; $attempt++) {
    $code = (string)random_int(10000000, 99999999);
    try {
        $stmt = $db->prepare('INSERT INTO pairings (code,device_hash,created_ip_hash,created_at,expires_at) VALUES (?,?,?,UTC_TIMESTAMP(),?)');
        $stmt->execute([$code, hash('sha256', $verifier), $ipHash, $expires]);
        response(['code' => $code, 'verifier' => $verifier, 'expires_in' => 600,
            'activation_url' => $config['activation_url'] . '?code=' . $code]);
    } catch (PDOException $e) {
        if ($e->getCode() !== '23000') throw $e;
    }
}
response(['error' => 'Please retry'], 503);
