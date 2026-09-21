<?php
declare(strict_types=1);

require_once $_SERVER['DOCUMENT_ROOT'] . '/bootstrap.php';
require_once FT_BACKEND_ROOT . '/includes/db.php';
require_once FT_BACKEND_ROOT . '/includes/http.php';
require_once FT_BACKEND_ROOT . '/includes/util.php';
require_once FT_BACKEND_ROOT . '/includes/xtream_client.php';
require_once FT_BACKEND_ROOT . '/includes/rate_limit.php';
require_once FT_BACKEND_ROOT . '/includes/audit_log.php';

ft_require_post();
$pdo = ft_db();

if (!ft_rate_limit($pdo, 'auth_register_ip', ft_client_ip(), 15, 300)) {
    ft_json_error('Too many attempts, please try again shortly', 429);
}

$body = ft_read_json_body();
$installationId = trim((string) ($body['installation_id'] ?? ''));
$deviceModel = mb_substr(trim((string) ($body['device_model'] ?? '')), 0, 100);
$username = trim((string) ($body['xtream_username'] ?? ''));
$password = (string) ($body['xtream_password'] ?? '');

if (!preg_match('/^[a-fA-F0-9-]{8,64}$/', $installationId) || $username === '' || $password === '') {
    ft_json_error('Missing or invalid fields');
}

if (!ft_rate_limit($pdo, 'auth_register_install', $installationId, 10, 300)) {
    ft_json_error('Too many attempts, please try again shortly', 429);
}

$xtreamBaseUrl = (string) $pdo->query('SELECT xtream_base_url FROM app_settings WHERE id = 1')->fetchColumn();

$auth = ft_xtream_authenticate($xtreamBaseUrl, $username, $password);
if (!$auth['ok']) {
    ft_audit($pdo, 'auth_register_failed', $installationId, (string) $auth['reason']);
    ft_json_error('Invalid Xtream username or password', 401);
}

$deviceToken = bin2hex(random_bytes(32));
$deviceTokenHash = hash('sha256', $deviceToken);

try {
    $upsert = $pdo->prepare(
        'INSERT INTO devices (installation_id, device_model, device_token_hash, xtream_username, status, last_seen_at)
         VALUES (?, ?, ?, ?, "active", NOW())
         ON DUPLICATE KEY UPDATE device_model = VALUES(device_model), device_token_hash = VALUES(device_token_hash),
             xtream_username = VALUES(xtream_username), status = "active", last_seen_at = NOW()'
    );
    $upsert->execute([$installationId, $deviceModel, $deviceTokenHash, $username]);
} catch (Throwable $e) {
    error_log('[auth/register] ' . $e->getMessage());
    ft_json_error('Internal server error', 500);
}

ft_audit($pdo, 'auth_register', $installationId);

$status = strtolower((string) $auth['status']) === 'active' ? 'active' : 'expired';
ft_json_response(['device_token' => $deviceToken, 'status' => $status]);
