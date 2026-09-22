<?php
declare(strict_types=1);

require_once $_SERVER['DOCUMENT_ROOT'] . '/bootstrap.php';
require_once FT_BACKEND_ROOT . '/includes/db.php';
require_once FT_BACKEND_ROOT . '/includes/http.php';
require_once FT_BACKEND_ROOT . '/includes/util.php';
require_once FT_BACKEND_ROOT . '/includes/rate_limit.php';
require_once FT_BACKEND_ROOT . '/includes/tmdb_client.php';

/**
 * Safe, keyless self-check: lets an admin (or this app's developer) confirm
 * from a browser whether TMDB enrichment is actually wired up on THIS
 * deployment, without ever exposing the key itself or making a real TMDB
 * call. Two independent things have to both be true for cast photos to
 * work, and this reports them separately so a "still shows initials" report
 * is diagnosable in one request instead of guesswork:
 *   - tmdb_enabled: the admin-panel toggle (app_settings.tmdb_enabled)
 *   - key_configured: secrets.php has a non-empty tmdb_api_key
 */
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    ft_json_error('Method not allowed', 405);
}

try {
    $pdo = ft_db();

    if (!ft_rate_limit($pdo, 'tmdb_status', ft_client_ip(), 30, 60)) {
        ft_json_error('Too many requests, please try again shortly', 429);
    }

    ft_json_response([
        'tmdb_enabled' => ft_tmdb_is_enabled($pdo),
        'key_configured' => ft_tmdb_api_key() !== null,
    ]);
} catch (Throwable $e) {
    error_log('[tmdb/status] ' . $e->getMessage());
    ft_json_error('Internal server error', 500);
}
