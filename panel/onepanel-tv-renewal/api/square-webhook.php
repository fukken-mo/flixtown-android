<?php
/*
 * Square webhook (payment.created / payment.updated) for TV renewals. Renews a paid line even when
 * the TV was turned off before the payment finished. The signature is checked with the webhook
 * signature key kept in config.php; unsigned or mismatched requests are ignored.
 */
header('Content-Type: application/json; charset=utf-8');
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') { http_response_code(405); exit('{}'); }
$body = (string)file_get_contents('php://input', false, null, 0, 65536);
require __DIR__ . '/../includes/bootstrap.php';
require __DIR__ . '/../includes/tv_renewal.php';
if (session_status() === PHP_SESSION_ACTIVE) session_write_close();
if (!tvr_webhook_valid($body, (string)($_SERVER['HTTP_X_SQUARE_HMACSHA256_SIGNATURE'] ?? ''))) { http_response_code(403); exit('{}'); }
$event = json_decode($body, true);
try { if (is_array($event)) tvr_webhook($pdo, $event); }
catch (Throwable $e) { error_log('tv-renewal webhook: ' . $e->getMessage()); http_response_code(500); exit('{}'); }
echo '{"ok":true}';
