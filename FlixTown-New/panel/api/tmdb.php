<?php
require __DIR__ . '/bootstrap.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') response(['error' => 'GET required'], 405);
$kind = (string)($_GET['kind'] ?? '');
$title = trim((string)($_GET['title'] ?? ''));
$year = (string)($_GET['year'] ?? '');
if (!in_array($kind, ['movie','series'], true) || $title === '' || strlen($title) > 160 || ($year !== '' && !preg_match('/^[0-9]{4}$/D', $year))) response(['error' => 'Invalid title request'], 400);
$searchTitle = trim((string)preg_replace('/\s*\((?:19|20)\d{2}\)\s*$/u', '', $title));
if ($year === '' && preg_match('/\(((?:19|20)\d{2})\)\s*$/u', $title, $yearMatch)) $year = $yearMatch[1];
$key = setting($db, 'tmdb_key');
if ($key === '') response(['cast' => [], 'trailer' => '']);
$cacheKey = hash('sha256', $kind . '|' . strtolower($title) . '|' . $year);
try {
    $q = $db->prepare('SELECT payload FROM tmdb_cache WHERE cache_key=? AND expires_at > UTC_TIMESTAMP()');
    $q->execute([$cacheKey]);
    $cached = $q->fetchColumn();
    if ($cached !== false) { echo $cached; exit; }
} catch (PDOException $e) { /* A missing optional cache table must not hide the cast. */ }
function tmdbGet(string $path, array $query): array {
    $url = 'https://api.themoviedb.org/3/' . $path . '?' . http_build_query($query);
    $ch = curl_init($url);
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 4, CURLOPT_TIMEOUT => 8, CURLOPT_FOLLOWLOCATION => false, CURLOPT_PROTOCOLS => CURLPROTO_HTTPS]);
    $body = curl_exec($ch);$status = curl_getinfo($ch, CURLINFO_RESPONSE_CODE);curl_close($ch);
    return $status === 200 && is_string($body) ? (json_decode($body, true) ?: []) : [];
}
$type = $kind === 'series' ? 'tv' : 'movie';
$q = ['api_key' => $key, 'query' => $searchTitle !== '' ? $searchTitle : $title, 'language' => 'en-US'];
if ($year !== '') $q[$type === 'tv' ? 'first_air_date_year' : 'year'] = $year;
$search = tmdbGet('search/' . $type, $q);
$result = $search['results'][0] ?? null;
if (!$result && $year !== '') {
    unset($q[$type === 'tv' ? 'first_air_date_year' : 'year']);
    $search = tmdbGet('search/' . $type, $q);
    $result = $search['results'][0] ?? null;
}
$cast = [];$trailer = '';
if (is_array($result) && !empty($result['id'])) {
    $id = (int)$result['id'];
    $credits = tmdbGet($type . '/' . $id . '/credits', ['api_key' => $key]);
    foreach (array_slice($credits['cast'] ?? [], 0, 15) as $actor) {
        if (!is_array($actor) || empty($actor['name'])) continue;
        $path = (string)($actor['profile_path'] ?? '');
        $cast[] = ['id' => (int)($actor['id'] ?? 0), 'name' => (string)$actor['name'], 'character' => (string)($actor['character'] ?? ''),
            'image' => substr($path, 0, 1) === '/' ? 'https://image.tmdb.org/t/p/w185' . $path : ''];
    }
    $videos = tmdbGet($type . '/' . $id . '/videos', ['api_key' => $key, 'language' => 'en-US']);
    foreach ($videos['results'] ?? [] as $video) if (($video['site'] ?? '') === 'YouTube' && ($video['type'] ?? '') === 'Trailer' && !empty($video['key'])) {
        $trailer = 'https://www.youtube.com/watch?v=' . rawurlencode((string)$video['key']);break;
    }
}
$payload = json_encode(['cast' => $cast, 'trailer' => $trailer], JSON_UNESCAPED_SLASHES);
$cacheHours = $cast || $trailer !== '' ? 168 : 1;
try {
    $save = $db->prepare('INSERT INTO tmdb_cache (cache_key,payload,expires_at) VALUES (?,?,UTC_TIMESTAMP() + INTERVAL ? HOUR) ON DUPLICATE KEY UPDATE payload=VALUES(payload),expires_at=VALUES(expires_at)');
    $save->execute([$cacheKey,$payload,$cacheHours]);
} catch (PDOException $e) { /* Continue serving the live response. */ }
echo $payload;
