<?php
$_SERVER['SCRIPT_FILENAME'] = __FILE__;
require __DIR__ . '/../tmdb/tmdb-cast-match.php';
$count = 0;
function expect($actual, $expected, string $label): void {
    global $count; $count++;
    if ($actual !== $expected) throw new RuntimeException($label . ': ' . json_encode($actual));
}
// Synthetic identity fixtures, not assertions about live TMDB identifiers.
$right = ['id'=>101, 'title'=>'The Beast', 'release_date'=>'2026-10-09'];
$wrong = ['id'=>102, 'title'=>'Heart of the Beast', 'release_date'=>'2026-09-01', 'vote_count'=>999999];
$mma = ['id'=>103, 'title'=>'Beast', 'release_date'=>'2026-04-01'];
$older = ['id'=>104, 'title'=>'The Beast', 'release_date'=>'2023-01-01'];
expect(ftCastPick([$wrong,$right,$mma,$older], 'The Beast', 2026)['id'] ?? null, 101, 'wrong first result rejected');
expect(ftCastPick([$wrong], 'The Beast', 2026), null, 'partial title rejected');
expect(ftCastPick([$mma], 'The Beast', 2026), null, 'leading article differentiates titles');
expect(ftCastPick([$older], 'The Beast', 2026), null, 'wrong year rejected');
expect(ftCastPick([$right,$older], 'The Beast', 0), null, 'unknown-year remake ambiguity');
expect(ftCastPick([$right,$right], 'The Beast', 2026)['id'] ?? null, 101, 'duplicate ID deduplicated');
expect(ftCastPick([$right,['id'=>105,'title'=>'The Beast','release_date'=>'2026-02-01']], 'The Beast', 2026), null, 'same year ambiguity rejected');
expect(ftCastPick([['id'=>106,'title'=>'The Beast','release_date'=>'2025-01-01'],$right], 'The Beast', 2026)['id'] ?? null, 101, 'exact year preferred');
expect(ftCastPick([['id'=>106,'title'=>'The Beast','release_date'=>'2025-01-01']], 'The Beast', 2026)['id'] ?? null, 106, 'unique adjacent release year');
expect(ftCastPick([['id'=>106,'title'=>'The Beast']], 'The Beast', 2026), null, 'missing year rejected');
expect(ftCastPick([$right], 'EN - The Beast (2026) [4K]', 2026)['id'] ?? null, 101, 'provider decorations');
expect(ftCastPick([['id'=>110,'name'=>'The Office','first_air_date'=>'2005-01-01']], 'The Office', 2005)['id'] ?? null, 110, 'series title and date');
expect(ftCastPick([['id'=>111,'title'=>'Local name','original_title'=>'The Beast','release_date'=>'2026-01-01']], 'The Beast', 2026)['id'] ?? null, 111, 'original title accepted');
expect(ftCastCacheKey('movie','The Beast',2026) === hash('sha256','movie|the beast|2026'), false, 'old cache bypassed');
expect(ftCastCacheKey('movie','The Beast',2026) === ftCastCacheKey('series','The Beast',2026), false, 'movie and series cache separate');
echo "cast matcher: $count passed\n";
