<?php
declare(strict_types=1);

require_once __DIR__ . '/../../../../includes/db.php';
require_once __DIR__ . '/../../../../includes/http.php';
require_once __DIR__ . '/../../../../includes/util.php';
require_once __DIR__ . '/../../../../includes/rate_limit.php';
require_once __DIR__ . '/../../../../includes/audit_log.php';

ft_require_post();
$pdo = ft_db();

if (!ft_rate_limit($pdo, 'pair_start', ft_client_ip(), 20, 300)) {
    ft_json_error('Too many requests, please try again shortly', 429);
}

$body = ft_read_json_body();
$installationId = trim((string) ($body['installation_id'] ?? ''));
$deviceModel = mb_substr(trim((string) ($body['device_model'] ?? '')), 0, 100);

if (!preg_match('/^[a-fA-F0-9-]{8,64}$/', $installationId)) {
    ft_json_error('Invalid installation_id');
}

// Housekeeping: drop this installation's old pairing attempts so a repeated
// QR-pairing flow can't leave many stale rows behind.
$cleanup = $pdo->prepare("DELETE FROM pairings WHERE installation_id = ? AND status != 'completed'");
$cleanup->execute([$installationId]);

$expiresInSeconds = 600;
$pollToken = bin2hex(random_bytes(32));
$pollTokenHash = hash('sha256', $pollToken);
$pairingId = ft_uuid_v4();
$publicCode = null;

$maxAttempts = 5;
for ($attempt = 0; $attempt < $maxAttempts; $attempt++) {
    $candidateCode = ft_generate_public_code();
    try {
        $stmt = $pdo->prepare(
            'INSERT INTO pairings (pairing_id, public_code, poll_token_hash, installation_id, device_model, status, expires_at)
             VALUES (?, ?, ?, ?, ?, "pending", DATE_ADD(NOW(), INTERVAL ? SECOND))'
        );
        $stmt->execute([$pairingId, $candidateCode, $pollTokenHash, $installationId, $deviceModel, $expiresInSeconds]);
        $publicCode = $candidateCode;
        break;
    } catch (PDOException $e) {
        $isDuplicateKey = (int) ($e->errorInfo[1] ?? 0) === 1062;
        if ($isDuplicateKey && $attempt < $maxAttempts - 1) {
            continue; // public_code collision: retry with a freshly generated code
        }
        error_log('[pair/start] ' . $e->getMessage());
        ft_json_error('Could not start pairing', 500);
    }
}

if ($publicCode === null) {
    ft_json_error('Could not start pairing', 500);
}

ft_audit($pdo, 'pair_start', $installationId);

ft_json_response([
    'pairing_id' => $pairingId,
    'public_code' => $publicCode,
    'poll_token' => $pollToken,
    'expires_in_seconds' => $expiresInSeconds,
]);
