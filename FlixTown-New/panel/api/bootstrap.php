<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$configFile = dirname(__DIR__) . '/private/config.php';
if (!is_file($configFile)) {
    http_response_code(503);
    exit(json_encode(['error' => 'Panel setup incomplete']));
}
$config = require $configFile;
if (!is_array($config) || !preg_match('/^[a-f0-9]{64}$/i', $config['app_key'] ?? '')) {
    http_response_code(503);
    exit(json_encode(['error' => 'Invalid panel configuration']));
}
$origin = $_SERVER['HTTP_ORIGIN'] ?? '';
function allowedQrOrigin(string $origin, array $config): bool {
    $base = rtrim((string)$config['qr_origin'], '/');
    return $origin !== '' && ($origin === $base || $origin === preg_replace('~^https://~', 'https://www.', $base));
}
if (allowedQrOrigin($origin, $config)) {
    header('Access-Control-Allow-Origin: ' . $origin);
    header('Vary: Origin');
    header('Access-Control-Allow-Methods: POST, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type');
}
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(allowedQrOrigin($origin, $config) ? 204 : 403);
    exit;
}
try {
    $db = new PDO($config['db_dsn'], $config['db_user'], $config['db_password'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
} catch (Throwable $e) {
    http_response_code(503);
    exit(json_encode(['error' => 'Database unavailable']));
}
function response(array $body, int $status = 200): never {
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_SLASHES);
    exit;
}
function postData(): array {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') response(['error' => 'POST required'], 405);
    if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 16384) response(['error' => 'Request too large'], 413);
    $v = json_decode(file_get_contents('php://input'), true);
    if (!is_array($v)) response(['error' => 'Invalid JSON'], 400);
    return $v;
}
function setting(PDO $db, string $name): string {
    $q = $db->prepare('SELECT value FROM settings WHERE name=?');
    $q->execute([$name]);
    return (string)($q->fetchColumn() ?: '');
}
function encryptCredentials(array $credentials, string $hexKey): string {
    $iv = random_bytes(12);
    $cipher = openssl_encrypt(json_encode($credentials), 'aes-256-gcm', hex2bin($hexKey), OPENSSL_RAW_DATA, $iv, $tag);
    if ($cipher === false) throw new RuntimeException('Encryption failed');
    return base64_encode($iv . $tag . $cipher);
}
function decryptCredentials(string $stored, string $hexKey): array {
    $raw = base64_decode($stored, true);
    if ($raw === false || strlen($raw) < 28) throw new RuntimeException('Invalid encrypted data');
    $plain = openssl_decrypt(substr($raw, 28), 'aes-256-gcm', hex2bin($hexKey), OPENSSL_RAW_DATA, substr($raw, 0, 12), substr($raw, 12, 16));
    if ($plain === false) throw new RuntimeException('Decryption failed');
    return json_decode($plain, true, 512, JSON_THROW_ON_ERROR);
}
