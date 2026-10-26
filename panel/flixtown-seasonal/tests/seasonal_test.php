<?php
// php panel/flixtown-seasonal/tests/seasonal_test.php — no network, no database.
declare(strict_types=1);
define('FLIX_SEASONAL_TEST', true);
require dirname(__DIR__) . '/upload/api/flixtown-seasonal/SeasonalStore.php';
$pass = 0; $fail = 0;
function check(string $name, bool $ok): void { global $pass, $fail; echo ($ok ? "PASS  " : "FAIL  ") . $name . "\n"; $ok ? $pass++ : $fail++; }
$tmp = sys_get_temp_dir() . '/flix-seasonal-' . bin2hex(random_bytes(4)); mkdir("$tmp/data", 0777, true); mkdir("$tmp/art", 0777, true);
$store = new FlixSeasonalStore(['public_api_base' => 'https://panelsandapps.com/panels/flixtown2027/api/'], "$tmp/data", "$tmp/art");
$d = function (string $s) { return FlixSeasonalStore::day($s); };
$pick = function (array $s, string $date) use ($d) { $e = FlixSeasonalStore::select($s, $d($date)); return $e ? $e['id'] : 'default'; };
$def = FlixSeasonalStore::defaults();

// Same results as the app's SeasonalScheduleTest
foreach (['2027-02-10' => 'valentines', '2027-02-15' => 'valentines', '2027-02-16' => 'default', '2027-03-13' => 'st_patricks', '2027-07-05' => 'independence_day',
    '2026-10-15' => 'halloween', '2026-10-31' => 'halloween', '2026-11-01' => 'default', '2026-11-30' => 'default', '2026-12-01' => 'christmas',
    '2026-12-26' => 'christmas', '2026-12-27' => 'new_year', '2027-01-03' => 'new_year', '2027-01-04' => 'default', '2027-03-22' => 'easter',
    '2027-03-29' => 'easter', '2027-03-30' => 'default', '2028-04-10' => 'easter', '2026-11-23' => 'thanksgiving', '2026-11-27' => 'thanksgiving',
    '2026-11-28' => 'default', '2027-05-28' => 'memorial_day', '2027-05-31' => 'memorial_day', '2027-06-01' => 'default'] as $date => $want)
    check("selects $want on $date", $pick($def, $date) === $want);
check('Easter 2026/2027/2028', FlixSeasonalStore::easter(2026) === $d('2026-04-05') && FlixSeasonalStore::easter(2027) === $d('2027-03-28') && FlixSeasonalStore::easter(2028) === $d('2028-04-16'));
check('Thanksgiving 2026/2027', FlixSeasonalStore::thanksgiving(2026) === $d('2026-11-26') && FlixSeasonalStore::thanksgiving(2027) === $d('2027-11-25'));
check('Memorial Day 2026/2027', FlixSeasonalStore::memorial(2026) === $d('2026-05-25') && FlixSeasonalStore::memorial(2027) === $d('2027-05-31'));

// Built-in schedule matches the app's built-in schedule
$java = file_get_contents(dirname(__DIR__, 3) . '/app/src/main/java/com/flixtown/tv/SeasonalSchedule.java');
preg_match('/DEFAULT_JSON="(.*?)";/s', $java, $m);
$appJson = json_decode(stripcslashes(preg_replace('/"\s*\+\s*"/', '', $m[1])), true);
$norm = function ($e) { unset($e['name'], $e['dates'], $e['image'], $e['image_sha256'], $e['enabled']); ksort($e); return $e; };
check('panel defaults equal the app defaults', is_array($appJson) && array_map($norm, $appJson['events']) === array_map($norm, $def['events']));

// Priority, tie and exact per-year dates
$s = ['enabled' => true, 'events' => [
    ['id' => 'long_b', 'enabled' => true, 'priority' => 40, 'rule' => 'fixed', 'start' => '10-01', 'end' => '10-31'],
    ['id' => 'short_c', 'enabled' => true, 'priority' => 40, 'rule' => 'fixed', 'start' => '10-20', 'end' => '10-22'],
    ['id' => 'same_a', 'enabled' => true, 'priority' => 40, 'rule' => 'fixed', 'start' => '10-20', 'end' => '10-22'],
    ['id' => 'top', 'enabled' => true, 'priority' => 90, 'rule' => 'fixed', 'start' => '10-30', 'end' => '10-31']]];
check('priority, then shorter range, then id', $pick($s, '2026-10-05') === 'long_b' && $pick($s, '2026-10-21') === 'same_a' && $pick($s, '2026-10-31') === 'top');
$e = ['id' => 'easter', 'enabled' => true, 'priority' => 40, 'rule' => 'easter', 'start_offset' => -2, 'end_offset' => 0, 'dates' => ['2027' => ['2027-03-01', '2027-03-03']]];
check('exact dates replace the rule for that year only', $pick(['enabled' => true, 'events' => [$e]], '2027-03-02') === 'easter' && $pick(['enabled' => true, 'events' => [$e]], '2027-03-27') === 'default' && $pick(['enabled' => true, 'events' => [$e]], '2028-04-15') === 'easter');
check('schedule off selects nothing', FlixSeasonalStore::select(array_merge($def, ['enabled' => false]), $d('2026-12-10')) === null);

// Validation
$bad = [['events' => 'x'], ['events' => [['id' => 'Bad Id']]], ['events' => [['id' => 'a', 'rule' => 'fixed', 'start' => '13-01', 'end' => '01-01']]],
    ['events' => [['id' => 'a', 'rule' => 'moon']]], ['events' => [['id' => 'a', 'rule' => 'easter', 'start_offset' => 5, 'end_offset' => -5]]],
    ['events' => [['id' => 'a', 'rule' => 'fixed', 'start' => '01-01', 'end' => '01-02'], ['id' => 'a', 'rule' => 'fixed', 'start' => '01-01', 'end' => '01-02']]],
    ['events' => [['id' => 'a', 'rule' => 'fixed', 'start' => '01-01', 'end' => '01-02', 'dates' => ['2027' => ['2027-05-01', '2027-01-01']]]]]];
