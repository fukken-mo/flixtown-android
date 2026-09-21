<?php
declare(strict_types=1);

/**
 * Security headers applied to every JSON API response. Deliberately no
 * Access-Control-Allow-Origin: the client is a native Android app, not a
 * browser, so there is no legitimate cross-origin use case and no wildcard
 * CORS is ever added here.
 */
function ft_send_security_headers(): void
{
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: no-referrer');
    header('X-Frame-Options: DENY');
    header("Content-Security-Policy: default-src 'none'");
    header('Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()');
}

function ft_json_response(array $data, int $statusCode = 200): void
{
    ft_send_security_headers();
    http_response_code($statusCode);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_SLASHES);
    exit;
}

function ft_json_error(string $message, int $statusCode = 400): void
{
    ft_json_response(['error' => $message], $statusCode);
}

function ft_require_post(): void
{
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
        ft_json_error('Method not allowed', 405);
    }
}

function ft_read_json_body(): array
{
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        return [];
    }
    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : [];
}
