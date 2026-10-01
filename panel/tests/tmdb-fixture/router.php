<?php
// Fake TMDB API for tmdb_http_test.sh. Logs every request so the test can count calls.
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
parse_str((string)parse_url($_SERVER['REQUEST_URI'], PHP_URL_QUERY), $q);
file_put_contents(__DIR__ . '/requests.log', $_SERVER['REQUEST_URI'] . "\n", FILE_APPEND);
header('Content-Type: application/json');
if (($q['api_key'] ?? '') !== 'test-key') { http_response_code(401); exit('{"status_message":"Invalid API key"}'); }
if (is_file(__DIR__ . '/down')) { http_response_code(503); exit('{}'); }
$movies = [
    603 => ['id' => 603, 'title' => 'The Matrix', 'original_title' => 'The Matrix', 'release_date' => '1999-03-30', 'vote_average' => 8.217, 'vote_count' => 26000],
    604 => ['id' => 604, 'title' => 'The Matrix Reloaded', 'original_title' => 'The Matrix Reloaded', 'release_date' => '2003-05-15', 'vote_average' => 7.0, 'vote_count' => 11000],
    948 => ['id' => 948, 'title' => 'Halloween', 'original_title' => 'Halloween', 'release_date' => '1978-10-25', 'vote_average' => 7.6, 'vote_count' => 6000],
    424139 => ['id' => 424139, 'title' => 'Halloween', 'original_title' => 'Halloween', 'release_date' => '2018-10-18', 'vote_average' => 6.5, 'vote_count' => 5000],
    77 => ['id' => 77, 'title' => 'Tiny Film', 'original_title' => 'Tiny Film', 'release_date' => '2024-01-01', 'vote_average' => 10.0, 'vote_count' => 2],
];
$tv = [
    1399 => ['id' => 1399, 'name' => 'Game of Thrones', 'original_name' => 'Game of Thrones', 'first_air_date' => '2011-04-17', 'vote_average' => 8.456, 'vote_count' => 24000],
    2316 => ['id' => 2316, 'name' => 'The Office', 'original_name' => 'The Office', 'first_air_date' => '2005-03-24', 'vote_average' => 8.6, 'vote_count' => 4000],
    2996 => ['id' => 2996, 'name' => 'The Office', 'original_name' => 'The Office', 'first_air_date' => '2001-07-09', 'vote_average' => 8.1, 'vote_count' => 1300],
];
if (preg_match('~/3/(movie|tv)/(\d+)$~', $path, $m)) {
    $set = $m[1] === 'movie' ? $movies : $tv;
    if (!isset($set[(int)$m[2]])) { http_response_code(404); exit('{"status_code":34}'); }
    exit(json_encode($set[(int)$m[2]]));
}
if (preg_match('~/3/search/(movie|tv)$~', $path, $m)) {
    $set = $m[1] === 'movie' ? $movies : $tv;
    $year = (int)($q['year'] ?? $q['first_air_date_year'] ?? 0);
    $words = strtolower($q['query'] ?? '');
    $results = [];
    foreach ($set as $r) {
        $t = strtolower($r['title'] ?? $r['name']);
        if (!str_contains($t, $words)) continue;
        if ($year && substr($r['release_date'] ?? $r['first_air_date'], 0, 4) !== (string)$year) continue;
        $results[] = $r;
    }
    exit(json_encode(['page' => 1, 'results' => $results]));
}
if ($path === '/3/trending/all/week') {
    $page = (int)($q['page'] ?? 1);
    $all = [
        $movies[424139] + ['media_type' => 'movie'], $tv[1399] + ['media_type' => 'tv'],
        ['id' => 1, 'media_type' => 'person', 'name' => 'Someone'],
        ['id' => 555, 'media_type' => 'movie', 'title' => 'Not On Server', 'release_date' => '2026-09-01', 'vote_average' => 7, 'vote_count' => 50],
        $movies[603] + ['media_type' => 'movie'], $movies[77] + ['media_type' => 'movie'],
    ];
    exit(json_encode(['page' => $page, 'results' => $page === 1 ? array_slice($all, 0, 3) : ($page === 2 ? array_slice($all, 3) : [])]));
}
http_response_code(404); echo '{}';
