<?php
declare(strict_types=1);

function ft_secrets(): array
{
    static $secrets = null;
    if ($secrets === null) {
        $path = __DIR__ . '/../secrets.php';
        if (!is_file($path)) {
            throw new RuntimeException(
                'secrets.php is missing. Copy secrets.example.php to secrets.php (kept outside public_html) and fill it in.'
            );
        }
        $secrets = require $path;
    }
    return $secrets;
}

function ft_db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $cfg = ft_secrets()['db'];
        $dsn = sprintf(
            'mysql:host=%s;dbname=%s;charset=%s',
            $cfg['host'],
            $cfg['name'],
            $cfg['charset'] ?? 'utf8mb4'
        );
        $pdo = new PDO($dsn, $cfg['user'], $cfg['pass'], [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
    }
    return $pdo;
}
