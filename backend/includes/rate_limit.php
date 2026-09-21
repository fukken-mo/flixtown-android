<?php
declare(strict_types=1);

require_once __DIR__ . '/util.php';

/**
 * Simple DB-backed fixed-window rate limiter. Row-locked so concurrent
 * requests from the same identifier serialize instead of racing past the
 * limit.
 */
function ft_rate_limit(PDO $pdo, string $bucket, string $identifier, int $maxAttempts, int $windowSeconds): bool
{
    $pdo->beginTransaction();
    try {
        $stmt = $pdo->prepare('SELECT attempts, window_started_at FROM rate_limits WHERE bucket = ? AND identifier = ? FOR UPDATE');
        $stmt->execute([$bucket, $identifier]);
        $row = $stmt->fetch();

        $now = time();

        if ($row === false) {
            $ins = $pdo->prepare('INSERT INTO rate_limits (bucket, identifier, attempts, window_started_at) VALUES (?, ?, 1, ?)');
            $ins->execute([$bucket, $identifier, $now]);
            $pdo->commit();
            return true;
        }

        $windowStart = (int) $row['window_started_at'];
        if ($now - $windowStart > $windowSeconds) {
            $upd = $pdo->prepare('UPDATE rate_limits SET attempts = 1, window_started_at = ? WHERE bucket = ? AND identifier = ?');
            $upd->execute([$now, $bucket, $identifier]);
            $pdo->commit();
            return true;
        }

        if ((int) $row['attempts'] >= $maxAttempts) {
            $pdo->commit();
            return false;
        }

        $upd = $pdo->prepare('UPDATE rate_limits SET attempts = attempts + 1 WHERE bucket = ? AND identifier = ?');
        $upd->execute([$bucket, $identifier]);
        $pdo->commit();
        return true;
    } catch (Throwable $e) {
        $pdo->rollBack();
        throw $e;
    }
}
