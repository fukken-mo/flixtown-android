<?php
/*
 * Flix Town: TMDB ratings for catalog titles, used by the app's cards and details pages.
 *
 * POST {"items":[{"key":"movie:123","kind":"movie|series","tmdb":"603","title":"EN - The Matrix","year":1999}, …]}
 * (at most 12 items) returns {"ratings":{"movie:123":{"rating":8.2,"votes":26000,"tmdb_id":603}, "series:9":null}}.
 *
 * - "rating" is TMDB's vote_average (10-point scale) rounded to one decimal; null means there is no
 *   reliable rating (no unambiguous match, or fewer than 20 votes) and the app shows none.
 * - A key that is missing from "ratings" could not be checked right now (TMDB unreachable); the app
 *   asks again later. Only definite answers are cached (tmdb_cache: 7 days with a rating, 2 days without).
 * - Matching rules: see tmdb-match.php.
 */
require __DIR__ . '/bootstrap.php';
require __DIR__ . '/tmdb-match.php';

$body = postData();
$items = $body['items'] ?? null;
if (!is_array($items) || count($items) > 12) response(['error' => 'Send 1 to 12 items'], 400);
$key = setting($db, 'tmdb_key');
if ($key === '') response(['ratings' => (object)[], 'unavailable' => 'TMDB key not set in the panel']);

$want = [];
foreach ($items as $it) {
    if (!is_array($it)) continue;
    $k = (string)($it['key'] ?? '');
    $kind = (string)($it['kind'] ?? '');
    $tmdb = (string)($it['tmdb'] ?? '');
    $title = trim((string)($it['title'] ?? ''));
    $year = (int)($it['year'] ?? 0);
    if (!preg_match('/^[A-Za-z0-9:_.-]{1,64}$/D', $k) || !in_array($kind, ['movie', 'series'], true)
        || $title === '' || mb_strlen($title) > 160) continue;
    if (!preg_match('/^[0-9]{1,9}$/D', $tmdb)) $tmdb = '';
    [$clean, $nameYear] = ftCleanTitle($title);
    if ($year < 1870 || $year > 2100) $year = $nameYear;
    $type = $kind === 'series' ? 'tv' : 'movie';
    $steps = [];
    if ($tmdb !== '') $steps[] = 'id';
    if ($year > 0) $steps[] = 'year';
    $steps[] = 'search';
    $want[$k] = ['type' => $type, 'tmdb' => $tmdb, 'title' => $clean, 'year' => $year, 'steps' => $steps,
        'cache' => hash('sha256', 'rating-v2-release|' . $type . '|' . $tmdb . '|' . ftNormalize($clean) . '|' . $year)];
}

$order = array_keys($want);
$out = [];
$cached = ftCacheGet($db, array_column($want, 'cache'));
foreach ($want as $k => $w) {
    if (!isset($cached[$w['cache']])) continue;
    $value = json_decode($cached[$w['cache']], true);
    $out[$k] = is_array($value) && isset($value['rating']) ? $value : null;
    unset($want[$k]);
}

// Up to three rounds (ID lookup, search with year, search without year); each round runs in parallel.
$pending = $want;
for ($round = 0; $round < 3 && $pending; $round++) {
    $requests = [];
    foreach ($pending as $k => $w) {
        $step = $w['steps'][0];
        $requests[$k] = $step === 'id'
            ? [$w['type'] . '/' . $w['tmdb'], ['language' => 'en-US']]
            : ['search/' . $w['type'], ['query' => $w['title'], 'language' => 'en-US', 'include_adult' => 'false']
                + ($step === 'year' ? [($w['type'] === 'tv' ? 'first_air_date_year' : 'year') => $w['year']] : [])];
    }
    $answers = ftTmdbMulti($requests, $key);
    $next = [];
    foreach ($pending as $k => $w) {
        $data = $answers[$k] ?? null;
        if ($data === null) continue;                     // TMDB failed: no answer, nothing cached
        $step = array_shift($w['steps']);
        $match = $step === 'id'
            ? (ftIdAccept($data, $w['title'], $w['year']) ? $data : null)
            : ftPick(is_array($data['results'] ?? null) ? $data['results'] : [], $w['title'], $w['year']);
        if ($match === null && $w['steps']) { $next[$k] = $w; continue; }
        $rating = $match ? ftRating($match) : null;
        $value = $match ? ($rating ?? ['rating' => null, 'votes' => (int)($match['vote_count'] ?? 0)])
            + ['tmdb_id' => (int)$match['id'],
               'release_date' => (string)($match[$w['type'] === 'tv' ? 'first_air_date' : 'release_date'] ?? '')] : null;
        $out[$k] = $value;
        ftCacheSet($db, $w['cache'], json_encode($value ?? ['rating' => null, 'tmdb_id' => $match ? (int)$match['id'] : 0]),
            $value ? 7 * 86400 : 2 * 86400);
    }
    $pending = $next;
}
$sorted = [];
foreach ($order as $k) if (array_key_exists($k, $out)) $sorted[$k] = $out[$k];
response(['ratings' => (object)$sorted]);
