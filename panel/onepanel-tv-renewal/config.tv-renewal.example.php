<?php
/*
 * Add these two blocks to the array returned by config/config.php (do not commit real values).
 * Prices stay in ['pricing']['flix_town'] (1 => 15.00, 3 => 40.00, 6 => 75.00, 12 => 135.00).
 */
return [
  'tv_api' => [
    'enabled' => true,
    // 32+ random characters, e.g. the output of: php -r 'echo bin2hex(random_bytes(32));'
    'session_secret' => 'CHANGE_ME_TO_A_LONG_RANDOM_VALUE',
  ],
  'square' => [
    'environment' => 'production',            // or 'sandbox' while testing
    'access_token' => 'YOUR_SQUARE_ACCESS_TOKEN',
    'location_id' => 'YOUR_SQUARE_LOCATION_ID',
    'version' => '2025-01-23',
    // Square Developer Dashboard > Webhooks: subscribe payment.created and payment.updated to webhook_url.
    'webhook_signature_key' => 'YOUR_SQUARE_WEBHOOK_SIGNATURE_KEY',
    'webhook_url' => 'https://panelsandapps.com/panels/onepanel/api/square-webhook.php',
  ],
];
