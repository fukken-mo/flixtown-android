<?php
/**
 * Flix Town seasonal splash schedule for the TV app (public, read-only, GET).
 * The app decides on each TV from its own date; this only publishes the schedule and picture links.
 */
declare(strict_types=1);
require_once __DIR__ . '/flixtown-seasonal/SeasonalStore.php';
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'GET') { http_response_code(405); header('Allow: GET'); exit('{"error":"GET required"}'); }
header('Cache-Control: public, max-age=300');
$store = new FlixSeasonalStore(require __DIR__ . '/flixtown-seasonal/settings.php');
echo json_encode($store->publicSchedule(), JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
