<?php
declare(strict_types=1);

/**
 * Copy this file to `secrets.php` and place it OUTSIDE public_html (this
 * directory, "backend/", is the right place on a typical cPanel account:
 * only "backend/public_html" is served over the web). Fill in real values
 * there. Never commit the real secrets.php to version control.
 */
return [
    'db' => [
        'host' => '127.0.0.1',
        'name' => 'CHANGE_ME_db_name',
        'user' => 'CHANGE_ME_db_user',
        'pass' => 'CHANGE_ME_db_password',
        'charset' => 'utf8mb4',
    ],

    // Generate with: php -r "echo bin2hex(random_bytes(32));"
    'sodium_key_hex' => 'CHANGE_ME_64_HEX_CHARACTERS',

    // TMDB proxy key (backend-only; never shipped to the app). Used by
    // /api/v1/tmdb/credits.php and /api/v1/tmdb/resolve.php whenever
    // app_settings.tmdb_enabled is also turned on in the admin panel — both
    // conditions must hold or those endpoints report {enabled: false}.
    'tmdb_api_key' => '',
];
