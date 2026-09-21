<?php
declare(strict_types=1);

require_once __DIR__ . '/../../../../includes/db.php';
require_once __DIR__ . '/../../../../includes/http.php';
require_once __DIR__ . '/../../../../includes/util.php';
require_once __DIR__ . '/../../../../includes/crypto.php';
require_once __DIR__ . '/../../../../includes/rate_limit.php';
require_once __DIR__ . '/../../../../includes/audit_log.php';

ft_require_post();
$pdo = ft_db();

if (!ft_rate_limit($pdo, 'pair_ack', ft_client_ip(), 30, 60)) {
    ft_json_error('Too many requests', 429);
}

$body = ft_read_json_body();
$pairingId = (string) ($body['pairing_id'] ?? '');
$pollToken = (string) ($body['poll_token'] ?? '');
$tempDeviceToken = (string) ($body['temp_device_token'] ?? '');

if ($pairingId === '' || $pollToken === '' || $tempDeviceToken === '') {
    ft_json_error('Missing required fields');
}

$deviceToken = null;
$installationIdForAudit = null;

$pdo->beginTransaction();
try {
    // Row lock: two ack calls for the same pairing_id (a client retry racing
    // the original request) serialize here instead of both promoting a session.
    $stmt = $pdo->prepare('SELECT * FROM pairings WHERE pairing_id = ? FOR UPDATE');
    $stmt->execute([$pairingId]);
    $pairing = $stmt->fetch();

    if ($pairing === false || !hash_equals($pairing['poll_token_hash'], hash('sha256', $pollToken))) {
        $pdo->rollBack();
        ft_json_error('Pairing not found', 404);
    }

    $installationIdForAudit = $pairing['installation_id'];

    if ($pairing['status'] !== 'completed') {
        $pdo->rollBack();
        ft_json_error('Pairing is not ready to be acknowledged', 409);
    }

    $payload = json_decode(ft_decrypt((string) $pairing['encrypted_credentials']), true);
    if (!is_array($payload) || !hash_equals((string) ($payload['temp_device_token'] ?? ''), $tempDeviceToken)) {
        $pdo->rollBack();
        ft_json_error('Invalid temporary device token', 403);
    }

    $deviceToken = bin2hex(random_bytes(32));
    $deviceTokenHash = hash('sha256', $deviceToken);

    $upsert = $pdo->prepare(
        'INSERT INTO devices (installation_id, device_model, device_token_hash, xtream_username, status, last_seen_at)
         VALUES (?, ?, ?, ?, "active", NOW())
         ON DUPLICATE KEY UPDATE device_model = VALUES(device_model), device_token_hash = VALUES(device_token_hash),
             xtream_username = VALUES(xtream_username), status = "active", last_seen_at = NOW()'
    );
    $upsert->execute([
        $pairing['installation_id'],
        $pairing['device_model'],
        $deviceTokenHash,
        $payload['xtream_username'] ?? null,
    ]);

    // The pairing record's job is done: delete it now that a permanent
    // session exists, per the "temporary record deleted after ACK" policy.
    $del = $pdo->prepare('DELETE FROM pairings WHERE pairing_id = ?');
    $del->execute([$pairingId]);

    $pdo->commit();
} catch (Throwable $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[pair/ack] ' . $e->getMessage());
    ft_json_error('Internal server error', 500);
}

ft_audit($pdo, 'pair_ack', $installationIdForAudit);

ft_json_response(['device_token' => $deviceToken]);
