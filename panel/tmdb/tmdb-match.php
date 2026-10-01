<?php
/*
 * Flix Town: shared TMDB helpers for ratings.php and trending.php (not an endpoint by itself).
 *
 * Matching rules (the Android app uses the same normalisation in TitleMatch.java):
 *  - Provider decorations are removed before comparing: "EN - ", "4K-EN - ", "|EN| ", "[EN] ",
 *    "US: ", quality tags such as "[4K]" / "(HD)" / trailing "1080p", and a trailing "(2019)".
 *  - Titles are compared case-, accent- and punctuation-insensitively, "&" equals "and" and a
 *    leading "The" is ignored. Only exact (normalised) title matches count; there is no fuzzy match.
 *  - When the catalog knows the year, the TMDB year must be within one year of it. When the year is
 *    unknown, exactly one exact-title match must exist, otherwise the title is treated as ambiguous.
 *  - A TMDB ID from the catalog is used first, but only if that TMDB record's title matches too
 *    (providers sometimes store the wrong ID); otherwise the search above decides.
 *  - A rating is shown only with at least FT_MIN_VOTES votes.
 */
if (basename((string)($_SERVER['SCRIPT_FILENAME'] ?? '')) === basename(__FILE__)) { http_response_code(404); exit; }

const FT_MIN_VOTES = 20;

/** Removes provider prefixes/suffixes and returns [title, year found in "(2019)" or 0]. */
function ftCleanTitle(string $s): array {
    $s = $original = trim(preg_replace('/\s+/u', ' ', $s) ?? $s);
    $year = 0;
    for ($i = 0; $i < 4; $i++) {
        $before = $s;
        $s = preg_replace('/^(?:\[[^\]]{1,12}\]|\|[^|]{1,12}\|)\s*(?:[-:|]\s*)?/u', '', $s) ?? $s;
        $s = preg_replace('/^[A-Z0-9+]{2,5}(?:[- ][A-Z0-9+]{2,5})?\s*(?:\|\s*|-\s+)/u', '', $s) ?? $s;
        $s = preg_replace('/^[A-Z]{2}:\s+/u', '', $s) ?? $s;
        $s = preg_replace('/\s*[\[(](?:4K|UHD|FHD|HD|SD|HDR|HEVC|1080p|720p|2160p|MULTI(?:[- ]?SUBS?)?|VOSTFR|SUBS?|DUB(?:BED)?|[A-Z]{2})[\])]/iu', '', $s) ?? $s;
        $s = preg_replace('/\s+(?:4K|UHD|FHD|HDR|1080p|720p|2160p)$/iu', '', $s) ?? $s;
        if (preg_match('/^(.*\S)\s*[\[(]((?:19|20)\d{2})[\])]$/u', $s, $m)) { $s = $m[1]; $year = (int)$m[2]; }
        $s = trim($s);
        if ($s === $before) break;
    }
    return [$s === '' ? $original : $s, $year];
}

/** Comparison key: lower case, no accents or punctuation, "&" = "and", no leading "the". */
function ftNormalize(string $s): string {
    $s = ftCleanTitle($s)[0];
    if (class_exists('Normalizer')) {
        $d = Normalizer::normalize($s, Normalizer::FORM_D);
        if (is_string($d)) $s = preg_replace('/\p{Mn}+/u', '', $d) ?? $s;
    }
    $s = mb_strtolower($s, 'UTF-8');
    $s = str_replace('&', ' and ', $s);
    $s = preg_replace("/['’`]/u", '', $s) ?? $s;
    $s = preg_replace('/[^\p{L}\p{N}]+/u', ' ', $s) ?? $s;
    $s = trim($s);
    if (str_starts_with($s, 'the ')) $s = substr($s, 4);
    return $s;
}

function ftYearOf(array $r): int {
    $date = (string)($r['release_date'] ?? $r['first_air_date'] ?? '');
    return preg_match('/^((?:18|19|20)\d{2})/', $date, $m) ? (int)$m[1] : 0;
}

/** True when one of the record's titles equals the wanted one (normalised). */
function ftTitleMatches(array $r, string $want): bool {
    foreach (['title', 'original_title', 'name', 'original_name'] as $f) {
        if (isset($r[$f]) && is_string($r[$f]) && $r[$f] !== '' && ftNormalize($r[$f]) === $want) return true;
    }
    return false;
}

