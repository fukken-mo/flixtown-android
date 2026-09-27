<?php
declare(strict_types=1);
require __DIR__ . '/bootstrap.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') response(['error' => 'GET required'], 405);
$id = filter_input(INPUT_GET, 'id', FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
$name = trim((string)($_GET['name'] ?? ''));
if (!$id && ($name === '' || strlen($name) > 120)) response(['error' => 'Invalid actor'], 400);
$key = setting($db, 'tmdb_key');
if ($key === '') response(['titles' => []]);
$cacheKey = hash('sha256', 'actor|' . ($id ?: strtolower($name)));
$query = $db->prepare('SELECT payload FROM tmdb_cache WHERE cache_key=? AND expires_at > UTC_TIMESTAMP()');
$query->execute([$cacheKey]);
$cached = $query->fetchColumn();
if ($cached !== false) { header('Content-Type: application/json; charset=utf-8'); echo $cached; exit; }
function fetchTmdb(string $path, array $query): array {
    $ch = curl_init('https://api.themoviedb.org/3/' . $path . '?' . http_build_query($query));
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 4, CURLOPT_TIMEOUT => 8,
        CURLOPT_FOLLOWLOCATION => false, CURLOPT_PROTOCOLS => CURLPROTO_HTTPS]);
    $body = curl_exec($ch); $status = curl_getinfo($ch, CURLINFO_RESPONSE_CODE); curl_close($ch);
    return $status === 200 && is_string($body) ? (json_decode($body, true) ?: []) : [];
}
if (!$id) {
    $people = fetchTmdb('search/person', ['api_key' => $key, 'query' => $name]);
    $id = (int)($people['results'][0]['id'] ?? 0);
}
$credits = $id ? fetchTmdb('person/' . $id . '/combined_credits', ['api_key' => $key]) : [];
$titles = [];
foreach (array_slice($credits['cast'] ?? [], 0, 500) as $credit) {
    if (!is_array($credit) || !in_array($credit['media_type'] ?? '', ['movie','tv'], true)) continue;
    $title = $credit['media_type'] === 'movie' ? ($credit['title'] ?? '') : ($credit['name'] ?? '');
    if (!is_string($title) || $title === '') continue;
    $titles[] = ['kind' => $credit['media_type'] === 'movie' ? 'movie' : 'series', 'title' => $title];
}
$payload = json_encode(['titles' => $titles], JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
$save = $db->prepare('INSERT INTO tmdb_cache (cache_key,payload,expires_at) VALUES (?,?,UTC_TIMESTAMP() + INTERVAL 7 DAY) ON DUPLICATE KEY UPDATE payload=VALUES(payload),expires_at=VALUES(expires_at)');
$save->execute([$cacheKey,$payload]);
header('Content-Type: application/json; charset=utf-8');
echo $payload;
