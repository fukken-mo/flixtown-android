<?php
declare(strict_types=1);

require_once $_SERVER['DOCUMENT_ROOT'] . '/bootstrap.php';
require_once FT_BACKEND_ROOT . '/includes/db.php';
require_once FT_BACKEND_ROOT . '/includes/http.php';
require_once FT_BACKEND_ROOT . '/includes/util.php';
require_once FT_BACKEND_ROOT . '/includes/crypto.php';
require_once FT_BACKEND_ROOT . '/includes/rate_limit.php';

ft_require_post();
$pdo = ft_db();

$body = ft_read_json_body();
$pairingId = (string) ($body['pairing_id'] ?? '');
$pollToken = (string) ($body['poll_token'] ?? '');

if ($pairingId === '' || $pollToken === '') {
    ft_json_error('Missing required fields');
}

if (!ft_rate_limit($pdo, 'pair_status', ft_client_ip(), 60, 60)) {
    ft_json_error('Too many requests', 429);
}

$stmt = $pdo->prepare('SELECT * FROM pairings WHERE pairing_id = ?');
$stmt->execute([$pairingId]);
$pairing = $stmt->fetch();

if ($pairing === false || !hash_equals($pairing['poll_token_hash'], hash('sha256', $pollToken))) {
    ft_json_response(['status' => 'error', 'error' => 'Pairing not found'], 404);
}

if ($pairing['status'] === 'pending' && strtotime((string) $pairing['expires_at']) < time()) {
    $upd = $pdo->prepare("UPDATE pairings SET status = 'expired' WHERE pairing_id = ?");
    $upd->execute([$pairingId]);
    ft_json_response(['status' => 'expired']);
}

if ($pairing['status'] === 'pending') {
    ft_json_response(['status' => 'pending']);
}

if ($pairing['status'] === 'completed') {
    try {
        $payload = json_decode(ft_decrypt((string) $pairing['encrypted_credentials']), true);
    } catch (Throwable $e) {
        error_log('[pair/status] decrypt failed: ' . $e->getMessage());
        ft_json_response(['status' => 'error', 'error' => 'Internal server error'], 500);
    }

    if (!is_array($payload)) {
        ft_json_response(['status' => 'error', 'error' => 'Internal server error'], 500);
    }

    ft_json_response([
        'status' => 'completed',
        'xtream_username' => $payload['xtream_username'] ?? null,
        'xtream_password' => $payload['xtream_password'] ?? null,
        'temp_device_token' => $payload['temp_device_token'] ?? null,
    ]);
}

// Anything else (already used, or an unexpected state) reads as expired to the TV.
ft_json_response(['status' => 'expired']);
