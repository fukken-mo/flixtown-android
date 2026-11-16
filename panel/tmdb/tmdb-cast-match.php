<?php
// Cast identity rules are stricter than rating/title display matching.
if (basename((string)($_SERVER['SCRIPT_FILENAME'] ?? '')) === basename(__FILE__)) { http_response_code(404); exit; }
require_once __DIR__ . '/tmdb-match.php';

function ftCastTitle(string $title): string {
    // Prefix protects the leading article: The Beast and Beast can be different films.
    return ftNormalize('identity ' . ftCleanTitle($title)[0]);
}

function ftCastPick(array $results, string $title, int $year): ?array {
    $want = ftCastTitle($title);
    $hits = [];
    foreach ($results as $record) {
        if (!is_array($record) || !is_numeric($record['id'] ?? null) || (int)$record['id'] <= 0) continue;
        $matches = false;
        foreach (['title', 'original_title', 'name', 'original_name'] as $field) {
            if (is_string($record[$field] ?? null) && ftCastTitle($record[$field]) === $want) $matches = true;
        }
        if (!$matches) continue;
        $recordYear = ftYearOf($record);
        if ($year > 0 && (!$recordYear || abs($recordYear - $year) > 1)) continue;
        $hits[(int)$record['id']] = $record;
    }
    if ($year > 0) {
        $sameYear = array_filter($hits, fn($r) => ftYearOf($r) === $year);
        if ($sameYear) $hits = $sameYear;
    }
    // Popularity cannot decide between remakes or distinct same-name releases.
    return count($hits) === 1 ? reset($hits) : null;
}

function ftCastCacheKey(string $kind, string $title, int $year): string {
    // Never reuse results produced by the old first-search-result lookup.
    return hash('sha256', 'cast-v2|' . $kind . '|' . ftCastTitle($title) . '|' . $year);
}
