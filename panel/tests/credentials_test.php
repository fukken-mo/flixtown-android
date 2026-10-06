<?php
// php panel/tests/credentials_test.php  — exits non-zero on the first failure.
require_once __DIR__ . '/../accounts/flixtown-credentials.php';

$fail = 0;
function check($ok, $what) { global $fail; echo ($ok ? 'PASS  ' : 'FAIL  ') . $what . "\n"; if (!$ok) $fail = 1; }
$none = function ($u) { return false; };

$pairs = FlixCredentials::generateMany(1000, $none);
$shapeOk = true; $stringsOk = true; $leadingZero = false; $seen = array(); $digitCounts = array_fill(0, 10, 0);
foreach ($pairs as $p) {
    $shapeOk = $shapeOk && preg_match('/^[0-9]{10}$/', $p['username']) && preg_match('/^[0-9]{16}$/', $p['password']);
    $stringsOk = $stringsOk && is_string($p['username']) && is_string($p['password']);
    $leadingZero = $leadingZero || $p['username'][0] === '0';
    $seen[$p['username']] = true;
    foreach (str_split($p['username'] . $p['password']) as $d) $digitCounts[(int)$d]++;
}
check($shapeOk, '10-digit usernames and 16-digit passwords, digits 0-9 only');
check($stringsOk, 'returned as strings');
check($leadingZero, 'leading zeros occur and are kept (1000 usernames)');
check(count($seen) === 1000, 'bulk usernames unique among themselves');
// 26000 digits: each digit should land near 2600; a broken generator (e.g. never 0, or skewed) fails.
check(min($digitCounts) > 2300 && max($digitCounts) < 2900, 'all ten digits used evenly: ' . implode(',', $digitCounts));

$json = json_encode(array('username' => '0012345678', 'password' => '0000123456789012'));
$back = json_decode($json, true);
check($json === '{"username":"0012345678","password":"0000123456789012"}' && $back['username'] === '0012345678', 'JSON keeps leading zeros as strings');

$calls = 0;
$taken = function ($u) use (&$calls) { $calls++; return $calls <= 3; };
$p = FlixCredentials::generate($taken);
check($calls === 4 && strlen($p['username']) === 10, 'regenerates after collisions (3 taken, 4th free)');

$always = function ($u) { return true; };
try { FlixCredentials::generate($always); check(false, 'gives up when every username is taken'); }
catch (RuntimeException $e) { check(true, 'gives up when every username is taken (no duplicate returned)'); }

$p = FlixCredentials::fillMissing('custom7', 'Secret9', $none);
check($p === array('username' => 'custom7', 'password' => 'Secret9'), 'operator-supplied credentials kept exactly');
$p = FlixCredentials::fillMissing('0099', '', $none);
check($p['username'] === '0099' && preg_match('/^[0-9]{16}$/', $p['password']), 'only the blank field is generated');

$src = '';
foreach (token_get_all(file_get_contents(__DIR__ . '/../accounts/flixtown-credentials.php')) as $t) {
    if (!is_array($t) || ($t[0] !== T_COMMENT && $t[0] !== T_DOC_COMMENT)) $src .= is_array($t) ? $t[1] : $t;
}
check(!preg_match('/\b(mt_rand|rand|uniqid|microtime|time|lcg_value)\s*\(/', $src), 'no predictable sources (rand/mt_rand/uniqid/time)');

exit($fail);
