<?php
declare(strict_types=1);

/**
 * The only place the backend talks to TMDB. The API key lives in secrets.php
 * (outside public_html, never committed) and is never returned to the app —
 * config.php only ever exposes the boolean `tmdb_enabled` flag. Every actual
 * TMDB call is proxied through these two functions so the key never leaves
 * the server.
 */

const FT_TMDB_BASE = 'https://api.themoviedb.org/3';

function ft_tmdb_api_key(): ?string
{
    $key = trim((string) (ft_secrets()['tmdb_api_key'] ?? ''));
    return $key === '' ? null : $key;
}

/**
 * Whether the admin panel has TMDB enrichment turned on. Deliberately
 * fail-safe: an installation whose app_settings table predates the
 * tmdb_enabled column (added alongside this proxy) would otherwise throw an
 * uncaught PDOException on every credits/resolve call — that's treated the
 * same as "not enabled" rather than as a fatal error, with a log line
 * pointing at the real cause (a pending schema migration) instead of a raw
 * stack trace reaching the client.
 */
function ft_tmdb_is_enabled(PDO $pdo): bool
{
    try {
        $stmt = $pdo->query('SELECT tmdb_enabled FROM app_settings WHERE id = 1');
        $settings = $stmt->fetch();
        return $settings !== false && (bool) $settings['tmdb_enabled'];
    } catch (PDOException $e) {
        error_log('[tmdb_client] tmdb_enabled check failed (is app_settings.tmdb_enabled migrated on this DB?): ' . $e->getMessage());
        return false;
    }
}

/**
 * @return array{ok: bool, data?: array, reason?: string}
 */
function ft_tmdb_get(string $path, array $query): array
{
    $apiKey = ft_tmdb_api_key();
    if ($apiKey === null) {
        return ['ok' => false, 'reason' => 'tmdb_not_configured'];
    }
    if (!function_exists('curl_init')) {
        return ['ok' => false, 'reason' => 'curl_unavailable'];
    }

    $query['api_key'] = $apiKey;
    $url = FT_TMDB_BASE . $path . '?' . http_build_query($query);

    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 6,
        CURLOPT_TIMEOUT => 10,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_HTTPHEADER => ['Accept: application/json'],
    ]);
    $raw = curl_exec($ch);
    $curlError = curl_error($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($raw === false) {
        error_log('[tmdb_client] ' . $curlError);
        return ['ok' => false, 'reason' => 'unreachable'];
    }
    if ($httpCode < 200 || $httpCode >= 300) {
        return ['ok' => false, 'reason' => 'tmdb_error_' . $httpCode];
    }

    $data = json_decode($raw, true);
    if (!is_array($data)) {
        return ['ok' => false, 'reason' => 'malformed_response'];
    }

    return ['ok' => true, 'data' => $data];
}

/**
 * @param string $type "movie" or "tv"
 * @return array{ok: bool, cast?: array, reason?: string}
 */
function ft_tmdb_credits(string $type, int $tmdbId): array
{
    if (!in_array($type, ['movie', 'tv'], true) || $tmdbId <= 0) {
        return ['ok' => false, 'reason' => 'invalid_request'];
    }

    $result = ft_tmdb_get("/$type/$tmdbId/credits", []);
    if (!$result['ok']) {
        return $result;
    }

    $rawCast = $result['data']['cast'] ?? [];
    if (!is_array($rawCast)) {
        return ['ok' => false, 'reason' => 'malformed_response'];
    }

    $cast = [];
    foreach ($rawCast as $member) {
        if (!is_array($member) || !isset($member['id'], $member['name'])) {
            continue;
        }
        $cast[] = [
            'id' => (int) $member['id'],
            'name' => (string) $member['name'],
            'character' => isset($member['character']) ? (string) $member['character'] : null,
            'profile_path' => isset($member['profile_path']) ? (string) $member['profile_path'] : null,
            'order' => isset($member['order']) ? (int) $member['order'] : 999,
        ];
    }

    usort($cast, fn ($a, $b) => $a['order'] <=> $b['order']);
    return ['ok' => true, 'cast' => array_slice($cast, 0, 20)];
}

/**
 * @param string $type "movie" or "tv"
 * @return array{ok: bool, tmdb_id?: ?int, reason?: string}
 */
function ft_tmdb_resolve(string $type, string $query, ?int $year): array
{
    if (!in_array($type, ['movie', 'tv'], true) || trim($query) === '') {
        return ['ok' => false, 'reason' => 'invalid_request'];
    }

    $params = ['query' => $query];
    if ($year !== null && $year > 0) {
        $params[$type === 'movie' ? 'year' : 'first_air_date_year'] = $year;
    }

    $result = ft_tmdb_get("/search/$type", $params);
    if (!$result['ok']) {
        return $result;
    }

    $results = $result['data']['results'] ?? [];
    $first = is_array($results) ? ($results[0] ?? null) : null;
    $tmdbId = is_array($first) && isset($first['id']) ? (int) $first['id'] : null;

    return ['ok' => true, 'tmdb_id' => $tmdbId];
}
