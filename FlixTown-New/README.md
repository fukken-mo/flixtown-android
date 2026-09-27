# Flix Town new build, development checkpoint

This is a new panel, QR pairing flow and Android TV source, separate from the old Flix Town app. The intended panel path is `https://panelsandapps.com/panels/flixtown2027/`. The activation page belongs at `https://myflixtown.com/activate.php`.

Development status: Android source has not been compiled or tested on a TV. This environment has no Android SDK or Gradle. The panel PHP code has not been syntax checked by a PHP runtime here. Do not deploy this checkpoint as a finished app. The files will be delivered separately for each cPanel after integration verification.

## Installation

1. Back up the current host folders and database before replacing files.
2. Create a MySQL database and import `panel/schema.sql` through phpMyAdmin.
3. Copy `panel/private/config.example.php` to `panel/private/config.php` and set database values. Generate the app key with `php -r 'echo bin2hex(random_bytes(32)), PHP_EOL;'`. Generate an admin hash with `php -r 'echo password_hash("YOUR_UNIQUE_PASSWORD", PASSWORD_DEFAULT), PHP_EOL;'`. To keep the config outside the document root, set the `FLIXTOWN_CONFIG` server environment variable to its absolute path. The packaged cPanel layout instead protects `private` with `.htaccess`.
4. Upload `panel/api`, `panel/admin` and `panel/private` to the chosen panel folder. Upload `qr-site/activate.php` to the myflixtown.com document root. Both domains need HTTPS.
5. Configure the Xtream URL and intro settings at `/panels/flixtown2027/admin/`. Do not deploy this foundation until the TV app and end-to-end pairing have been tested.

## Protocol

- TV POSTs `{}` to `/api/pair-start.php`; displays the returned QR URL and eight digit code. Store the random verifier on the TV only.
- Phone opens the activation URL and submits code plus Xtream credentials to `/api/pair-activate.php`. The panel validates against Xtream and encrypts the credentials temporarily.
- TV POSTs `{code,verifier}` to `/api/pair-poll.php` every few seconds. It receives the account once, then the panel clears the temporary ciphertext. Codes expire after ten minutes.
- App GETs `/api/config.php` on each open. It renders cached catalog immediately and updates catalog in the background. The admin UI is a first pass; both domains and the Android app need integration and device testing.
- Android source in `android/` uses native TV focus views, Leanback grids, Media3 playback, encrypted local account storage and a cached catalog. Search, Favorites, expiration/renewal, cast, trailer lookup and a first Up Next prompt are implemented. Remote navigation, subtitle focus, decoder compatibility, playback progress, update flow and performance require device testing and further work.
- Renewal requests are manual. The panel records the request and phone number; an admin must verify payment and extend the same account in Xtream before marking it handled. Automatic Xtream extension requires the server's admin API details and credentials.

## Deployment notes

Restrict `panel/private` from direct HTTP access on Nginx or LiteSpeed if `.htaccess` is ignored. Set the Xtream URL only to a server you control because the panel contacts it with account credentials. Use an HTTPS Xtream endpoint if one is available. Rate limits and abuse monitoring are needed before public launch.
