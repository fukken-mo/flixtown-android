<?php
declare(strict_types=1);

require_once $_SERVER['DOCUMENT_ROOT'] . '/bootstrap.php';
require_once FT_BACKEND_ROOT . '/includes/db.php';
require_once FT_BACKEND_ROOT . '/includes/util.php';
require_once FT_BACKEND_ROOT . '/includes/xtream_client.php';
require_once FT_BACKEND_ROOT . '/includes/crypto.php';
require_once FT_BACKEND_ROOT . '/includes/rate_limit.php';
require_once FT_BACKEND_ROOT . '/includes/audit_log.php';

// Safe Browsing / customer-trust requirement: this page must never be
// reachable, or degrade, over plain HTTP.
$isHttps = ($_SERVER['HTTPS'] ?? '') === 'on' || ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https';
if (!$isHttps) {
    http_response_code(400);
    header('Content-Type: text/plain; charset=utf-8');
    exit('HTTPS is required to activate Flix Town.');
}

session_set_cookie_params([
    'lifetime' => 0,
    'path' => '/',
    'secure' => true,
    'httponly' => true,
    'samesite' => 'Strict',
]);
session_start();

$nonce = base64_encode(random_bytes(16));

header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header('X-Frame-Options: DENY');
header('Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()');
header("Content-Security-Policy: default-src 'none'; style-src 'nonce-$nonce'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
// Enable once the deployed domain has been confirmed to always serve HTTPS
// cleanly (see backend/DEPLOYMENT.md) -- premature HSTS on a misconfigured
// host can lock customers out of the activation page entirely.
// header('Strict-Transport-Security: max-age=63072000; includeSubDomains');

if (empty($_SESSION['csrf_token'])) {
    $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
}
$csrfToken = $_SESSION['csrf_token'];

$prefillCode = '';
if (isset($_GET['code']) && is_string($_GET['code']) && preg_match('/^[A-Za-z0-9]{6}$/', $_GET['code'])) {
    $prefillCode = strtoupper($_GET['code']);
}

$errorMessage = null;
$successMessage = null;

if (($_SERVER['REQUEST_METHOD'] ?? '') === 'POST') {
    $pdo = ft_db();
    $submittedCsrf = (string) ($_POST['csrf_token'] ?? '');

    if (!hash_equals($csrfToken, $submittedCsrf)) {
        $errorMessage = 'Your session expired. Please reload this page and try again.';
    } elseif (!ft_rate_limit($pdo, 'pair_complete', ft_client_ip(), 8, 300)) {
        $errorMessage = 'Too many attempts. Please wait a few minutes and try again.';
    } else {
        $code = strtoupper(trim((string) ($_POST['code'] ?? '')));
        $username = trim((string) ($_POST['username'] ?? ''));
        $password = (string) ($_POST['password'] ?? '');
        $prefillCode = $code;

        if (!preg_match('/^[A-Z0-9]{6}$/', $code) || $username === '' || $password === '') {
            $errorMessage = 'Please fill in the code, username, and password.';
        } else {
            $pdo->beginTransaction();
            try {
                $stmt = $pdo->prepare(
                    "SELECT * FROM pairings WHERE public_code = ? AND status = 'pending' AND expires_at > NOW() FOR UPDATE"
                );
                $stmt->execute([$code]);
                $pairing = $stmt->fetch();

                if ($pairing === false) {
                    $pdo->rollBack();
                    $errorMessage = 'That code is invalid or has expired. Please check your TV for a new code.';
                } else {
                    $xtreamBaseUrl = (string) $pdo->query('SELECT xtream_base_url FROM app_settings WHERE id = 1')->fetchColumn();
                    $auth = ft_xtream_authenticate($xtreamBaseUrl, $username, $password);

                    if (!$auth['ok']) {
                        $pdo->rollBack();
                        $errorMessage = 'That username or password was not accepted. Please try again.';
                        ft_audit($pdo, 'pair_complete_failed', $pairing['installation_id'], (string) $auth['reason']);
                    } else {
                        $tempDeviceToken = bin2hex(random_bytes(32));
                        $encrypted = ft_encrypt(json_encode([
                            'xtream_username' => $username,
                            'xtream_password' => $password,
                            'temp_device_token' => $tempDeviceToken,
                        ], JSON_THROW_ON_ERROR));

                        $update = $pdo->prepare(
                            "UPDATE pairings SET status = 'completed', encrypted_credentials = ?, completed_at = NOW() WHERE pairing_id = ?"
                        );
                        $update->execute([$encrypted, $pairing['pairing_id']]);
                        $pdo->commit();

                        ft_audit($pdo, 'pair_complete', $pairing['installation_id']);
                        $successMessage = 'Success! Your TV will finish signing in automatically within a few seconds.';
                        $prefillCode = '';
                        unset($_SESSION['csrf_token']); // one-time use
                    }
                }
            } catch (Throwable $e) {
                if ($pdo->inTransaction()) {
                    $pdo->rollBack();
                }
                error_log('[pair.php] ' . $e->getMessage());
                $errorMessage = 'Something went wrong. Please try again.';
            }
        }
    }
}
?><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Activate Flix Town</title>
<style nonce="<?= htmlspecialchars($nonce, ENT_QUOTES, 'UTF-8') ?>">
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { background:#0a0a0c; color:#fff; font-family: -apple-system, "Segoe UI", Roboto, Arial, sans-serif; margin:0; padding:0; }
  .wrap { max-width:420px; margin: 48px auto; padding: 32px; background:#161619; border-radius:16px; }
  h1 { font-size:22px; margin:0 0 8px; }
  p.sub { color:#afafb8; margin:0 0 24px; font-size:14px; line-height:1.5; }
  label { display:block; font-size:13px; color:#afafb8; margin-bottom:6px; margin-top:16px; }
  input { width:100%; padding:12px 14px; border-radius:8px; border:1px solid #2a2a30; background:#1f1f24; color:#fff; font-size:16px; }
  input#code { letter-spacing: 4px; text-transform: uppercase; }
  input:focus { outline: 2px solid #e3283a; }
  button { width:100%; margin-top:24px; padding:14px; border:none; border-radius:8px; background:#e3283a; color:#fff; font-size:16px; font-weight:600; cursor:pointer; }
  .msg { padding:12px 14px; border-radius:8px; margin-bottom:16px; font-size:14px; line-height:1.4; }
  .msg.error { background:#3a1418; color:#ff8a94; }
  .msg.success { background:#123a1c; color:#7be29a; }
  .hint { margin-top:24px; font-size:12px; color:#7a7a82; text-align:center; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Activate Flix Town</h1>
  <p class="sub">Enter the code shown on your TV, then your Xtream username and password, to finish activation.</p>
  <?php if ($errorMessage !== null): ?>
    <div class="msg error"><?= htmlspecialchars($errorMessage, ENT_QUOTES, 'UTF-8') ?></div>
  <?php endif; ?>
  <?php if ($successMessage !== null): ?>
    <div class="msg success"><?= htmlspecialchars($successMessage, ENT_QUOTES, 'UTF-8') ?></div>
  <?php else: ?>
  <form method="post" action="/pair.php" autocomplete="off">
    <input type="hidden" name="csrf_token" value="<?= htmlspecialchars($csrfToken, ENT_QUOTES, 'UTF-8') ?>">
    <label for="code">TV code</label>
    <input id="code" name="code" maxlength="6" required value="<?= htmlspecialchars($prefillCode, ENT_QUOTES, 'UTF-8') ?>" autocapitalize="characters">
    <label for="username">Xtream username</label>
    <input id="username" name="username" required autocapitalize="none" spellcheck="false">
    <label for="password">Xtream password</label>
    <input id="password" name="password" type="password" required>
    <button type="submit">Activate</button>
  </form>
  <?php endif; ?>
  <p class="hint">Flix Town &middot; flixtown.panelsandapps.com</p>
</div>
</body>
</html>
