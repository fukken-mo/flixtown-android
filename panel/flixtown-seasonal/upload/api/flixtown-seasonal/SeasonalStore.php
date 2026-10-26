<?php
/**
 * Flix Town seasonal splash: schedule storage, validation, selection and picture uploads.
 * File storage only (data/seasonal.json); no database changes. PHP 7.4+.
 *
 * Schedule format (same as the app reads from api/seasonal.php):
 *   enabled, display_ms (800–3000), version (raised on every save), events[]:
 *     id [a-z0-9_]{1,40}, name, enabled, priority 0–1000,
 *     rule fixed (start/end "MM-DD", inclusive, may cross the new year) | easter | thanksgiving | memorial
 *       (start_offset/end_offset days around Easter Sunday, U.S. Thanksgiving, U.S. Memorial Day),
 *     dates {"YYYY": ["YYYY-MM-DD","YYYY-MM-DD"]} exact dates for a year (replace the rule that year),
 *     image (file name in api/seasonal-art/), image_sha256.
 *   default_image / default_image_sha256: the everyday design (no event, an event without a picture,
 *     or seasonal off). The event id "everyday" is reserved for it.
 * Selection: highest priority, then the shorter range, then the alphabetically first id.
 */
final class FlixSeasonalStore
{
    const RULES = ['fixed', 'easter', 'thanksgiving', 'memorial'];
    const MAX_UPLOAD = 8388608;          // 8 MB, the app's limit
    const MIN_W = 1280, MIN_H = 720, MAX_W = 3840, MAX_H = 2160;

    /** @var string */ private $dataFile;
    /** @var string */ private $artDir;
    /** @var array */ private $settings;

    public function __construct(array $settings, string $dataDir = null, string $artDir = null)
    {
        $this->settings = $settings;
        $this->dataFile = ($dataDir ?: __DIR__ . '/data') . '/seasonal.json';
        $this->artDir = $artDir ?: dirname(__DIR__) . '/seasonal-art';
    }

    public static function defaults(): array
    {
        $e = function ($id, $name, $rule, $a, $b, $priority) {
            $x = ['id' => $id, 'name' => $name, 'enabled' => true, 'priority' => $priority, 'rule' => $rule, 'dates' => new stdClass(), 'image' => '', 'image_sha256' => ''];
            if ($rule === 'fixed') { $x['start'] = $a; $x['end'] = $b; } else { $x['start_offset'] = $a; $x['end_offset'] = $b; }
            return $x;
        };
        return ['version' => 1, 'enabled' => true, 'display_ms' => 1500, 'default_image' => '', 'default_image_sha256' => '', 'events' => [
            $e('new_year', 'New Year', 'fixed', '12-27', '01-03', 60),
            $e('valentines', "Valentine's Day", 'fixed', '02-10', '02-15', 40),
            $e('st_patricks', "St. Patrick's Day", 'fixed', '03-13', '03-18', 40),
            $e('easter', 'Easter', 'easter', -6, 1, 45),
            $e('memorial_day', 'Memorial Day', 'memorial', -3, 0, 40),
            $e('independence_day', 'Independence Day', 'fixed', '07-01', '07-05', 40),
            $e('halloween', 'Halloween', 'fixed', '10-15', '10-31', 40),
            $e('thanksgiving', 'Thanksgiving', 'thanksgiving', -3, 1, 40),
            $e('christmas', 'Christmas', 'fixed', '12-01', '12-26', 50),
        ]];
    }

    public function load(): array
    {
        $raw = is_file($this->dataFile) ? @file_get_contents($this->dataFile) : false;
        $data = $raw === false ? null : json_decode($raw, true);
        if (!is_array($data)) return self::defaults();
        list($clean, $errors) = self::sanitize($data);
        return $errors ? self::defaults() : $clean;
    }

    /** Validates and saves atomically; raises the version. Returns the list of problems (empty = saved). */
    public function save(array $input): array
    {
        list($clean, $errors) = self::sanitize($input);
        if ($errors) return $errors;
        $current = $this->load();
        $clean['version'] = max((int)$current['version'], (int)($input['version'] ?? 0)) + 1;
        $dir = dirname($this->dataFile);
        if (!is_dir($dir) && !@mkdir($dir, 0750, true)) return ['The data folder is missing.'];
        $tmp = $dir . '/seasonal.' . bin2hex(random_bytes(6)) . '.tmp';
        if (@file_put_contents($tmp, json_encode($clean, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), LOCK_EX) === false) return ['Could not write the schedule. Check that api/flixtown-seasonal/data is writable.'];
        @chmod($tmp, 0640);
        if (!@rename($tmp, $this->dataFile)) { @unlink($tmp); return ['Could not replace the schedule file.']; }
        $this->pruneArt($clean);
        return [];
    }

