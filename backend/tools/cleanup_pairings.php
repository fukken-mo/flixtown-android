<?php
declare(strict_types=1);

/**
 * Cron entry point: reaps rows from `pairings` that have finished their job.
 *
 * - pending/expired rows older than 1 day: the pairing was abandoned or timed
 *   out; nothing references them anymore.
 * - acked rows older than 1 hour: kept only so pair/ack.php can replay the
 *   same device token to a delivery retry (see that file's comments). An
 *   hour is far longer than any real retry window.
 *
 * This file lives outside public_html and is meant to be invoked by cron via
 * the PHP CLI, never over HTTP. It refuses to run under a web SAPI.
 *
 * Suggested cron line (hourly):
 *   0 * * * * /usr/bin/php /home/USER/backend/tools/cleanup_pairings.php >> /home/USER/backend/storage/logs/cleanup.log 2>&1
 */

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit('This script may only be run from the command line.');
}

require_once __DIR__ . '/../includes/db.php';

$pdo = ft_db();

$deletedStale = $pdo->exec(
    "DELETE FROM pairings WHERE status IN ('pending', 'expired') AND created_at < (NOW() - INTERVAL 1 DAY)"
);

$deletedAcked = $pdo->exec(
    "DELETE FROM pairings WHERE status = 'acked' AND completed_at < (NOW() - INTERVAL 1 HOUR)"
);

$rateLimitDeleted = $pdo->exec(
    'DELETE FROM rate_limits WHERE window_started_at < ' . (time() - 3600)
);

printf(
    "[%s] cleanup_pairings: removed %d stale pairing(s), %d acked pairing(s), %d rate-limit row(s)\n",
    date('c'),
    $deletedStale,
    $deletedAcked,
    $rateLimitDeleted
);
