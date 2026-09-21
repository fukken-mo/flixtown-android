# cPanel install guide — flixtown.panelsandapps.com

Follow these in order. Each step assumes the previous one is done.

## 1. Create the subdomain/domain

In cPanel → **Domains**, create `flixtown.panelsandapps.com` pointing at a
document root of your choice (e.g. `~/flixtown.panelsandapps.com` or a
subfolder under it). That document root is what step 4 calls "web root."

## 2. Enable SSL

In cPanel → **SSL/TLS Status** (or **AutoSSL**), issue a certificate for
`flixtown.panelsandapps.com`. Confirm `https://flixtown.panelsandapps.com/`
loads without a browser warning before continuing. Everything below assumes
HTTPS works.

## 3. Upload and extract the backend

Upload `flixtown-cpanel-backend.zip` via cPanel File Manager or SFTP and
extract it into your home directory (**not** directly into the web root yet).
You'll get a `backend/` folder with the layout described in README-FIRST.md.

## 4. What belongs inside the web root

Copy only the **contents** of `backend/public_html/` into the domain's web
root, so you end up with:

```
<web root>/pair.php
<web root>/.htaccess
<web root>/api/v1/config.php
<web root>/api/v1/auth/register.php
<web root>/api/v1/pair/start.php
<web root>/api/v1/pair/status.php
<web root>/api/v1/pair/ack.php
```

## 5. What must stay OUTSIDE the web root

Move (don't copy) these to sit one level **above** the web root, e.g. your
home directory:

```
backend/secrets.php       (after you create it in step 9 — not yet)
backend/includes/
backend/tools/
backend/storage/
backend/schema.sql
```

Nothing under these paths should ever be reachable by URL. If your web root
is `~/flixtown.panelsandapps.com`, a safe layout is:

```
~/backend/includes/
~/backend/tools/
~/backend/storage/
~/backend/secrets.php
~/flixtown.panelsandapps.com/pair.php   <- web root, from public_html/
```

The PHP files use relative `__DIR__` paths (e.g. `__DIR__ . '/../../../../includes/db.php'`)
that assume `public_html`'s contents sit directly next to `includes/`,
`tools/`, and `storage/` under a common `backend/` parent — i.e. don't
separate them further than the layout above.

## 6. Create the MySQL database and user

cPanel → **MySQL Databases**: create a database (e.g. `youruser_flixtown`)
and a user with a strong, generated password. Add the user to the database
with **all privileges**.

## 7. Import the schema

Via cPanel → **phpMyAdmin**, select the database, go to **Import**, and
upload `backend/schema.sql`. Or via SSH:

```
mysql -u DBUSER -p DBNAME < backend/schema.sql
```

See DATABASE-SETUP.md for what this creates.

## 8. Configure the database connection

Copy `backend/secrets.example.php` to `backend/secrets.php` (in the
outside-web-root location from step 5) and fill in:

```php
'db' => [
    'host' => '127.0.0.1',
    'name' => 'youruser_flixtown',
    'user' => 'youruser_flixtown',
    'pass' => 'PASTE_YOUR_DB_PASSWORD',
],
```

## 9. Generate secrets

Still in `secrets.php`, generate the libsodium key:

```
php -r "echo bin2hex(random_bytes(32));"
```

Paste the output into `sodium_key_hex`. This key encrypts Xtream credentials
for the few seconds they sit in the `pairings` table between a phone
submitting them and the TV fetching them — treat it like a password.

## 10. Set the app base URL

Nothing to configure here for the backend itself — `flixtown.panelsandapps.com`
*is* the base URL, and it's baked into the Android app's `Constants.kt`
(`BackendConstants.CONTROL_HOST`). If you ever change the control domain
again, that Kotlin constant is the only place in the app that needs updating,
and the app must be rebuilt.

What IS configurable without a rebuild is the **Xtream server URL** — update
it any time by editing the `app_settings.xtream_base_url` row in the
database (see DATABASE-SETUP.md).

## 11. Test the pairing page

Visit `https://flixtown.panelsandapps.com/pair` in a browser. You should see
the activation form (code, username, password) with no certificate warning
and no mixed-content warning in the browser console.

## 12. Test the config API

```
curl https://flixtown.panelsandapps.com/api/v1/config.php
```

Should return JSON including `"xtream_base_url":"http://streamtown.live:8080"`
(or whatever you've set it to).

## 13. Configure the cleanup cron

cPanel → **Cron Jobs**, add (hourly is enough):

```
0 * * * * /usr/bin/php ~/backend/tools/cleanup_pairings.php >> ~/backend/storage/logs/cleanup.log 2>&1
```

Adjust `~/backend/...` to match wherever you placed `includes/`/`tools/`/`storage/`
in step 5. This deletes abandoned pairing attempts and pairing rows whose job
is done (see `tools/cleanup_pairings.php` for exactly what it removes and why).

## Done

At this point: HTTPS works, the database is populated, `/pair` renders,
`/api/v1/config.php` returns JSON, and cleanup is scheduled. Point the Android
app at this backend (already the default in this build) and test a real QR
pairing end to end before considering this live.