    /** @return array [clean, errors] */
    public static function sanitize(array $in): array
    {
        $errors = [];
        $out = ['version' => max(1, (int)($in['version'] ?? 1)), 'enabled' => !empty($in['enabled']),
            'display_ms' => max(800, min(3000, (int)($in['display_ms'] ?? 1500))), 'default_image' => '', 'default_image_sha256' => '', 'events' => []];
        $di = (string)($in['default_image'] ?? ''); $ds = strtolower((string)($in['default_image_sha256'] ?? ''));
        if (preg_match('/^everyday-[0-9a-f]{16}\.jpg$/', $di)) { $out['default_image'] = $di; $out['default_image_sha256'] = preg_match('/^[0-9a-f]{64}$/', $ds) ? $ds : ''; }
        $events = $in['events'] ?? null;
        if (!is_array($events) || count($events) > 40) return [$out, ['The event list is missing or too long (40 at most).']];
        $ids = [];
        foreach (array_values($events) as $i => $e) {
            if (!is_array($e)) { $errors[] = 'Event ' . ($i + 1) . ' is not valid.'; continue; }
            $id = (string)($e['id'] ?? '');
            if (!preg_match('/^[a-z0-9_]{1,40}$/', $id)) { $errors[] = 'Event ' . ($i + 1) . ': the id must be 1–40 lowercase letters, digits or _.'; continue; }
            if (isset($ids[$id])) { $errors[] = "Event $id appears twice."; continue; }
            if ($id === 'everyday') { $errors[] = 'The id "everyday" is reserved for the everyday design.'; continue; }
            $ids[$id] = true;
            $name = trim(preg_replace('/[\x00-\x1F\x7F]/u', '', (string)($e['name'] ?? $id)));
            $x = ['id' => $id, 'name' => $name === '' ? $id : mb_substr($name, 0, 60), 'enabled' => !empty($e['enabled']),
                'priority' => max(0, min(1000, (int)($e['priority'] ?? 40))), 'rule' => (string)($e['rule'] ?? 'fixed')];
            if (!in_array($x['rule'], self::RULES, true)) { $errors[] = "$name: unknown date rule."; continue; }
            if ($x['rule'] === 'fixed') {
                foreach (['start', 'end'] as $k) {
                    $v = (string)($e[$k] ?? '');
                    if (!self::validMonthDay($v)) { $errors[] = "$name: the $k date must be MM-DD (for example 12-01)."; continue 2; }
                    $x[$k] = $v;
                }
            } else {
                $a = (int)($e['start_offset'] ?? 0); $b = (int)($e['end_offset'] ?? 0);
                if ($a < -60 || $b > 60 || $b < $a) { $errors[] = "$name: days before/after must be between −60 and 60, and the end not before the start."; continue; }
                $x['start_offset'] = $a; $x['end_offset'] = $b;
            }
            $dates = [];
            foreach ((array)($e['dates'] ?? []) as $year => $pair) {
                if (!preg_match('/^\d{4}$/', (string)$year) || !is_array($pair) || count($pair) !== 2) { $errors[] = "$name: exact dates must be a year with a start and end date."; continue 2; }
                $s = self::day((string)$pair[0]); $t = self::day((string)$pair[1]);
                if ($s === null || $t === null || $t < $s || $t - $s > 62) { $errors[] = "$name: exact dates for $year are not valid (YYYY-MM-DD, end after start, at most 62 days)."; continue 2; }
                $dates[(string)$year] = [(string)$pair[0], (string)$pair[1]];
            }
            ksort($dates);
            $x['dates'] = $dates ? $dates : new stdClass();
            $image = (string)($e['image'] ?? '');
            $x['image'] = preg_match('/^[a-z0-9_]+-[0-9a-f]{16}\.jpg$/', $image) ? $image : '';
            $sha = strtolower((string)($e['image_sha256'] ?? ''));
            $x['image_sha256'] = $x['image'] !== '' && preg_match('/^[0-9a-f]{64}$/', $sha) ? $sha : '';
            $out['events'][] = $x;
        }
        return [$out, $errors];
    }

    /** The schedule as the app reads it: picture names become absolute https links on the panel host. */
    public function publicSchedule(): array
    {
        $s = $this->load();
        $base = (string)($this->settings['public_api_base'] ?? '');
        $baseOk = preg_match('~^https://[a-z0-9.-]+(:\d+)?/~i', $base) === 1;
        foreach ($s['events'] as &$e) {
            $file = $this->artDir . '/' . $e['image'];
            if ($e['image'] !== '' && $baseOk && is_file($file)) $e['image'] = rtrim($base, '/') . '/seasonal-art/' . $e['image'];
            else { $e['image'] = ''; $e['image_sha256'] = ''; }
        }
        unset($e);
        if ($s['default_image'] !== '' && $baseOk && is_file($this->artDir . '/' . $s['default_image'])) $s['default_image'] = rtrim($base, '/') . '/seasonal-art/' . $s['default_image'];
        else { $s['default_image'] = ''; $s['default_image_sha256'] = ''; }
        return $s;
    }

