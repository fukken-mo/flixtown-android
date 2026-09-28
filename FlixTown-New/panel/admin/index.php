<?php
declare(strict_types=1);
$config = require (getenv('FLIXTOWN_CONFIG') ?: dirname(__DIR__) . '/private/config.php');
session_name('flixtown_admin');
session_set_cookie_params(['httponly' => true, 'secure' => true, 'samesite' => 'Strict', 'path' => '/panels/flixtown2027/admin/']);
session_start();
header('Cache-Control: no-store');
header('X-Frame-Options: DENY');
header('Content-Security-Policy: default-src \'self\'; style-src \'self\' \'unsafe-inline\'; form-action \'self\'; base-uri \'none\'');
$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['login'])) {
    if (hash_equals((string)$config['admin_user'], (string)($_POST['user'] ?? '')) && password_verify((string)($_POST['password'] ?? ''), (string)$config['admin_password_hash'])) {
        session_regenerate_id(true);
        $_SESSION['admin'] = true;
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
        header('Location: index.php'); exit;
    }
    $error = 'Login failed';
}
if (isset($_GET['logout'])) {
    $_SESSION = [];
    session_destroy();
    header('Location: index.php'); exit;
}
$authorized = !empty($_SESSION['admin']);
if ($authorized) {
    try {
        $db = new PDO($config['db_dsn'], $config['db_user'], $config['db_password'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_EMULATE_PREPARES => false]);
        if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['handled'])) {
            if (!hash_equals((string)$_SESSION['csrf'], (string)($_POST['csrf'] ?? ''))) { http_response_code(403); exit('Invalid request'); }
            $id = filter_var($_POST['handled'], FILTER_VALIDATE_INT);
            if ($id) $db->prepare("UPDATE renewal_requests SET status='handled',handled_at=UTC_TIMESTAMP() WHERE id=? AND status='pending'")->execute([$id]);
            header('Location: index.php'); exit;
        }
        if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['save'])) {
            if (!hash_equals((string)$_SESSION['csrf'], (string)($_POST['csrf'] ?? ''))) { http_response_code(403); exit('Invalid request'); }
            $values = [
                'app_name' => clipText(trim((string)($_POST['app_name'] ?? 'Flix Town')),60),
                'xtream_url' => trim((string)($_POST['xtream_url'] ?? '')),
                'intro_url' => trim((string)($_POST['intro_url'] ?? '')),
                'intro_enabled' => isset($_POST['intro_enabled']) ? '1' : '0',
                'logo_url' => trim((string)($_POST['logo_url'] ?? '')),
                'tmdb_key' => trim((string)($_POST['tmdb_key'] ?? '')),
                'cashapp_url' => trim((string)($_POST['cashapp_url'] ?? '')),
                'price_1m' => trim((string)($_POST['price_1m'] ?? '')),
                'price_3m' => trim((string)($_POST['price_3m'] ?? '')),
                'price_6m' => trim((string)($_POST['price_6m'] ?? '')),
                'price_12m' => trim((string)($_POST['price_12m'] ?? '')),
                'announcement' => clipText(trim((string)($_POST['announcement'] ?? '')),500),
                'maintenance' => isset($_POST['maintenance']) ? '1' : '0',
                'update_version_code' => trim((string)($_POST['update_version_code'] ?? '0')),
                'update_apk_url' => trim((string)($_POST['update_apk_url'] ?? '')),
                'update_notes' => clipText(trim((string)($_POST['update_notes'] ?? '')),250),
                'update_required' => isset($_POST['update_required']) ? '1' : '0',
            ];
            foreach (['xtream_url', 'intro_url', 'logo_url', 'cashapp_url'] as $key) {
                if ($values[$key] !== '' && !filter_var($values[$key], FILTER_VALIDATE_URL)) { $error = 'Check the ' . $key . ' URL'; break; }
            }
            if (!$error && !in_array(parse_url($values['xtream_url'], PHP_URL_SCHEME), ['http','https'], true)) $error = 'Xtream URL must use HTTP or HTTPS';
            if (!$error && $values['intro_url'] !== '' && parse_url($values['intro_url'], PHP_URL_SCHEME) !== 'https') $error = 'Intro video must use HTTPS';
            if (!$error && !preg_match('/^(0|[1-9][0-9]{0,8})$/D',$values['update_version_code'])) $error = 'Update version must be a whole number';
            if (!$error && $values['update_apk_url'] !== '' && (!filter_var($values['update_apk_url'], FILTER_VALIDATE_URL) || parse_url($values['update_apk_url'], PHP_URL_SCHEME) !== 'https')) $error = 'APK link must use HTTPS';
            $upload = $_FILES['update_apk'] ?? null;
            $hasUpload = is_array($upload) && (int)($upload['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_NO_FILE;
            if (!$error && (int)$values['update_version_code'] > 0 && $values['update_apk_url'] === '' && !$hasUpload) $error = 'Upload an APK or add its direct link';
            if ($hasUpload && (int)$values['update_version_code'] === 0) $error = 'Set the APK build number before uploading';
            if ($hasUpload && (int)($upload['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) $error = 'APK upload failed. Check your hosting upload size limit';
            if ($hasUpload && !$error) {
                $size = (int)($upload['size'] ?? 0);
                $source = (string)($upload['tmp_name'] ?? '');
                $name = (string)($upload['name'] ?? '');
                if ($size < 1024 || $size > 83886080 || !preg_match('/\.apk$/iD',$name) || !is_uploaded_file($source)) $error = 'Choose a valid APK under 80 MB';
                elseif (($handle = fopen($source,'rb')) === false) $error = 'Could not read the uploaded APK';
                else {
                    $header = fread($handle,4); fclose($handle);
                    if ($header !== "PK\x03\x04") $error = 'This is not an APK file';
                }
                if (!$error) {
                    $folder = dirname(__DIR__) . '/updates';
                    if (!is_dir($folder) && !mkdir($folder,0755,true)) $error = 'Could not create the updates folder';
                    elseif (!is_writable($folder)) $error = 'The updates folder is not writable';
                    else {
                        $basename = 'FlixTown-v' . $values['update_version_code'] . '.apk';
                        $temporary = $folder . '/.' . $basename . '.upload';
                        if (!move_uploaded_file($source,$temporary) || !rename($temporary,$folder . '/' . $basename)) $error = 'Could not save the APK on the server';
                        else {
                            chmod($folder . '/' . $basename,0644);
                            $values['update_apk_url'] = 'https://panelsandapps.com/panels/flixtown2027/updates/' . $basename;
                        }
                    }
                }
            }
            foreach (['price_1m','price_3m','price_6m','price_12m'] as $key) if (!$error && !preg_match('/^[0-9]{1,4}(?:\.[0-9]{1,2})?$/D',$values[$key])) $error='Check plan prices';
            if (!$error) {
                $stmt = $db->prepare('INSERT INTO settings (name,value) VALUES (?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)');
                foreach ($values as $key => $value) $stmt->execute([$key, $value]);
                header('Location: index.php?saved=1'); exit;
            }
        }
        $values = $db->query('SELECT name,value FROM settings')->fetchAll(PDO::FETCH_KEY_PAIR);
        $renewals = $db->query("SELECT id,username,phone,plan,created_at FROM renewal_requests WHERE status='pending' ORDER BY created_at ASC LIMIT 100")->fetchAll(PDO::FETCH_ASSOC);
    } catch (Throwable $e) { $error = 'Database is unavailable'; $values = []; }
}
function h(string $value): string { return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'); }
function clipText(string $value,int $limit): string {
    if (function_exists('mb_substr')) return mb_substr($value,0,$limit);
    if (preg_match_all('/./us',$value,$parts) !== false) return implode('',array_slice($parts[0],0,$limit));
    return substr($value,0,$limit);
}
?><!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Flix Town Panel</title>
<style>body{font:16px system-ui;background:#090b10;color:#f5f5f5;margin:0}main{max-width:760px;margin:7vh auto;padding:30px;background:#171a21;border-radius:18px}h1{color:#e83342;margin-top:0}label{display:block;margin:18px 0 6px}input:not([type=checkbox]),textarea{box-sizing:border-box;width:100%;background:#252a35;border:1px solid #596070;color:#fff;padding:12px;font:inherit;border-radius:8px}textarea{height:90px}button{background:#d72536;color:white;border:0;border-radius:8px;padding:12px 22px;font:inherit;margin-top:20px;cursor:pointer}.notice{color:#eec479}a{color:#eaa7ac}small{color:#adb1b9}</style>
<main><h1>Flix Town Panel</h1>
<?php if ($error): ?><p class="notice"><?= h($error) ?></p><?php endif; ?>
<?php if (!$authorized): ?>
<form method="post"><label>Username</label><input name="user" autocomplete="username" required><label>Password</label><input type="password" name="password" autocomplete="current-password" required><button name="login" value="1">Sign in</button></form>
<?php else: ?>
<p><a href="?logout=1">Sign out</a></p><?php if (isset($_GET['saved'])): ?><p>Settings saved.</p><?php endif; ?>
<h2>Renewal requests</h2>
<?php if (empty($renewals)): ?><p>No pending requests.</p><?php else: ?>
<p><small>Confirm payment and extend the same account in your Xtream admin before marking it handled. Marking it here does not alter the Xtream account.</small></p>
<?php foreach ($renewals as $renewal): ?>
<form method="post" style="background:#252a35;padding:12px;margin:8px 0;border-radius:8px">
<input type="hidden" name="csrf" value="<?= h((string)$_SESSION['csrf']) ?>">
<strong><?= h((string)$renewal['username']) ?></strong> · <?= h((string)$renewal['plan']) ?> · <?= h((string)$renewal['phone']) ?> · <?= h((string)$renewal['created_at']) ?> UTC
<button name="handled" value="<?= (int)$renewal['id'] ?>">Mark handled</button></form>
<?php endforeach; endif; ?>
<h2>App settings</h2>
<form method="post" enctype="multipart/form-data"><input type="hidden" name="csrf" value="<?= h((string)$_SESSION['csrf']) ?>">
<label>App name</label><input name="app_name" value="<?= h((string)($values['app_name'] ?? 'Flix Town')) ?>" required>
<label>Xtream server URL</label><input name="xtream_url" value="<?= h((string)($values['xtream_url'] ?? '')) ?>" required>
<label>Logo URL</label><input name="logo_url" value="<?= h((string)($values['logo_url'] ?? '')) ?>" placeholder="https://">
<label>Intro video URL</label><input name="intro_url" value="<?= h((string)($values['intro_url'] ?? '')) ?>" placeholder="https://">
<label><input type="checkbox" name="intro_enabled" <?= ($values['intro_enabled'] ?? '0') === '1' ? 'checked' : '' ?>> Play intro on app open</label>
<label>TMDB API key (kept on the panel)</label><input name="tmdb_key" value="<?= h((string)($values['tmdb_key'] ?? '')) ?>">
<h2>App updates</h2>
<p><small>Set the APK build number and upload the APK below. The panel will create its direct link. Use 0 to turn off the update prompt.</small></p>
<label>Latest APK build number</label><input type="number" min="0" max="999999999" name="update_version_code" value="<?= h((string)($values['update_version_code'] ?? '0')) ?>">
<label>Upload signed APK</label><input type="file" name="update_apk" accept=".apk,application/vnd.android.package-archive">
<label>Direct HTTPS APK link</label><input type="url" name="update_apk_url" value="<?= h((string)($values['update_apk_url'] ?? '')) ?>" placeholder="https://myflixtown.com/updates/FlixTown.apk">
<label>What is new</label><input name="update_notes" value="<?= h((string)($values['update_notes'] ?? '')) ?>" maxlength="250">
<label><input type="checkbox" name="update_required" <?= ($values['update_required'] ?? '0') === '1' ? 'checked' : '' ?>> Require this update</label>
<label>Cash App URL</label><input name="cashapp_url" value="<?= h((string)($values['cashapp_url'] ?? '')) ?>">
<label>1 month price</label><input name="price_1m" value="<?= h((string)($values['price_1m'] ?? '15.00')) ?>">
<label>3 month price</label><input name="price_3m" value="<?= h((string)($values['price_3m'] ?? '40.00')) ?>">
<label>6 month price</label><input name="price_6m" value="<?= h((string)($values['price_6m'] ?? '75.00')) ?>">
<label>12 month price</label><input name="price_12m" value="<?= h((string)($values['price_12m'] ?? '130.00')) ?>">
<label>Announcement</label><textarea name="announcement"><?= h((string)($values['announcement'] ?? '')) ?></textarea>
<label><input type="checkbox" name="maintenance" <?= ($values['maintenance'] ?? '0') === '1' ? 'checked' : '' ?>> Maintenance mode</label>
<button name="save" value="1">Save settings</button></form>
<p><small>Pairing codes expire after ten minutes. Settings are fetched by the TV app each time it opens.</small></p>
<?php endif; ?></main></html>