foreach ($bad as $i => $b) check("rejects malformed schedule #$i", FlixSeasonalStore::sanitize($b)[1] !== []);
list($clean) = FlixSeasonalStore::sanitize(['display_ms' => 99999, 'events' => [['id' => 'a', 'rule' => 'fixed', 'start' => '01-01', 'end' => '01-02', 'image' => '../../config.php']]]);
check('display time bounded, unsafe picture names dropped', $clean['display_ms'] === 3000 && $clean['events'][0]['image'] === '');

// Save: atomic, version raised; a bad save leaves the old file
check('save works', $store->save($def) === [] && $store->load()['version'] === 2);
check('bad save rejected and old schedule kept', $store->save(['events' => [['id' => 'BAD']]]) !== [] && $store->load()['version'] === 2);
check('no temporary files left', glob("$tmp/data/*.tmp") === []);

// Uploads
$img = function (int $w, int $h, string $path, string $type = 'png') { $i = imagecreatetruecolor($w, $h); imagefill($i, 0, 0, imagecolorallocate($i, 120, 20, 20)); $type === 'png' ? imagepng($i, $path) : imagejpeg($i, $path); imagedestroy($i); };
$img(1920, 1080, "$tmp/good.png"); $img(800, 450, "$tmp/small.png"); $img(1920, 1440, "$tmp/square.png");
file_put_contents("$tmp/script.jpg", "<?php echo 'x'; ?>");
$img(1920, 1080, "$tmp/poly.jpg", 'jpeg'); file_put_contents("$tmp/poly.jpg", "\n<?php system('id'); ?>", FILE_APPEND);
$up = function (string $f) { return ['error' => UPLOAD_ERR_OK, 'tmp_name' => $f, 'name' => basename($f), 'size' => filesize($f)]; };
list($name, $sha, $err) = $store->storeUpload($up("$tmp/good.png"), 'halloween');
check('16:9 picture accepted with a generated name', $err === null && preg_match('/^halloween-[0-9a-f]{16}\.jpg$/', (string)$name) === 1 && hash_file('sha256', "$tmp/art/$name") === $sha);
check('too small rejected', $store->storeUpload($up("$tmp/small.png"), 'halloween')[2] !== null);
check('not 16:9 rejected', $store->storeUpload($up("$tmp/square.png"), 'halloween')[2] !== null);
check('script disguised as .jpg rejected', $store->storeUpload($up("$tmp/script.jpg"), 'halloween')[2] !== null);
list($pn, , $perr) = $store->storeUpload($up("$tmp/poly.jpg"), 'christmas');
check('picture with appended PHP is re-encoded without it', $perr === null && strpos((string)file_get_contents("$tmp/art/$pn"), '<?php') === false);
check('bad event id rejected', $store->storeUpload($up("$tmp/good.png"), '../x')[2] !== null);
check('failed upload rejected', $store->storeUpload(['error' => UPLOAD_ERR_INI_SIZE], 'halloween')[2] !== null);

// Public schedule: absolute https links on the panel host; unused pictures removed on save
$s = $store->load();
foreach ($s['events'] as &$ev) if ($ev['id'] === 'halloween') { $ev['image'] = $name; $ev['image_sha256'] = $sha; }
unset($ev);
check('save with picture', $store->save($s) === []);
$pub = $store->publicSchedule(); $h = array_values(array_filter($pub['events'], function ($e) { return $e['id'] === 'halloween'; }))[0];
check('public link is https on the panel host', $h['image'] === "https://panelsandapps.com/panels/flixtown2027/api/seasonal-art/$name" && $h['image_sha256'] === $sha);
check('unused uploaded picture pruned', !is_file("$tmp/art/$pn") && is_file("$tmp/art/$name"));
$c = array_values(array_filter($pub['events'], function ($e) { return $e['id'] === 'christmas'; }))[0];
check('event without picture publishes no link', $c['image'] === '');

// Everyday design
list($en, $es, $eerr) = $store->storeUpload($up("$tmp/good.png"), 'everyday');
$s = $store->load(); $s['default_image'] = $en; $s['default_image_sha256'] = $es; $s['enabled'] = false;
check('everyday picture saved', $eerr === null && preg_match('/^everyday-[0-9a-f]{16}\.jpg$/', (string)$en) === 1 && $store->save($s) === []);
$pub = $store->publicSchedule();
check('everyday picture published as an https link, also with seasonal off', $pub['enabled'] === false && $pub['default_image'] === "https://panelsandapps.com/panels/flixtown2027/api/seasonal-art/$en" && $pub['default_image_sha256'] === $es);
check('everyday picture is not pruned', is_file("$tmp/art/$en"));
check('the id "everyday" is reserved', FlixSeasonalStore::sanitize(['events' => [['id' => 'everyday', 'rule' => 'fixed', 'start' => '01-01', 'end' => '01-02']]])[1] !== []);
$s['default_image'] = ''; $store->save($s);
check('removing the everyday picture deletes its file', !is_file("$tmp/art/$en") && $store->publicSchedule()['default_image'] === '');

array_map('unlink', glob("$tmp/*/*") ?: []); array_map('unlink', glob("$tmp/*.*") ?: []); @rmdir("$tmp/data"); @rmdir("$tmp/art"); @rmdir($tmp);
echo "\nseasonal panel: $pass passed, $fail failed\n";
exit($fail ? 1 : 0);