    /**
     * Validates an uploaded picture and stores a re-encoded JPEG under a generated name
     * (EVENT-<16 hex>.jpg). Re-encoding drops metadata and anything that is not the picture.
     * @return array [fileName|null, sha256|null, error|null]
     */
    public function storeUpload(array $file, string $eventId): array
    {
        if (!preg_match('/^[a-z0-9_]{1,40}$/', $eventId)) return [null, null, 'Unknown event.'];
        if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) return [null, null, 'The upload did not arrive. Try again (8 MB at most).'];
        $tmp = (string)($file['tmp_name'] ?? '');
        if (!is_uploaded_file($tmp) && !(defined('FLIX_SEASONAL_TEST') && is_file($tmp))) return [null, null, 'The upload did not arrive.'];
        $size = filesize($tmp);
        if ($size === false || $size <= 0 || $size > self::MAX_UPLOAD) return [null, null, 'The picture must be 8 MB or smaller.'];
        $info = @getimagesize($tmp);
        if (!$info || !in_array($info[2], [IMAGETYPE_JPEG, IMAGETYPE_PNG, IMAGETYPE_WEBP], true)) return [null, null, 'Use a JPEG, PNG or WebP picture.'];
        $mime = function_exists('finfo_open') ? finfo_file(finfo_open(FILEINFO_MIME_TYPE), $tmp) : $info['mime'];
        if (!in_array($mime, ['image/jpeg', 'image/png', 'image/webp'], true)) return [null, null, 'The file is not a picture.'];
        list($w, $h) = $info;
        if ($w < self::MIN_W || $h < self::MIN_H || $w > self::MAX_W || $h > self::MAX_H) return [null, null, "The picture is {$w}×{$h}. Use 1280×720 to 3840×2160."];
        $ratio = $w / $h;
        if ($ratio < 1.70 || $ratio > 1.85) return [null, null, "The picture is {$w}×{$h}; it must be 16:9 (for example 1920×1080)."];
        if (!function_exists('imagecreatetruecolor')) return [null, null, 'The panel needs the PHP GD extension to accept pictures.'];
        $src = $info[2] === IMAGETYPE_JPEG ? @imagecreatefromjpeg($tmp) : ($info[2] === IMAGETYPE_PNG ? @imagecreatefrompng($tmp) : (function_exists('imagecreatefromwebp') ? @imagecreatefromwebp($tmp) : false));
        if (!$src) return [null, null, 'The picture could not be read.'];
        $dst = imagecreatetruecolor($w, $h);
        imagecopy($dst, $src, 0, 0, 0, 0, $w, $h);
        if (!is_dir($this->artDir) && !@mkdir($this->artDir, 0755, true)) return [null, null, 'The seasonal-art folder is missing.'];
        $out = $this->artDir . '/upload.' . bin2hex(random_bytes(6)) . '.tmp';
        $ok = imagejpeg($dst, $out, 88);
        imagedestroy($src); imagedestroy($dst);
        if (!$ok || filesize($out) > self::MAX_UPLOAD) { @unlink($out); return [null, null, 'The picture could not be saved (it may be too large after conversion).']; }
        $sha = hash_file('sha256', $out);
        $name = $eventId . '-' . substr($sha, 0, 16) . '.jpg';
        @chmod($out, 0644);
        if (!@rename($out, $this->artDir . '/' . $name)) { @unlink($out); return [null, null, 'The picture could not be saved.']; }
        return [$name, $sha, null];
    }

    /** Removes pictures no event uses (only files this module names). */
    private function pruneArt(array $schedule): void
    {
        $used = [];
        if (($schedule['default_image'] ?? '') !== '') $used[$schedule['default_image']] = true;
        foreach ($schedule['events'] as $e) if ($e['image'] !== '') $used[$e['image']] = true;
        foreach ((array)@scandir($this->artDir) as $f) {
            if (preg_match('/^[a-z0-9_]+-[0-9a-f]{16}\.jpg$/', (string)$f) && !isset($used[$f])) @unlink($this->artDir . '/' . $f);
        }
    }

    /* ---------------- Dates (same rules as the app) ---------------- */

    public static function validMonthDay(string $s): bool
    {
        if (!preg_match('/^(\d{2})-(\d{2})$/', $s, $m)) return false;
        $mo = (int)$m[1]; $d = (int)$m[2];
        return $mo >= 1 && $mo <= 12 && $d >= 1 && $d <= cal_days_in_month_safe(2024, $mo);
    }
    /** "YYYY-MM-DD" to a day number, or null. */
    public static function day(string $s): ?int
    {
        if (!preg_match('/^(\d{4})-(\d{2})-(\d{2})$/', $s, $m) || !checkdate((int)$m[2], (int)$m[3], (int)$m[1])) return null;
        return self::dayOf((int)$m[1], (int)$m[2], (int)$m[3]);
    }
    public static function dayOf(int $y, int $m, int $d): int
    {
        if ($m === 2 && $d === 29 && !checkdate(2, 29, $y)) $d = 28;
        return (int)floor((new DateTimeImmutable(sprintf('%04d-%02d-%02d', $y, $m, $d), new DateTimeZone('UTC')))->getTimestamp() / 86400);
    }
    public static function easter(int $y): int
    {
        $a = $y % 19; $b = intdiv($y, 100); $c = $y % 100; $d = intdiv($b, 4); $e = $b % 4; $f = intdiv($b + 8, 25); $g = intdiv($b - $f + 1, 3);
        $h = (19 * $a + $b - $d - $g + 15) % 30; $i = intdiv($c, 4); $k = $c % 4; $l = (32 + 2 * $e + 2 * $i - $h - $k) % 7; $m = intdiv($a + 11 * $h + 22 * $l, 451);
        $month = intdiv($h + $l - 7 * $m + 114, 31); $day = (($h + $l - 7 * $m + 114) % 31) + 1;
        return self::dayOf($y, $month, $day);
    }
    public static function thanksgiving(int $y): int
    {
        $first = self::dayOf($y, 11, 1); $wd = (($first + 3) % 7 + 7) % 7;   // 0 = Monday
        return $first + ((3 - $wd) % 7 + 7) % 7 + 21;
    }
    public static function memorial(int $y): int
    {
        $last = self::dayOf($y, 5, 31); $wd = (($last + 3) % 7 + 7) % 7;
        return $last - $wd;
    }
    /** [start, end] day numbers of the occurrence that starts in $year, or null. */
    public static function range(array $e, int $year): ?array
    {
        $dates = (array)($e['dates'] ?? []);
        if (isset($dates[(string)$year])) { $p = $dates[(string)$year]; $a = self::day((string)$p[0]); $b = self::day((string)$p[1]); return $a !== null && $b !== null && $b >= $a ? [$a, $b] : null; }
        switch ($e['rule']) {
            case 'fixed':
                list($sm, $sd) = array_map('intval', explode('-', $e['start'])); list($em, $ed) = array_map('intval', explode('-', $e['end']));
                $endYear = ($em * 100 + $ed) < ($sm * 100 + $sd) ? $year + 1 : $year;
                return [self::dayOf($year, $sm, $sd), self::dayOf($endYear, $em, $ed)];
            case 'easter': $x = self::easter($year); break;
            case 'thanksgiving': $x = self::thanksgiving($year); break;
            case 'memorial': $x = self::memorial($year); break;
            default: return null;
        }
        return [$x + (int)$e['start_offset'], $x + (int)$e['end_offset']];
    }
    /** The event selected on $today (day number), or null for the normal background. */
    public static function select(array $schedule, int $today): ?array
    {
        if (empty($schedule['enabled'])) return null;
        $year = (int)gmdate('Y', $today * 86400);
        $best = null; $bestLen = PHP_INT_MAX;
        foreach ($schedule['events'] as $e) {
            if (empty($e['enabled'])) continue;
            $len = null;
            foreach ([$year - 1, $year] as $y) { $r = self::range($e, $y); if ($r && $today >= $r[0] && $today <= $r[1]) { $len = $r[1] - $r[0]; break; } }
            if ($len === null) continue;
            if ($best === null || $e['priority'] > $best['priority'] || ($e['priority'] === $best['priority'] && ($len < $bestLen || ($len === $bestLen && strcmp($e['id'], $best['id']) < 0)))) { $best = $e; $bestLen = $len; }
        }
        return $best;
    }
    public static function rangeText(array $e, int $year): string
    {
        $r = self::range($e, $year);
        return $r ? gmdate('M j, Y', $r[0] * 86400) . ' – ' . gmdate('M j, Y', $r[1] * 86400) : '—';
    }
}

function cal_days_in_month_safe(int $y, int $m): int { return (int)(new DateTimeImmutable(sprintf('%04d-%02d-01', $y, $m)))->format('t'); }
