<?php
/**
 * Flix Town panel — Seasonal splash (startup backgrounds by holiday).
 *
 * Uses the existing Flix Town panel login: sign in on admin/index.php first (same session and
 * CSRF token). Nothing here is shown or saved without that login. No database changes.
 */
declare(strict_types=1);
session_name('flixtown_admin');
session_set_cookie_params(['httponly' => true, 'secure' => true, 'samesite' => 'Strict', 'path' => '/panels/flixtown2027/admin/']);
session_start();
header('Cache-Control: no-store');
header('X-Frame-Options: DENY');
header('X-Content-Type-Options: nosniff');
header("Content-Security-Policy: default-src 'self'; img-src 'self'; style-src 'self' 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
if (empty($_SESSION['admin'])) { header('Location: index.php'); exit; }
if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(32));
$csrf = (string)$_SESSION['csrf'];

require_once dirname(__DIR__) . '/api/flixtown-seasonal/SeasonalStore.php';
$settings = require dirname(__DIR__) . '/api/flixtown-seasonal/settings.php';
$store = new FlixSeasonalStore($settings);
function h($s): string { return htmlspecialchars((string)$s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }
function flash(string $type, string $text): void { $_SESSION['seasonal_flash'][] = [$type, $text]; }
function back(): void { header('Location: seasonal.php'); exit; }

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) { http_response_code(403); exit('Invalid request. Reload the page and try again.'); }
    $s = $store->load();
    $action = (string)($_POST['action'] ?? '');
    $find = function (string $id) use (&$s) { foreach ($s['events'] as $i => $e) if ($e['id'] === $id) return $i; return -1; };
    if ($action === 'restore_default') {
        $s['enabled'] = false;
        $err = $store->save($s);
        flash($err ? 'error' : 'ok', $err ? implode(' ', $err) : 'Seasonal splash is off: TVs show the normal Flix Town splash from their next launch.');
        back();
    }
    if ($action === 'turn_on') { $s['enabled'] = true; $err = $store->save($s); flash($err ? 'error' : 'ok', $err ? implode(' ', $err) : 'Seasonal splash is on.'); back(); }
    if ($action === 'save') {
        $s['enabled'] = isset($_POST['enabled']);
        $s['display_ms'] = (int)round(((float)($_POST['display_seconds'] ?? 1.5)) * 1000);
        $events = [];
        foreach ((array)($_POST['events'] ?? []) as $in) {
            if (!is_array($in)) continue;
            $i = $find((string)($in['id'] ?? ''));
            $e = $i >= 0 ? $s['events'][$i] : ['id' => (string)($in['id'] ?? ''), 'image' => '', 'image_sha256' => ''];
            $e['name'] = (string)($in['name'] ?? $e['id']);
            $e['enabled'] = isset($in['enabled']);
            $e['priority'] = (int)($in['priority'] ?? 40);
            $e['rule'] = (string)($in['rule'] ?? 'fixed');
            if ($e['rule'] === 'fixed') { $e['start'] = trim((string)($in['start'] ?? '')); $e['end'] = trim((string)($in['end'] ?? '')); unset($e['start_offset'], $e['end_offset']); }
            else { $e['start_offset'] = (int)($in['start_offset'] ?? 0); $e['end_offset'] = (int)($in['end_offset'] ?? 0); unset($e['start'], $e['end']); }
            $dates = [];
            foreach (preg_split('/\R/', (string)($in['dates'] ?? '')) as $line) {
                $line = trim($line); if ($line === '') continue;
                if (preg_match('/^(\d{4})\s*:\s*(\d{4}-\d{2}-\d{2})\s*(?:to|–|-)\s*(\d{4}-\d{2}-\d{2})$/u', $line, $m)) $dates[$m[1]] = [$m[2], $m[3]];
                else { flash('error', h($e['name']) . ': write exact dates as "2027: 2027-03-25 to 2027-03-29".'); back(); }
            }
            $e['dates'] = $dates;
            $events[] = $e;
        }
        $s['events'] = $events;
        $err = $store->save($s);
        flash($err ? 'error' : 'ok', $err ? implode(' ', $err) : 'Saved. TVs use it from their next launch after they check (within about 30 minutes).');
        back();
    }
    if ($action === 'add_event') {
        $id = strtolower(trim((string)($_POST['new_id'] ?? '')));
        $s['events'][] = ['id' => $id, 'name' => (string)($_POST['new_name'] ?? $id), 'enabled' => false, 'priority' => 40, 'rule' => 'fixed',
            'start' => '01-01', 'end' => '01-01', 'dates' => [], 'image' => '', 'image_sha256' => ''];
        $err = $store->save($s);
        flash($err ? 'error' : 'ok', $err ? implode(' ', $err) : 'Event added (off). Set its dates, add a picture, then turn it on.');
        back();
    }
    $id = (string)($_POST['event'] ?? '');
    $i = $find($id);
    if ($i < 0) { flash('error', 'Unknown event.'); back(); }
    if ($action === 'upload') {
        list($name, $sha, $error) = $store->storeUpload($_FILES['picture'] ?? [], $id);
        if ($error) { flash('error', $s['events'][$i]['name'] . ': ' . $error); back(); }
        $s['events'][$i]['image'] = $name; $s['events'][$i]['image_sha256'] = $sha;
        $err = $store->save($s);
        flash($err ? 'error' : 'ok', $err ? implode(' ', $err) : $s['events'][$i]['name'] . ': picture saved.');
        back();
    }
    if ($action === 'remove_image') {
        $s['events'][$i]['image'] = ''; $s['events'][$i]['image_sha256'] = '';
        $err = $store->save($s);
        flash($err ? 'error' : 'ok', $err ? implode(' ', $err) : $s['events'][$i]['name'] . ': picture removed (the normal background shows for it).');
        back();
    }
    if ($action === 'delete_event') {
        $builtIn = array_column(FlixSeasonalStore::defaults()['events'], 'id');
        if (in_array($id, $builtIn, true)) { flash('error', 'Built-in events can be turned off but not deleted.'); back(); }
        array_splice($s['events'], $i, 1);
        $err = $store->save($s);
        flash($err ? 'error' : 'ok', $err ? implode(' ', $err) : 'Event deleted.');
        back();
    }
    back();
}

