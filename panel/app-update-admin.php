<?php
/**
 * Flix Town app updates — panel page.
 *
 * Enter the link to the APK once. Replace the file at that link with each new build (same link,
 * same file name); the panel reads the package ID, versionCode and versionName from the APK's own
 * manifest, plus its SHA-256 and signing certificate. "Save & detect" and "Detect / Refresh APK"
 * read it immediately; the update API also notices a replaced file by itself within minutes.
 */
require_once __DIR__ . '/flixtown-update/UpdateStore.php';

$settings = require __DIR__ . '/flixtown-update/settings.php';
$store = new FlixUpdateStore($settings);
$https = !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off';
session_name('flixtown_update_admin');
if (PHP_VERSION_ID >= 70300) session_set_cookie_params(array('httponly' => true, 'secure' => $https, 'samesite' => 'Strict'));
else session_set_cookie_params(0, '/; samesite=Strict', '', $https, true);
session_start();
header('Cache-Control: no-store');
header('X-Frame-Options: DENY');

function h($s) { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }
function flash($type, $text) { $_SESSION['flash'][] = array($type, $text); }
function back() { header('Location: ' . strtok($_SERVER['REQUEST_URI'], '?')); exit; }

$password = (string)$store->setting('admin_password', '');
$locked = $password === '' || $password === 'CHANGE-ME';
if (empty($_SESSION['csrf'])) $_SESSION['csrf'] = bin2hex(random_bytes(16));
$csrfOk = isset($_POST['csrf']) && hash_equals($_SESSION['csrf'], (string)$_POST['csrf']);

if (!$locked && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = isset($_POST['action']) ? $_POST['action'] : '';
    if (!$csrfOk) { flash('error', 'The form expired. Please try again.'); back(); }
    if ($action === 'login') {
        if (hash_equals($password, (string)(isset($_POST['password']) ? $_POST['password'] : ''))) {
            session_regenerate_id(true); $_SESSION['ok'] = true;
        } else { sleep(2); flash('error', 'Wrong password.'); }
        back();
    }
    if (empty($_SESSION['ok'])) back();
    if ($action === 'logout') { $_SESSION = array(); session_destroy(); back(); }
    $slotId = isset($_POST['slot']) ? (string)$_POST['slot'] : '';
    if (!array_key_exists($slotId, FlixUpdateStore::SLOTS)) back();
    @set_time_limit(300);
    try {
        if ($action === 'save') {
            $slot = $store->configure($slotId, (string)$_POST['url'], (string)$_POST['notes'], !empty($_POST['required']));
            if ($slot['url'] === '') { flash('ok', FlixUpdateStore::SLOTS[$slotId] . ': saved. No APK is published.'); back(); }
        }
        if ($action === 'save' || $action === 'detect') {
            $slot = $store->inspectSlot($slotId);
            $d = $slot['detected'];
            flash('ok', FlixUpdateStore::SLOTS[$slotId] . ': detected ' . $d['package'] . ' version ' . $d['version_name'] . ' (versionCode ' . $d['version_code'] . ').');
        }
    } catch (Exception $e) {
        flash('error', FlixUpdateStore::SLOTS[$slotId] . ': ' . $e->getMessage());
    }
    back();
}

$state = $store->state();
$flash = isset($_SESSION['flash']) ? $_SESSION['flash'] : array();
unset($_SESSION['flash']);
$loggedIn = !$locked && !empty($_SESSION['ok']);

