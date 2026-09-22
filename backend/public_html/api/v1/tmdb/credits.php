<?php
declare(strict_types=1);

require_once $_SERVER['DOCUMENT_ROOT'] . '/bootstrap.php';
require_once FT_BACKEND_ROOT . '/includes/db.php';
require_once FT_BACKEND_ROOT . '/includes/http.php';
require_once FT_BACKEND_ROOT . '/includes/util.php';
require_once FT_BACKEND_ROOT . '/includes/rate_limit.php';
require_once FT_BACKEND_ROOT . '/includes/tmdb_client.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    ft_json_error('Method not allowed', 405);
}

$pdo = ft_db();

if (!ft_rate_limit($pdo, 'tmdb_credits', ft_client_ip(), 120, 60)) {
    ft_json_error('Too many requests, please try again shortly', 429);
}

$stmt = $pdo->query('SELECT tmdb_enabled FROM app_settings WHERE id = 1');
$settings = $stmt->fetch();
if ($settings === false || !(bool) $settings['tmdb_enabled']) {
    ft_json_response(['enabled' => false, 'cast' => []]);
}

$type = (string) ($_GET['type'] ?? '');
$tmdbId = (int) ($_GET['tmdb_id'] ?? 0);

if (!in_array($type, ['movie', 'tv'], true) || $tmdbId <= 0) {
    ft_json_error('Invalid type or tmdb_id');
}

$result = ft_tmdb_credits($type, $tmdbId);
if (!$result['ok']) {
    if ($result['reason'] === 'tmdb_not_configured') {
        ft_json_response(['enabled' => false, 'cast' => []]);
    }
    error_log('[tmdb/credits] ' . $result['reason']);
    ft_json_error('Could not fetch credits', 502);
}

ft_json_response(['enabled' => true, 'cast' => $result['cast']]);