$s = $store->load();
$tz = new DateTimeZone((string)($settings['admin_timezone'] ?? 'UTC'));
$now = new DateTimeImmutable('now', $tz);
$today = FlixSeasonalStore::dayOf((int)$now->format('Y'), (int)$now->format('n'), (int)$now->format('j'));
$selected = FlixSeasonalStore::select($s, $today);
$year = (int)$now->format('Y');
$flashes = $_SESSION['seasonal_flash'] ?? []; unset($_SESSION['seasonal_flash']);
$art = function (array $e): string { return $e['image'] !== '' ? '../api/seasonal-art/' . rawurlencode($e['image']) : ''; };
?><!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Flix Town — Seasonal splash</title>
<style>
body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;background:#0f0d0d;color:#eee;margin:0;padding:24px}
.wrap{max-width:1100px;margin:auto}a{color:#f0a29f}h1{margin:0 0 4px}.sub{color:#aaa;margin:0 0 18px}
.card{background:#1a1616;border:1px solid #3a2a2a;border-radius:14px;padding:18px;margin-bottom:16px}
.ok{background:#12351f;border:1px solid #2e7d4f;color:#9be3b5;padding:10px 14px;border-radius:8px;margin-bottom:12px}
.error{background:#3a1616;border:1px solid #a33;color:#ffb3b3;padding:10px 14px;border-radius:8px;margin-bottom:12px}
table{width:100%;border-collapse:collapse}td,th{padding:8px 6px;border-bottom:1px solid #2e2424;vertical-align:top;text-align:left;font-size:14px}
input[type=text],input[type=number],select,textarea{background:#241e1e;color:#eee;border:1px solid #4a3a3a;border-radius:6px;padding:6px;box-sizing:border-box}
textarea{width:100%;min-height:42px;font-family:monospace;font-size:12px}
button{background:#ce4b4a;color:#fff;border:0;border-radius:8px;padding:9px 16px;font-weight:600;cursor:pointer}
button.sec{background:#3a2e2e}.prev{width:192px;aspect-ratio:16/9;background:#000;border-radius:6px;overflow:hidden;border:1px solid #3a2a2a;display:grid;place-items:center;color:#777;font-size:12px}
.prev img{width:100%;height:100%;object-fit:cover}.big{width:100%;max-width:640px}.muted{color:#999;font-size:12px}
</style></head><body><div class="wrap">
<p><a href="index.php">← Flix Town panel</a></p>
<h1>Seasonal splash</h1>
<p class="sub">The startup background on customers' TVs. Each TV picks the event for its own date; changes apply from the TV's next launch after it checks (within about 30 minutes). Pictures must be 16:9 (1920×1080 recommended), JPEG/PNG/WebP, 8 MB at most; approved pictures already contain the logo.</p>
<?php foreach ($flashes as $f) echo '<div class="' . ($f[0] === 'ok' ? 'ok' : 'error') . '">' . h($f[1]) . '</div>'; ?>

<div class="card">
  <strong>Status:</strong> <?= $s['enabled'] ? 'On' : 'Off — TVs show the normal Flix Town splash' ?> ·
  <strong>Selected today</strong> (<?= h($now->format('M j, Y')) ?>, <?= h($tz->getName()) ?>):
  <?= $selected ? h($selected['name']) . ($selected['image'] === '' ? ' (no picture: normal background)' : '') : 'Normal background' ?>
  <?php if ($selected && $selected['image'] !== ''): ?><div class="prev big" style="margin-top:12px"><img src="<?= h($art($selected)) ?>" alt=""></div><?php endif; ?>
  <form method="post" style="margin-top:12px">
    <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
    <?php if ($s['enabled']): ?><button name="action" value="restore_default">Restore normal splash now</button>
    <?php else: ?><button name="action" value="turn_on">Turn seasonal splash on</button><?php endif; ?>
  </form>
</div>

<form method="post" class="card">
  <input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="save">
  <label><input type="checkbox" name="enabled" value="1" <?= $s['enabled'] ? 'checked' : '' ?>> Seasonal splash on</label>
  &nbsp; Display time <input type="number" name="display_seconds" min="0.8" max="3" step="0.1" value="<?= h(number_format($s['display_ms'] / 1000, 1)) ?>" style="width:70px"> seconds (0.8–3)
  <table style="margin-top:14px"><tr><th>On</th><th>Event</th><th>Dates (inclusive)</th><th>Priority</th><th>Exact dates for a year (optional)</th><th><?= $year ?></th></tr>
  <?php foreach ($s['events'] as $n => $e): $p = "events[$n]"; ?>
    <tr><td><input type="checkbox" name="<?= $p ?>[enabled]" value="1" <?= $e['enabled'] ? 'checked' : '' ?>><input type="hidden" name="<?= $p ?>[id]" value="<?= h($e['id']) ?>"></td>
      <td><input type="text" name="<?= $p ?>[name]" value="<?= h($e['name']) ?>" maxlength="60" style="width:150px"><div class="muted"><?= h($e['id']) ?></div></td>
      <td><select name="<?= $p ?>[rule]"><?php foreach (['fixed' => 'Fixed dates', 'easter' => 'Around Easter', 'thanksgiving' => 'Around Thanksgiving', 'memorial' => 'Around Memorial Day'] as $k => $label) echo '<option value="' . $k . '"' . ($e['rule'] === $k ? ' selected' : '') . '>' . $label . '</option>'; ?></select><br>
        <?php if ($e['rule'] === 'fixed'): ?>from <input type="text" name="<?= $p ?>[start]" value="<?= h($e['start']) ?>" size="5" placeholder="MM-DD"> to <input type="text" name="<?= $p ?>[end]" value="<?= h($e['end']) ?>" size="5" placeholder="MM-DD">
        <?php else: ?>days <input type="number" name="<?= $p ?>[start_offset]" value="<?= (int)$e['start_offset'] ?>" style="width:60px"> to <input type="number" name="<?= $p ?>[end_offset]" value="<?= (int)$e['end_offset'] ?>" style="width:60px"> <span class="muted">(0 = the day itself)</span><?php endif; ?></td>
      <td><input type="number" name="<?= $p ?>[priority]" value="<?= (int)$e['priority'] ?>" min="0" max="1000" style="width:70px"></td>
      <td><textarea name="<?= $p ?>[dates]" placeholder="2027: 2027-03-25 to 2027-03-29"><?php foreach ((array)$e['dates'] as $y => $d) echo h($y . ': ' . $d[0] . ' to ' . $d[1]) . "\n"; ?></textarea></td>
      <td class="muted"><?= h(FlixSeasonalStore::rangeText($e, $year)) ?></td></tr>
  <?php endforeach; ?></table>
  <p><button>Save schedule</button> <span class="muted">Highest priority wins when events overlap; then the shorter range; then the event id.</span></p>
</form>

<div class="card"><h3 style="margin-top:0">Pictures</h3><table>
<?php foreach ($s['events'] as $e): ?>
  <tr><td style="width:210px"><div class="prev"><?php if ($e['image'] !== ''): ?><img src="<?= h($art($e)) ?>" alt=""><?php else: ?>No picture (normal background)<?php endif; ?></div></td>
    <td><strong><?= h($e['name']) ?></strong><br>
      <form method="post" enctype="multipart/form-data" style="margin-top:8px"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="upload"><input type="hidden" name="event" value="<?= h($e['id']) ?>">
        <input type="file" name="picture" accept="image/jpeg,image/png,image/webp" required> <button><?= $e['image'] !== '' ? 'Replace picture' : 'Upload picture' ?></button></form>
      <?php if ($e['image'] !== ''): ?><form method="post" style="margin-top:6px"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="remove_image"><input type="hidden" name="event" value="<?= h($e['id']) ?>"><button class="sec">Remove picture</button></form><?php endif; ?>
      <?php if (!in_array($e['id'], array_column(FlixSeasonalStore::defaults()['events'], 'id'), true)): ?><form method="post" style="margin-top:6px"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="delete_event"><input type="hidden" name="event" value="<?= h($e['id']) ?>"><button class="sec">Delete event</button></form><?php endif; ?>
    </td></tr>
<?php endforeach; ?></table></div>

<form method="post" class="card"><input type="hidden" name="csrf" value="<?= h($csrf) ?>"><input type="hidden" name="action" value="add_event">
  <strong>Add an event</strong> (no app update needed): id <input type="text" name="new_id" pattern="[a-z0-9_]{1,40}" placeholder="labor_day" required> name <input type="text" name="new_name" maxlength="60" placeholder="Labor Day" required> <button class="sec">Add</button>
</form>
</div></body></html>
