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

try {
    $pdo = ft_db();

    // Search fans out to TMDB's own query matching (fuzzier, more expensive
    // than a direct credits-by-id lookup), so it gets a tighter window than
    // credits.
    if (!ft_rate_limit($pdo, 'tmdb_resolve', ft_client_ip(), 60, 60)) {
        ft_json_error('Too many requests, please try again shortly', 429);
    }

    if (!ft_tmdb_is_enabled($pdo)) {
        ft_json_response(['enabled' => false, 'tmdb_id' => null]);
    }

    $type = (string) ($_GET['type'] ?? '');
    $query = trim((string) ($_GET['query'] ?? ''));
    $yearRaw = $_GET['year'] ?? null;
    $year = ($yearRaw !== null && ctype_digit((string) $yearRaw)) ? (int) $yearRaw : null;

    if (!in_array($type, ['movie', 'tv'], true) || $query === '') {
        ft_json_error('Invalid type or query');
    }

    $result = ft_tmdb_resolve($type, $query, $year);
    if (!$result['ok']) {
        if ($result['reason'] === 'tmdb_not_configured') {
            ft_json_response(['enabled' => false, 'tmdb_id' => null]);
        }
        error_log('[tmdb/resolve] ' . $result['reason']);
        ft_json_error('Could not resolve title', 502);
    }

    ft_json_response(['enabled' => true, 'tmdb_id' => $result['tmdb_id']]);
} catch (Throwable $e) {
    error_log('[tmdb/resolve] ' . $e->getMessage());
    ft_json_error('Internal server error', 500);
}
