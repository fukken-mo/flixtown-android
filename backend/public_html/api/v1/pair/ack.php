<?php
declare(strict_types=1);

require_once $_SERVER['DOCUMENT_ROOT'] . '/bootstrap.php';
require_once FT_BACKEND_ROOT . '/includes/db.php';
require_once FT_BACKEND_ROOT . '/includes/http.php';
require_once FT_BACKEND_ROOT . '/includes/util.php';
require_once FT_BACKEND_ROOT . '/includes/crypto.php';
require_once FT_BACKEND_ROOT . '/includes/rate_limit.php';
require_once FT_BACKEND_ROOT . '/includes/audit_log.php';

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
$isReplay = false;

$pdo->beginTransaction();
try {
    // Row lock: two ack calls for the same pairing_id (a client retry racing
    // the original request, or a genuine retry after a dropped response)
    // serialize here instead of both promoting a session.
    $stmt = $pdo->prepare('SELECT * FROM pairings WHERE pairing_id = ? FOR UPDATE');
    $stmt->execute([$pairingId]);
    $pairing = $stmt->fetch();

    if ($pairing === false || !hash_equals($pairing['poll_token_hash'], hash('sha256', $pollToken))) {
        $pdo->rollBack();
        ft_json_error('Pairing not found', 404);
    }

    $installationIdForAudit = $pairing['installation_id'];

    if ($pairing['status'] === 'acked') {
        // Delivery retry: the first ack already succeeded and promoted a
        // device session, but the TV never received (or is re-sending
        // because it never received) that response. Replay the SAME device
        // token instead of minting a new one, so a retry can never result in
        // two valid tokens for one pairing. The row is kept around (not
        // deleted) for exactly this purpose until the cleanup cron reaps it;
        // see tools/cleanup_pairings.php.
        $payload = json_decode(ft_decrypt((string) $pairing['encrypted_credentials']), true);
        if (!is_array($payload) || !hash_equals((string) ($payload['temp_device_token'] ?? ''), $tempDeviceToken)) {
            $pdo->rollBack();
            ft_json_error('Invalid temporary device token', 403);
        }
        $deviceToken = ft_decrypt((string) $pairing['issued_device_token_encrypted']);
        $isReplay = true;
        $pdo->commit();
    } elseif ($pairing['status'] === 'completed') {
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

        // Mark acked (not deleted) and remember the plaintext token, encrypted
        // at rest, so a delivery retry above can replay it. The permanent
        // session in `devices` already exists at this point either way.
        $update = $pdo->prepare(
            "UPDATE pairings SET status = 'acked', issued_device_token_encrypted = ? WHERE pairing_id = ?"
        );
        $update->execute([ft_encrypt($deviceToken), $pairingId]);

        $pdo->commit();
    } else {
        $pdo->rollBack();
        ft_json_error('Pairing is not ready to be acknowledged', 409);
    }
} catch (Throwable $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[pair/ack] ' . $e->getMessage());
    ft_json_error('Internal server error', 500);
}

ft_audit($pdo, $isReplay ? 'pair_ack_replay' : 'pair_ack', $installationIdForAudit);

ft_json_response(['device_token' => $deviceToken]);
