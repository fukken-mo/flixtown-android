<?php
// php panel/tests/tmdb_match_test.php — title clean-up and match safeguards (no network, no database).
$_SERVER['SCRIPT_FILENAME'] = __FILE__;
require __DIR__ . '/../tmdb/tmdb-match.php';
$fail = 0;
function check(string $what, $got, $want): void {
    global $fail;
    $ok = $got === $want;
    if (!$ok) $fail++;
    echo ($ok ? 'PASS  ' : 'FAIL  ') . $what . ($ok ? '' : '  got ' . json_encode($got) . ' want ' . json_encode($want)) . "\n";
}
// Provider decorations
check('EN - prefix', ftCleanTitle('EN - The Matrix (1999)'), ['The Matrix', 1999]);
check('4K-EN prefix', ftCleanTitle('4K-EN - Dune: Part Two'), ['Dune: Part Two', 0]);
check('|EN| prefix', ftCleanTitle('|EN| Oppenheimer [4K]'), ['Oppenheimer', 0]);
check('[EN] prefix + year', ftCleanTitle('[EN] Barbie (2023)'), ['Barbie', 2023]);
check('US: prefix', ftCleanTitle('US: The Office'), ['The Office', 0]);
check('quality suffix', ftCleanTitle('Interstellar 1080p'), ['Interstellar', 0]);
check('CSI: kept', ftCleanTitle('CSI: Miami'), ['CSI: Miami', 0]);
check('number title kept', ftCleanTitle('Blade Runner 2049'), ['Blade Runner 2049', 0]);
check('1917 kept', ftCleanTitle('1917'), ['1917', 0]);
check('normalise', ftNormalize('EN - The Lord of the Rings: The Return of the King'), 'lord of the rings the return of the king');
check('accents + ampersand', ftNormalize('Amélie & Co.'), 'amelie and co');
check('apostrophe', ftNormalize("Schindler's List"), 'schindlers list');

// Search picks
$halloween = [
    ['id' => 948, 'title' => 'Halloween', 'original_title' => 'Halloween', 'release_date' => '1978-10-25', 'vote_average' => 7.6, 'vote_count' => 6000],
    ['id' => 424139, 'title' => 'Halloween', 'original_title' => 'Halloween', 'release_date' => '2018-10-18', 'vote_average' => 6.5, 'vote_count' => 5000],
    ['id' => 2082, 'title' => 'Halloween', 'original_title' => 'Halloween', 'release_date' => '2007-08-31', 'vote_average' => 5.9, 'vote_count' => 2400],
    ['id' => 999, 'title' => 'Halloween Kills', 'release_date' => '2021-10-14', 'vote_average' => 5.5, 'vote_count' => 3000],
];
check('year picks the right remake', ftPick($halloween, 'Halloween', 2018)['id'] ?? null, 424139);
check('year ±1 tolerated', ftPick($halloween, 'Halloween', 2019)['id'] ?? null, 424139);
check('no year + several exact titles = ambiguous', ftPick($halloween, 'Halloween', 0), null);
check('wrong year = no match', ftPick($halloween, 'Halloween', 1995), null);
check('partial title never matches', ftPick($halloween, 'Halloween Ends', 2022), null);
check('unique exact title without year', ftPick($halloween, 'EN - Halloween Kills', 0)['id'] ?? null, 999);
check('original title matches', ftPick([['id' => 5, 'title' => 'Spirited Away', 'original_title' => '千と千尋の神隠し', 'release_date' => '2001-07-20', 'vote_count' => 100]], '千と千尋の神隠し', 2001)['id'] ?? null, 5);
check('tv first_air_date', ftPick([['id' => 1399, 'name' => 'Game of Thrones', 'original_name' => 'Game of Thrones', 'first_air_date' => '2011-04-17', 'vote_count' => 20000]], 'Game Of Thrones', 2011)['id'] ?? null, 1399);

// TMDB ID safeguard
check('id accepted when title matches', ftIdAccept(['id' => 603, 'title' => 'The Matrix', 'release_date' => '1999-03-30'], 'EN - The Matrix', 1999), true);
check('wrong id rejected', ftIdAccept(['id' => 604, 'title' => 'The Matrix Reloaded', 'release_date' => '2003-05-15'], 'The Matrix', 1999), false);
check('id with wrong year rejected', ftIdAccept(['id' => 603, 'title' => 'The Matrix', 'release_date' => '1999-03-30'], 'The Matrix', 2021), false);

// Ratings
check('rating rounded', ftRating(['vote_average' => 8.237, 'vote_count' => 300]), ['rating' => 8.2, 'votes' => 300]);
check('too few votes = none', ftRating(['vote_average' => 10, 'vote_count' => 3]), null);
check('zero average = none', ftRating(['vote_average' => 0, 'vote_count' => 500]), null);
echo $fail ? "$fail failed\n" : "all passed\n";
exit($fail ? 1 : 0);
