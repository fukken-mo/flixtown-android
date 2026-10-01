<?php
/*
 * Flix Town: this week's TMDB trending movies and TV shows (trending/all/week, first 3 pages, up to
 * 60 titles). The app keeps only the titles it can match to the Xtream catalog, in TMDB's order, so
 * "Trending Now" lists real trending titles that are available on the server. Cached 6 hours; if
 * TMDB is unreachable the last list is served (marked "stale") rather than nothing.
 *
 * GET → {"items":[{"rank":1,"media":"movie|tv","id":693134,"title":"…","original":"…","year":2024,
 *                  "rating":8.2|null,"votes":5120}, …], "updated":"2026-10-01T12:00:00Z"}
 */
require __DIR__ . '/bootstrap.php';
require __DIR__ . '/tmdb-match.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') response(['error' => 'GET required'], 405);
$key = setting($db, 'tmdb_key');
if ($key === '') response(['items' => [], 'unavailable' => 'TMDB key not set in the panel']);

$cacheKey = hash('sha256', 'trending-v1');
$fresh = ftCacheGet($db, [$cacheKey]);
if (isset($fresh[$cacheKey])) { echo $fresh[$cacheKey]; exit; }

$pages = ftTmdbMulti([
    1 => ['trending/all/week', ['language' => 'en-US', 'page' => 1]],
    2 => ['trending/all/week', ['language' => 'en-US', 'page' => 2]],
    3 => ['trending/all/week', ['language' => 'en-US', 'page' => 3]],
], $key);
$items = [];
$seen = [];
if ($pages[1] !== null) {
    foreach ([1, 2, 3] as $p) foreach (($pages[$p]['results'] ?? []) as $r) {
        $media = (string)($r['media_type'] ?? '');
        if (!is_array($r) || !in_array($media, ['movie', 'tv'], true) || empty($r['id'])) continue;
        if (isset($seen[$media . $r['id']])) continue;
        $seen[$media . $r['id']] = true;
        $rating = ftRating($r);
        $items[] = ['rank' => count($items) + 1, 'media' => $media, 'id' => (int)$r['id'],
            'title' => (string)($r['title'] ?? $r['name'] ?? ''), 'original' => (string)($r['original_title'] ?? $r['original_name'] ?? ''),
            'year' => ftYearOf($r), 'rating' => $rating['rating'] ?? null, 'votes' => (int)($r['vote_count'] ?? 0)];
    }
}
if (!$items) {
    $stale = ftCacheGet($db, [$cacheKey], true);
    if (isset($stale[$cacheKey])) {
        $old = json_decode($stale[$cacheKey], true);
        if (is_array($old)) response($old + ['stale' => true]);
    }
    response(['items' => [], 'error' => 'TMDB trending is unavailable right now'], 502);
}
$payload = json_encode(['items' => $items, 'updated' => gmdate('Y-m-d\TH:i:s\Z')], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
ftCacheSet($db, $cacheKey, $payload, 6 * 3600);
echo $payload;