/** Problems worth stopping for before devices try to install this APK. */
function warnings($id, array $slot, $mainPackage, array $state)
{
    $w = array(); $d = $slot['detected']; $p = $slot['previous'];
    if (!$d) return $w;
    if ($id === 'main' && $d['package'] !== $mainPackage)
        $w[] = 'This APK is ' . $d['package'] . ', not ' . $mainPackage . '. Installed Flix Town apps will not be offered it.';
    if ($id === 'test' && $d['package'] === $mainPackage)
        $w[] = 'The test build has the main app\'s package ID; the API offers the main app slot to that package.';
    if (!$d['signer_sha256'])
        $w[] = 'No APK Signature Scheme v2/v3 signature was found. Android TV devices may refuse to install it.';
    if ($p && $p['signer_sha256'] && $d['signer_sha256'] && $p['signer_sha256'] !== $d['signer_sha256'])
        $w[] = 'This APK is signed with a different key than the previous one (' . substr($p['signer_sha256'], 0, 16) . '…). It cannot install over apps signed with the old key.';
    if ($p && $p['sha256'] !== $d['sha256'] && $p['package'] === $d['package'] && $d['version_code'] <= $p['version_code'])
        $w[] = 'The versionCode did not increase (previous: ' . $p['version_code'] . '). Devices only offer an update when the versionCode is higher than the installed one.';
    if (strpos($slot['url'], 'https://') !== 0)
        $w[] = 'The link is not https://. The app only downloads updates over https.';
    return $w;
}
function ago($t) { if (!$t) return 'never'; $s = time() - (int)$t; if ($s < 60) return 'just now'; if ($s < 3600) return floor($s / 60) . ' min ago'; if ($s < 86400) return floor($s / 3600) . ' h ago'; return gmdate('Y-m-d H:i', (int)$t) . ' UTC'; }
?><!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Flix Town · App updates</title>
<style>
:root{--bg:#0b0b0e;--card:#16151a;--line:#2a2830;--text:#f2eeea;--muted:#a49e98;--accent:#ab3a39;--ok:#6fcf97;--warn:#e7b25a;--bad:#f0a29f}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:980px;margin:0 auto;padding:28px 16px 60px}h1{font-size:24px;margin:0 0 4px}p.lead{color:var(--muted);margin:0 0 22px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:20px;margin-bottom:18px}
.card h2{font-size:18px;margin:0 0 12px;display:flex;justify-content:space-between;gap:12px;align-items:center}
label{display:block;color:var(--muted);font-size:13px;margin:12px 0 4px}input[type=text],input[type=password],input[type=url],textarea{width:100%;padding:10px 12px;border-radius:9px;border:1px solid var(--line);background:#0f0e12;color:var(--text);font:inherit}
textarea{min-height:70px;resize:vertical}.row{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px;align-items:center}
button{padding:10px 16px;border-radius:9px;border:1px solid var(--line);background:#25232a;color:var(--text);font:inherit;cursor:pointer}button.primary{background:var(--accent);border-color:var(--accent)}
table{width:100%;border-collapse:collapse;margin-top:14px;font-size:14px}td{padding:6px 8px;border-top:1px solid var(--line);vertical-align:top}td:first-child{color:var(--muted);width:190px}
code{font:13px ui-monospace,SFMono-Regular,Menlo,monospace;word-break:break-all}.pill{font-size:12px;padding:3px 10px;border-radius:99px;border:1px solid var(--line);color:var(--muted)}
.pill.ok{color:var(--ok);border-color:#2f5a40}.pill.bad{color:var(--bad);border-color:#5a2f2f}
.msg{padding:10px 14px;border-radius:9px;margin-bottom:12px}.msg.ok{background:#16261c;color:var(--ok)}.msg.error{background:#2a1515;color:var(--bad)}
.warn{background:#2a2213;color:var(--warn);padding:9px 12px;border-radius:9px;margin-top:10px;font-size:14px}.err{background:#2a1515;color:var(--bad);padding:9px 12px;border-radius:9px;margin-top:10px;font-size:14px}
.check{display:flex;gap:8px;align-items:center;margin-top:12px;color:var(--muted)}.small{color:var(--muted);font-size:13px}
@media (max-width:600px){td:first-child{width:120px}}
</style></head><body><main>
<h1>Flix Town · App updates</h1>
<p class="lead">Publish an APK link once, then replace the file at that link with each new build. The version is read from inside the APK.</p>
<?php foreach ($flash as $f): ?><div class="msg <?= h($f[0]) ?>"><?= h($f[1]) ?></div><?php endforeach; ?>

<?php if ($locked): ?>
<div class="card"><h2>Locked</h2><p>Set <code>admin_password</code> in <code>flixtown-update/settings.php</code> on the server, then reload this page.</p></div>
<?php elseif (!$loggedIn): ?>
<form class="card" method="post"><h2>Sign in</h2>
<input type="hidden" name="csrf" value="<?= h($_SESSION['csrf']) ?>"><input type="hidden" name="action" value="login">
<label for="pw">Password</label><input id="pw" type="password" name="password" autofocus autocomplete="current-password">
<div class="row"><button class="primary">Sign in</button></div></form>
<?php else: foreach (FlixUpdateStore::SLOTS as $id => $label): $slot = $state['slots'][$id]; $d = $slot['detected']; ?>
<form class="card" method="post">
  <h2><span><?= h($label) ?></span>
    <?php if ($slot['error']): ?><span class="pill bad">Failed</span>
    <?php elseif ($d): ?><span class="pill ok">Detected · <?= h($d['version_name']) ?> (<?= h($d['version_code']) ?>)</span>
    <?php else: ?><span class="pill">Not published</span><?php endif; ?></h2>
  <?php if ($id === 'test'): ?><p class="small">Optional. For a side-by-side test app with its own package ID (for example <code><?= h($store->setting('main_package')) ?>.preview</code>). Each app is only offered the APK with its own package ID.</p><?php endif; ?>
  <input type="hidden" name="csrf" value="<?= h($_SESSION['csrf']) ?>"><input type="hidden" name="slot" value="<?= h($id) ?>">
  <label for="url-<?= h($id) ?>">APK link (keep the same link and file name for every release)</label>
  <input id="url-<?= h($id) ?>" type="url" name="url" value="<?= h($slot['url']) ?>" placeholder="https://example.com/apps/flixtown.apk">
  <label for="notes-<?= h($id) ?>">Release notes (optional, shown on the TV)</label>
  <textarea id="notes-<?= h($id) ?>" name="notes" maxlength="500"><?= h($slot['notes']) ?></textarea>
  <label class="check"><input type="checkbox" name="required" value="1" <?= $slot['required'] ? 'checked' : '' ?>> Required update (no “Later” button on the TV)</label>
  <div class="row">
    <button class="primary" name="action" value="save">Save &amp; detect</button>
    <button name="action" value="detect" <?= $slot['url'] === '' ? 'disabled' : '' ?>>Detect / Refresh APK</button>
  </div>
  <?php if ($slot['error']): ?><div class="err">Last check failed <?= h(ago($slot['checked_at'])) ?>: <?= h($slot['error']) ?> Devices are told the check failed (never “up to date”) until it succeeds.</div><?php endif; ?>
  <?php foreach (warnings($id, $slot, $store->setting('main_package'), $state) as $w): ?><div class="warn"><?= h($w) ?></div><?php endforeach; ?>
  <?php if ($d): ?>
  <table>
    <tr><td>Package ID</td><td><code><?= h($d['package']) ?></code></td></tr>
    <tr><td>versionName</td><td><?= h($d['version_name']) ?></td></tr>
    <tr><td>versionCode</td><td><?= h($d['version_code']) ?></td></tr>
    <tr><td>SHA-256</td><td><code><?= h($d['sha256']) ?></code></td></tr>
    <tr><td>Size</td><td><?= h(number_format($d['size'] / 1048576, 1)) ?> MB</td></tr>
    <tr><td>Signing certificate</td><td><?= $d['signer_sha256'] ? '<code>' . h($d['signer_sha256']) . '</code> <span class="small">(' . h($d['signature_scheme']) . ')</span>' : 'not found' ?></td></tr>
    <?php if ($d['min_sdk']): ?><tr><td>Minimum Android</td><td>API <?= h($d['min_sdk']) ?></td></tr><?php endif; ?>
    <tr><td>Read from the APK</td><td><?= h(ago($slot['inspected_at'])) ?> · link last checked <?= h(ago($slot['checked_at'])) ?></td></tr>
    <?php if ($slot['previous']): ?><tr><td>Previous</td><td><?= h($slot['previous']['version_name']) ?> (<?= h($slot['previous']['version_code']) ?>)</td></tr><?php endif; ?>
    <tr><td>API check</td><td><a style="color:var(--muted)" href="app-update.php?package=<?= h(rawurlencode($d['package'])) ?>" target="_blank" rel="noopener"><code>app-update.php?package=<?= h($d['package']) ?></code></a></td></tr>
  </table>
  <?php endif; ?>
</form>
<?php endforeach; ?>
<form method="post" class="row"><input type="hidden" name="csrf" value="<?= h($_SESSION['csrf']) ?>"><button name="action" value="logout">Sign out</button>
<span class="small">The API re-checks each link every <?= (int)$store->setting('recheck_minutes', 10) ?> minutes and reads the APK again only when the file changed.</span></form>
<?php endif; ?>
</main></body></html>
