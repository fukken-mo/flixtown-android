<?php
/*
 * Safety net, run from cron every few minutes (php cron/tv-renewal-sweep.php): re-checks TV
 * payments from the last 24 hours that are not finished, so a payment made after the TV was turned
 * off still renews the line even without the webhook. Command line only.
 */
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
require __DIR__ . '/../includes/bootstrap.php';
require __DIR__ . '/../includes/tv_renewal.php';
$q = $pdo->prepare("SELECT id FROM tv_payments WHERE state IN ('waiting','processing','paid','renewing') AND created_ts>?");
$q->execute([time() - 86400]);
foreach ($q->fetchAll(PDO::FETCH_COLUMN) as $id) {
  try { $row = tvr_advance($pdo, (string)$id); echo $id, ' ', $row['state'] ?? '?', "\n"; }
  catch (Throwable $e) { echo $id, ' error ', $e->getMessage(), "\n"; }
}
