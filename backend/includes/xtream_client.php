<?php
declare(strict_types=1);

/**
 * The only place the backend talks to a customer's Xtream Codes panel.
 * Xtream is the source of truth for credential validity and account status;
 * this function is called both when a phone completes a QR pairing and when
 * a TV performs a manual login.
 *
 * @return array{ok: bool, status?: string, exp_date?: ?string, max_connections?: ?string, reason?: string}
 */
function ft_xtream_authenticate(string $baseUrl, string $username, string $password): array
{
    $baseUrl = rtrim($baseUrl, '/');
    if ($baseUrl === '' || !preg_match('#^https?://#i', $baseUrl)) {
        return ['ok' => false, 'reason' => 'invalid_server_url'];
    }

    $url = $baseUrl . '/player_api.php?' . http_build_query([
        'username' => $username,
        'password' => $password,
    ]);

    if (!function_exists('curl_init')) {
        return ['ok' => false, 'reason' => 'curl_unavailable'];
    }

    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT => 12,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_FOLLOWLOCATION => false,
    ]);
    $raw = curl_exec($ch);
    $curlError = curl_error($ch);
    curl_close($ch);

    if ($raw === false) {
        error_log('[xtream_client] ' . $curlError);
        return ['ok' => false, 'reason' => 'unreachable'];
    }

    $data = json_decode($raw, true);
    $userInfo = is_array($data) ? ($data['user_info'] ?? null) : null;
    if (!is_array($userInfo) || (int) ($userInfo['auth'] ?? 0) !== 1) {
        return ['ok' => false, 'reason' => 'invalid_credentials'];
    }

    return [
        'ok' => true,
        'status' => (string) ($userInfo['status'] ?? 'Unknown'),
        'exp_date' => isset($userInfo['exp_date']) ? (string) $userInfo['exp_date'] : null,
        'max_connections' => isset($userInfo['max_connections']) ? (string) $userInfo['max_connections'] : null,
    ];
}
