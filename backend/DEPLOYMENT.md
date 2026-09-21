# Flix Town backend — cPanel deployment

## Layout

```
backend/
  secrets.php          <- create from secrets.example.php, keep OUTSIDE public_html
  includes/            <- keep OUTSIDE public_html
  public_html/         <- upload the CONTENTS of this folder into your account's public_html
  schema.sql
```

On a typical cPanel account, `public_html/` is the only directory served over
the web. Upload this repo's `backend/secrets.php` and `backend/includes/` one
level **above** `public_html` (e.g. into your home directory), and the
contents of `backend/public_html/` directly into your account's
`public_html/` (so `pair.php` ends up at `public_html/pair.php`, etc.).

## Steps

1. Create a MySQL database and user in cPanel, grant the user all privileges
   on that database.
2. Import the schema:
   ```
   mysql -u DBUSER -p DBNAME < schema.sql
   ```
3. Copy `secrets.example.php` to `secrets.php` (kept outside `public_html`)
   and fill in:
   - `db.host` / `db.name` / `db.user` / `db.pass`
   - `sodium_key_hex` — generate with `php -r "echo bin2hex(random_bytes(32));"`
4. Update `app_settings.xtream_base_url` in the database if it differs from
   the placeholder inserted by `schema.sql`.
5. Set real renewal prices in the `renewal_prices` table when the renewal
   milestone ships — never invented values ship in code.
6. Confirm the domain (`tv.streamtown.live`) serves HTTPS with a valid
   certificate before anything else. `pair.php` refuses to render over plain
   HTTP by design.
7. Once HTTPS is confirmed stable, uncomment the `Strict-Transport-Security`
   header in `public_html/pair.php`.
8. Confirm PHP has the `pdo_mysql`, `curl`, `sodium`, and `mbstring`
   extensions enabled (standard on modern cPanel PHP selectors).
9. Verify `GET https://tv.streamtown.live/api/v1/config.php` returns JSON,
   and `https://tv.streamtown.live/pair` renders the activation form.

## Not yet implemented (future milestones)

- Renewal request API + Cash App flow (table exists, no endpoints yet).
- Admin panel (dashboard, device/pairing management, `admin_users` table
  exists but has no login flow yet).
- TMDB metadata proxy (`tmdb_enabled` flag exists in config; no proxy
  endpoints yet — never expose `tmdb_api_key` to the app).
- App update enforcement beyond the config fields already served
  (`min_app_version_code`, `force_update`, `update_url`).