/** The search result that is unambiguously this title, or null. */
function ftPick(array $results, string $title, int $year): ?array {
    $want = ftNormalize($title);
    if ($want === '') return null;
    $hits = [];
    foreach ($results as $r) {
        if (!is_array($r) || empty($r['id']) || !ftTitleMatches($r, $want)) continue;
        $y = ftYearOf($r);
        if ($year > 0 && ($y === 0 || abs($y - $year) > 1)) continue;
        $hits[] = ['r' => $r, 'y' => $y];
    }
    if (!$hits) return null;
    if ($year > 0) {
        $same = array_values(array_filter($hits, fn($h) => $h['y'] === $year));
        if ($same) $hits = $same;
    } elseif (count($hits) > 1) {
        return null;   // e.g. two different series both called "The Office" and no year to tell them apart
    }
    usort($hits, fn($a, $b) => (int)($b['r']['vote_count'] ?? 0) <=> (int)($a['r']['vote_count'] ?? 0));
    return $hits[0]['r'];
}

/** A TMDB record fetched by the catalog's TMDB ID is used only if it is recognisably the same title. */
function ftIdAccept(array $r, string $title, int $year): bool {
    if (empty($r['id']) || !ftTitleMatches($r, ftNormalize($title))) return false;
    $y = ftYearOf($r);
    return $year === 0 || $y === 0 || abs($y - $year) <= 1;
}

/** ['rating' => 8.2, 'votes' => 1234] when TMDB has enough votes, otherwise null. */
function ftRating(array $r): ?array {
    $avg = (float)($r['vote_average'] ?? 0);
    $votes = (int)($r['vote_count'] ?? 0);
    if ($avg <= 0 || $avg > 10 || $votes < FT_MIN_VOTES) return null;
    return ['rating' => round($avg, 1), 'votes' => $votes];
}

/**
 * Parallel TMDB GETs. $requests: id => [path, query]. Result per id: decoded array on 200,
 * [] on 404 (definitely nothing there), null on any other failure (not cached, retried later).
 */
function ftTmdbMulti(array $requests, string $key): array {
    $base = getenv('FLIXTOWN_TMDB_BASE') ?: 'https://api.themoviedb.org/3/';
    $bearer = strlen($key) > 60 && substr_count($key, '.') === 2;   // TMDB v4 read access token
    $mh = curl_multi_init();
    $handles = [];
    foreach ($requests as $id => [$path, $query]) {
        if (!$bearer) $query['api_key'] = $key;
        $ch = curl_init($base . $path . '?' . http_build_query($query));
        curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_CONNECTTIMEOUT => 4, CURLOPT_TIMEOUT => 8,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_PROTOCOLS => str_starts_with($base, 'https://') ? CURLPROTO_HTTPS : (CURLPROTO_HTTP | CURLPROTO_HTTPS),
            CURLOPT_HTTPHEADER => $bearer ? ['Authorization: Bearer ' . $key, 'Accept: application/json'] : ['Accept: application/json']]);
        curl_multi_add_handle($mh, $ch);
        $handles[$id] = $ch;
    }
    do {
        $status = curl_multi_exec($mh, $running);
        if ($running) curl_multi_select($mh, 1.0);
    } while ($running && $status === CURLM_OK);
    $out = [];
    foreach ($handles as $id => $ch) {
        $code = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        $body = curl_multi_getcontent($ch);
        $data = $code === 200 && is_string($body) ? json_decode($body, true) : null;
        $out[$id] = $code === 404 ? [] : (is_array($data) ? $data : null);
        curl_multi_remove_handle($mh, $ch);
        curl_close($ch);
    }
    curl_multi_close($mh);
    return $out;
}

/** Cached payloads (cache_key => payload) that have not expired. A missing table just means no cache. */
function ftCacheGet(PDO $db, array $keys, bool $evenExpired = false): array {
    if (!$keys) return [];
    try {
        $sql = 'SELECT cache_key, payload FROM tmdb_cache WHERE cache_key IN (' . implode(',', array_fill(0, count($keys), '?')) . ')';
        $args = array_values($keys);
        if (!$evenExpired) { $sql .= ' AND expires_at > ?'; $args[] = gmdate('Y-m-d H:i:s'); }
        $q = $db->prepare($sql);
        $q->execute($args);
        $out = [];
        foreach ($q->fetchAll(PDO::FETCH_ASSOC) as $row) $out[$row['cache_key']] = (string)$row['payload'];
        return $out;
    } catch (Throwable $e) { return []; }
}

function ftCacheSet(PDO $db, string $key, string $payload, int $seconds): void {
    try {
        $db->prepare('DELETE FROM tmdb_cache WHERE cache_key=?')->execute([$key]);
        $db->prepare('INSERT INTO tmdb_cache (cache_key, payload, expires_at) VALUES (?,?,?)')
            ->execute([$key, $payload, gmdate('Y-m-d H:i:s', time() + $seconds)]);
    } catch (Throwable $e) { /* serving the live answer matters more than caching it */ }
}
