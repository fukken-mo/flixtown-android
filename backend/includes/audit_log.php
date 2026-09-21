<?php
declare(strict_types=1);

require_once __DIR__ . '/util.php';

function ft_audit(PDO $pdo, string $eventType, ?string $installationId, string $detail = ''): void
{
    try {
        $stmt = $pdo->prepare(
            'INSERT INTO audit_log (event_type, installation_id, ip_address, detail, created_at) VALUES (?, ?, ?, ?, NOW())'
        );
        $stmt->execute([$eventType, $installationId, ft_client_ip(), $detail]);
    } catch (Throwable $e) {
        // Auditing must never break the primary request.
        error_log('[audit_log] ' . $e->getMessage());
    }
}
