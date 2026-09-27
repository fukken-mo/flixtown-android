<?php
require __DIR__ . '/bootstrap.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') response(['error' => 'GET required'], 405);
response([
    'app_name' => setting($db, 'app_name'),
    'xtream_url' => setting($db, 'xtream_url'),
    'intro_enabled' => setting($db, 'intro_enabled') === '1',
    'intro_url' => setting($db, 'intro_url'),
    'logo_url' => setting($db, 'logo_url'),
    'cashapp_url' => setting($db, 'cashapp_url'),
    'plans' => ['1m' => setting($db,'price_1m'),'3m' => setting($db,'price_3m'),'6m' => setting($db,'price_6m'),'12m' => setting($db,'price_12m')],
    'announcement' => setting($db, 'announcement'),
    'maintenance' => setting($db, 'maintenance') === '1',
]);
