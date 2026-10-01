<?php
/**
 * Flix Town update API.
 *
 * GET app-update.php?package=<app package>&version_code=<installed versionCode>
 *
 * Returns the newest APK published in app-update-admin.php for that package: version code and
 * name read from the APK's own manifest, the APK link, its SHA-256 and signing certificate, and
 * the release notes. Uses the stored inspection; the APK link is re-checked at most every few
 * minutes (a HEAD request) and inspected again only when the file there has changed.
 */
require_once __DIR__ . '/flixtown-update/UpdateStore.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, max-age=0');
header('Pragma: no-cache');

$settings = require __DIR__ . '/flixtown-update/settings.php';
$package = isset($_GET['package']) ? preg_replace('/[^A-Za-z0-9._]/', '', (string)$_GET['package']) : '';
try {
    $store = new FlixUpdateStore($settings);
    echo json_encode($store->apiResponse($package), JSON_UNESCAPED_SLASHES);
} catch (Exception $e) {
    http_response_code(500);
    echo json_encode(array('ok' => false, 'error' => 'The update service failed: ' . $e->getMessage()), JSON_UNESCAPED_SLASHES);
}
