<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') response(['error' => 'GET required'], 405);

// One shared panel cache keeps TMDB calls off the TV devices and avoids per-title lookups.
$cacheKey = hash('sha256', 'tmdb-ranking-lists-v1');
$readCache = static function () use ($db, $cacheKey): ?string {
    try {
        $query = $db->prepare('SELECT payload FROM tmdb_cache WHERE cache_key=? AND expires_at > UTC_TIMESTAMP()');
        $query->execute([$cacheKey]);
        $value = $query->fetchColumn();
        return is_string($value) ? $value : null;
    } catch (PDOException $e) {
        return null;
    }
};
$cached = $readCache();
if ($cached !== null) { echo $cached; exit; }

$key = setting($db, 'tmdb_key');
if ($key === '') response(['error' => 'TMDB key is not configured'], 503);
$lock = fopen(sys_get_temp_dir() . '/flixtown-tmdb-rankings.lock', 'c');
if ($lock !== false) flock($lock, LOCK_EX);
try {
    $cached = $readCache();
    if ($cached !== null) { echo $cached; exit; }
    $multi = curl_multi_init();
    $requests = [];
    foreach (['popular' => 2, 'top_rated' => 5] as $kind => $pages) {
        for ($page = 1; $page <= $pages; $page++) {
            $url = 'https://api.themoviedb.org/3/movie/' . $kind . '?' .
                http_build_query(['api_key' => $key, 'language' => 'en-US', 'page' => $page]);
            $handle = curl_init($url);
            curl_setopt_array($handle, [
                CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 3, CURLOPT_TIMEOUT => 9,
                CURLOPT_FOLLOWLOCATION => false, CURLOPT_PROTOCOLS => CURLPROTO_HTTPS,
            ]);
            curl_multi_add_handle($multi, $handle);
            $requests[] = [$kind, $handle];
        }
    }
    do {
        $status = curl_multi_exec($multi, $running);
        if ($running) curl_multi_select($multi, 1.0);
    } while ($running && $status === CURLM_OK);
    $lists = ['popular' => [], 'top_rated' => []];
    $success = 0;
    foreach ($requests as [$kind, $handle]) {
        if (curl_getinfo($handle, CURLINFO_RESPONSE_CODE) === 200) {
            $body = json_decode((string)curl_multi_getcontent($handle), true);
            if (is_array($body) && isset($body['results']) && is_array($body['results'])) {
                $success++;
                foreach ($body['results'] as $movie) {
                    if (!is_array($movie) || empty($movie['id']) || empty($movie['title'])) continue;
                    $lists[$kind][] = [
                        'id' => (int)$movie['id'], 'title' => (string)$movie['title'],
                        'year' => substr((string)($movie['release_date'] ?? ''), 0, 4),
                        'rating' => (float)($movie['vote_average'] ?? 0),
                        'votes' => (int)($movie['vote_count'] ?? 0),
                    ];
                }
            }
        }
        curl_multi_remove_handle($multi, $handle);
        curl_close($handle);
    }
    curl_multi_close($multi);
    if ($success === 0) response(['error' => 'TMDB is temporarily unavailable'], 503);
    $payload = json_encode($lists, JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
    try {
        $save = $db->prepare('INSERT INTO tmdb_cache (cache_key,payload,expires_at) VALUES (?,?,UTC_TIMESTAMP() + INTERVAL 24 HOUR) ON DUPLICATE KEY UPDATE payload=VALUES(payload),expires_at=VALUES(expires_at)');
        $save->execute([$cacheKey, $payload]);
    } catch (PDOException $e) {
        // A missing optional cache table does not prevent a response.
    }
    echo $payload;
} finally {
    if ($lock !== false) { flock($lock, LOCK_UN); fclose($lock); }
}
