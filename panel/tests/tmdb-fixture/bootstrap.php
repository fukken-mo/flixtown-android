<?php
// Minimal stand-in for the panel's api/bootstrap.php (same helper names), for tests only.
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
$db = new PDO('sqlite:' . getenv('FLIXTOWN_TEST_DB'), null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
function response(array $body, int $status = 200): never { http_response_code($status); echo json_encode($body, JSON_UNESCAPED_SLASHES); exit; }
function postData(): array {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') response(['error' => 'POST required'], 405);
    $v = json_decode(file_get_contents('php://input'), true);
    if (!is_array($v)) response(['error' => 'Invalid JSON'], 400);
    return $v;
}
function setting(PDO $db, string $name): string { $q = $db->prepare('SELECT value FROM settings WHERE name=?'); $q->execute([$name]); return (string)($q->fetchColumn() ?: ''); }
