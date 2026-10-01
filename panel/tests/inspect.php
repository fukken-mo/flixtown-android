<?php
// Prints the inspector's result for one APK as JSON (used by the tests and by CI against real APKs).
require_once __DIR__ . '/../flixtown-update/ApkInspector.php';
try {
    echo json_encode(FlixApkInspector::inspect($argv[1]), JSON_UNESCAPED_SLASHES), "\n";
} catch (Exception $e) {
    echo json_encode(array('error' => $e->getMessage())), "\n";
    exit(1);
}
