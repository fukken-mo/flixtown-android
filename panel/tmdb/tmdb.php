<?php
require __DIR__ . '/bootstrap.php';
require __DIR__ . '/tmdb-cast-match.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') response(['error' => 'GET required'], 405);
$kind = (string)($_GET['kind'] ?? '');
$title = trim((string)($_GET['title'] ?? ''));
$yearText = (string)($_GET['year'] ?? '');
if (!in_array($kind, ['movie', 'series'], true) || $title === '' || strlen($title) > 160 || ($yearText !== '' && !preg_match('/^(?:18|19|20)\d{2}$/D', $yearText))) response(['error' => 'Invalid title request'], 400);
[$searchTitle, $embeddedYear] = ftCleanTitle($title);
$year = $yearText !== '' ? (int)$yearText : $embeddedYear;
$empty = ['cast' => [], 'trailer' => '', 'match_version' => 2, 'match' => null];
$key = setting($db, 'tmdb_key');
if ($key === '') response($empty + ['unavailable' => 'TMDB key not set in the panel']);
$cacheKey = ftCastCacheKey($kind, $title, $year);
$cached = ftCacheGet($db, [$cacheKey]);
if (isset($cached[$cacheKey])) {
    $data = json_decode($cached[$cacheKey], true);
    if (is_array($data) && ($data['match_version'] ?? 0) === 2 && is_array($data['cast'] ?? null)) response($data);
}
$type = $kind === 'series' ? 'tv' : 'movie';
$query = ['query' => $searchTitle, 'language' => 'en-US'];
$yearField = $type === 'tv' ? 'first_air_date_year' : 'year';
if ($year > 0) $query[$yearField] = $year;
$search = ftTmdbMulti(['search' => ['search/' . $type, $query]], $key)['search'];
if ($search === null || !is_array($search['results'] ?? null)) response($empty + ['unavailable' => 'TMDB search unavailable'], 503);
$result = ftCastPick($search['results'], $title, $year);
if ($result === null && $year > 0) {
    unset($query[$yearField]);
    $search = ftTmdbMulti(['search' => ['search/' . $type, $query]], $key)['search'];
    if ($search === null || !is_array($search['results'] ?? null)) response($empty + ['unavailable' => 'TMDB search unavailable'], 503);
    $result = ftCastPick($search['results'], $title, $year);
}
if ($result === null) {
    ftCacheSet($db, $cacheKey, json_encode($empty, JSON_UNESCAPED_SLASHES), 3600);
    response($empty);
}
$id = (int)$result['id'];
$fetched = ftTmdbMulti([
    'record' => [$type . '/' . $id, ['language' => 'en-US']],
    'credits' => [$type . '/' . $id . '/credits', []],
    'videos' => [$type . '/' . $id . '/videos', ['language' => 'en-US']],
], $key);
// Verify the selected title again on its detail record before attaching people or trailers.
if ($fetched['record'] === null || $fetched['credits'] === null || $fetched['videos'] === null) response($empty + ['unavailable' => 'TMDB details unavailable'], 503);
$verified = ftCastPick([$fetched['record']], $title, $year);
if ($verified === null || (int)$verified['id'] !== $id) response($empty);
if ((int)($fetched['credits']['id'] ?? 0) !== $id || !is_array($fetched['credits']['cast'] ?? null) || (int)($fetched['videos']['id'] ?? 0) !== $id || !is_array($fetched['videos']['results'] ?? null)) response($empty + ['unavailable' => 'TMDB details unavailable'], 503);
$cast = [];
foreach (array_slice($fetched['credits']['cast'], 0, 15) as $actor) {
    if (!is_array($actor) || !is_string($actor['name'] ?? null) || $actor['name'] === '') continue;
    $path = (string)($actor['profile_path'] ?? '');
    $cast[] = ['id' => (int)($actor['id'] ?? 0), 'name' => $actor['name'], 'character' => (string)($actor['character'] ?? ''),
        'image' => preg_match('~^/[A-Za-z0-9_.-]+$~D', $path) ? 'https://image.tmdb.org/t/p/w185' . $path : ''];
}
$trailer = '';
foreach ($fetched['videos']['results'] as $video) {
    if (($video['site'] ?? '') === 'YouTube' && ($video['type'] ?? '') === 'Trailer' && preg_match('/^[A-Za-z0-9_-]{11}$/D', (string)($video['key'] ?? ''))) {
        $trailer = 'https://www.youtube.com/watch?v=' . $video['key']; break;
    }
}
$payload = ['cast' => $cast, 'trailer' => $trailer, 'match_version' => 2,
    'match' => ['tmdb_id' => $id, 'title' => (string)($verified['title'] ?? $verified['name'] ?? ''), 'year' => ftYearOf($verified), 'kind' => $kind]];
ftCacheSet($db, $cacheKey, json_encode($payload, JSON_UNESCAPED_SLASHES), 604800);
response($payload);
