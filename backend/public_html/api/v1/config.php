<?php
declare(strict_types=1);

require_once __DIR__ . '/../../../includes/db.php';
require_once __DIR__ . '/../../../includes/http.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    ft_json_error('Method not allowed', 405);
}

try {
    $pdo = ft_db();

    $stmt = $pdo->query('SELECT * FROM app_settings WHERE id = 1');
    $settings = $stmt->fetch();
    if ($settings === false) {
        ft_json_error('Configuration not initialized', 500);
    }

    $prices = [];
    foreach ($pdo->query('SELECT duration_months, price FROM renewal_prices') as $row) {
        $prices[(string) $row['duration_months']] = (float) $row['price'];
    }

    $announcements = [];
    $stmt = $pdo->query('SELECT message FROM announcements WHERE is_active = 1 ORDER BY created_at DESC LIMIT 10');
    foreach ($stmt as $row) {
        $announcements[] = $row['message'];
    }

    // Note: tmdb_api_key is intentionally never included here. Only a flag
    // indicating enrichment is available leaves the backend; the key itself
    // stays server-side for the metadata-proxy endpoints.
    ft_json_response([
        'app_name' => $settings['app_name'],
        'xtream_base_url' => $settings['xtream_base_url'],
        'maintenance_mode' => (bool) $settings['maintenance_mode'],
        'maintenance_message' => $settings['maintenance_message'],
        'logo_url' => $settings['logo_url'],
        'intro_enabled' => (bool) $settings['intro_enabled'],
        'intro_video_url' => $settings['intro_video_url'],
        'min_app_version_code' => (int) $settings['min_app_version_code'],
        'latest_app_version_code' => (int) $settings['latest_app_version_code'],
        'force_update' => (bool) $settings['force_update'],
        'update_url' => $settings['update_url'],
        'cashapp_username' => $settings['cashapp_username'],
        'cashapp_url' => $settings['cashapp_url'],
        'tmdb_enabled' => (bool) $settings['tmdb_enabled'],
        'renewal_prices' => $prices,
        'announcements' => $announcements,
    ]);
} catch (Throwable $e) {
    error_log('[config] ' . $e->getMessage());
    ft_json_error('Internal server error', 500);
}
