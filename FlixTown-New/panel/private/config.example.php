<?php
declare(strict_types=1);
// Copy to config.php, preferably outside public_html. Never commit the real file.
return [
    'db_dsn' => 'mysql:host=localhost;dbname=YOUR_DB;charset=utf8mb4',
    'db_user' => 'YOUR_DB_USER',
    'db_password' => 'YOUR_DB_PASSWORD',
    'app_key' => 'REPLACE_WITH_64_HEX_CHARACTERS_FROM_BIN2HEX_RANDOM_BYTES_32',
    'qr_origin' => 'https://myflixtown.com',
    'activation_url' => 'https://myflixtown.com/activate.php',
    'admin_user' => 'admin',
    'admin_password_hash' => 'REPLACE_WITH_PASSWORD_HASH',
];
