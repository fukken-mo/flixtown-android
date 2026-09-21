# cPanel install guide — flixtown.panelsandapps.com

This matches the real, already-created deployment:

- Web root (subdomain document root): `/home/panelsan/public_html/flixtown`
- Private backend root: `/home/panelsan/flixtown-backend`
- SSL: already active for `https://flixtown.panelsandapps.com` — nothing to
  do here.

Every public PHP entry point requires one small file,
`public_html/bootstrap.php`, via `$_SERVER['DOCUMENT_ROOT']` (which Apache
sets to the subdomain's document root on every request). That file defines
`FT_BACKEND_ROOT` as `/home/panelsan/flixtown-backend` and every entry point
uses `FT_BACKEND_ROOT . '/includes/...'` from there — no file has the
`../../../../` path-counting that broke when the web root and backend root
weren't simple siblings, and nothing hardcodes the username `panelsan`
outside this guide itself (the code only knows "backend root = two levels up
from the web root, then into `flixtown-backend`", which is exactly this
layout).

## 1. Upload and extract the backend

Upload `flixtown-cpanel-backend.zip` and extract it into your home
directory. You'll get a `flixtown-cpanel-backend/` folder containing
`public_html/`, `includes/`, `tools/`, `storage/`, `schema.sql`, and
`secrets.example.php`.

## 2. Place the public files

Copy the **contents** of `flixtown-cpanel-backend/public_html/` into
`/home/panelsan/public_html/flixtown/`, so you end up with exactly:

```
/home/panelsan/public_html/flixtown/bootstrap.php
/home/panelsan/public_html/flixtown/pair.php
/home/panelsan/public_html/flixtown/.htaccess
/home/panelsan/public_html/flixtown/api/v1/config.php
/home/panelsan/public_html/flixtown/api/v1/auth/register.php
/home/panelsan/public_html/flixtown/api/v1/pair/start.php
/home/panelsan/public_html/flixtown/api/v1/pair/status.php
/home/panelsan/public_html/flixtown/api/v1/pair/ack.php
```

## 3. Place the private backend files

Copy `flixtown-cpanel-backend/includes/`, `tools/`, `storage/`, and
`schema.sql` into `/home/panelsan/flixtown-backend/`, so you end up with:

```
/home/panelsan/flixtown-backend/includes/
/home/panelsan/flixtown-backend/tools/
/home/panelsan/flixtown-backend/storage/
/home/panelsan/flixtown-backend/schema.sql
```

None of this is inside `public_html`, so none of it is reachable by URL.

## 4. Create the MySQL database and user

cPanel → **MySQL Databases**: create a database (e.g. `panelsan_flixtown`)
and a user with a strong, generated password. Add the user to the database
with **all privileges**.

## 5. Import the schema

phpMyAdmin → select the database → **Import** → upload
`/home/panelsan/flixtown-backend/schema.sql`. Or via SSH:

```
mysql -u DBUSER -p DBNAME < /home/panelsan/flixtown-backend/schema.sql
```

See DATABASE-SETUP.md for what this creates.

## 6. Configure the database connection

```
cp /home/panelsan/flixtown-backend/secrets.example.php /home/panelsan/flixtown-backend/secrets.php
chmod 600 /home/panelsan/flixtown-backend/secrets.php
```

Edit `secrets.php`:

```php
'db' => [
    'host' => '127.0.0.1',
    'name' => 'panelsan_flixtown',
    'user' => 'panelsan_flixtown',
    'pass' => 'PASTE_YOUR_DB_PASSWORD',
],
```

## 7. Generate secrets

```
php -r "echo bin2hex(random_bytes(32));"
```

Paste the output into `sodium_key_hex` in `secrets.php`. This key encrypts
Xtream credentials for the few seconds they sit in the `pairings` table
between a phone submitting them and the TV fetching them.

## 8. App base URL

Nothing to configure — `flixtown.panelsandapps.com` is already the base URL
baked into the Android app's `Constants.kt`. The **Xtream server URL** is the
one thing that's backend-configurable without an app rebuild; change it any
time via `app_settings.xtream_base_url` (DATABASE-SETUP.md).

## 9. Test the pairing page

Visit `https://flixtown.panelsandapps.com/pair`. You should see the
activation form with no certificate or mixed-content warnings.

## 10. Test the config API

```
curl https://flixtown.panelsandapps.com/api/v1/config.php
```

Should return JSON including `"xtream_base_url":"http://streamtown.live:8080"`.

## 11. Test pairing start/status/ack

```
curl -X POST https://flixtown.panelsandapps.com/api/v1/pair/start.php \
  -H 'Content-Type: application/json' \
  -d '{"installation_id":"11111111-1111-4111-8111-111111111111","device_model":"Test"}'
```

Should return JSON with `pairing_id`, `public_code`, `poll_token`,
`expires_in_seconds`. Feed `pairing_id`/`poll_token` into
`api/v1/pair/status.php` the same way — it should return
`{"status":"pending"}` until a phone completes `/pair` with that code, after
which it returns the credentials, and `api/v1/pair/ack.php` (with the
`temp_device_token` from that response) returns a `device_token`.

## 12. Configure the cleanup cron

cPanel → **Cron Jobs**, hourly:

```
0 * * * * /usr/bin/php /home/panelsan/flixtown-backend/tools/cleanup_pairings.php >> /home/panelsan/flixtown-backend/storage/logs/cleanup.log 2>&1
```

This deletes abandoned pairing attempts and pairing rows whose job is done
(see `tools/cleanup_pairings.php` for exactly what and why).

## Done

HTTPS is already active, the database is populated, `/pair` renders,
`/api/v1/config.php` returns JSON, pairing start/status/ack work, and cleanup
is scheduled. Point the Android app at this backend (already the default)
and test a real QR pairing end to end before considering this live.
