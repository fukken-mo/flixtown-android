# Security checklist

Run this before first deploying `flixtown.panelsandapps.com`, and again
periodically afterward.

## Old / unknown files

- [ ] `find public_html -type f | sort` — account for every file; delete
      anything not in this package.
- [ ] No leftover test/debug scripts: `phpinfo.php`, `test.php`, `info.php`,
      `adminer.php`, old panel installers, `*.php.bak`.
- [ ] No old pairing/activation scripts from a previous version of this
      backend still reachable.
- [ ] `grep -rEn "eval\(|base64_decode\(|gzinflate\(|assert\(|system\(|shell_exec\(" public_html`
      returns nothing.
- [ ] `find public_html -newer schema.sql` matches only files from your own
      deploys.

## JavaScript

- [ ] `pair.php` ships with zero JavaScript and CSP `default-src 'none'`.
      Keep it that way.
- [ ] No third-party scripts, analytics, ad scripts, or embeds anywhere under
      `public_html`.
- [ ] No inline `onclick=`/`onload=` handlers (the nonce-based CSP blocks
      these; don't work around it).

## `.htaccess` / server config

- [ ] Exactly one `.htaccess` exists, at `public_html/.htaccess`
      (`find public_html -iname ".htaccess"`).
- [ ] No `RewriteRule`/`RewriteCond` entries beyond what's in this package,
      especially none redirecting to an external domain.
- [ ] No `php_value auto_prepend_file` / `auto_append_file` you didn't add.

## Cron jobs

- [ ] cPanel → Cron Jobs contains only the `cleanup_pairings.php` line from
      CPANEL-INSTALL-GUIDE.md.

## Old test/admin panels

- [ ] No phpMyAdmin/Adminer exposed under `public_html` (use cPanel's own
      phpMyAdmin, outside the app's web root).
- [ ] No previous version's admin panel or debug dashboard reachable.

## File permissions

- [ ] PHP files `644`, directories `755`; nothing under `public_html` is
      group/world-writable (`find public_html -perm -o+w`).
- [ ] `secrets.php` (outside `public_html`) is `600`, owned by the cPanel
      account user only.
- [ ] `includes/`, `tools/`, `storage/` are not world-readable on shared
      hosting (check with your host if unsure).

## Network / TLS

- [ ] `https://flixtown.panelsandapps.com/pair` loads with a valid
      certificate and no mixed-content warnings.
- [ ] Plain `http://flixtown.panelsandapps.com/pair` either redirects to
      HTTPS at the server level or fails outright — `pair.php` itself refuses
      to render over HTTP, but don't rely on that alone.
- [ ] `Strict-Transport-Security` is enabled in `pair.php` only after HTTPS
      is confirmed stable (see SAFE-BROWSING-GUIDE.md).

## Application-level

- [ ] `secrets.php` is not inside `public_html` (verify:
      `curl -I https://flixtown.panelsandapps.com/secrets.php` should be 403/404,
      never 200).
- [ ] `GET /api/v1/config.php` response contains no `tmdb_api_key`, database
      credentials, or the libsodium key.
- [ ] A wrong pairing code, expired code, or wrong Xtream password on
      `/pair` shows a generic error — never which part was wrong.

## After deployment

- [ ] Add and verify the domain in Google Search Console; check **Security
      Issues** periodically.
- [ ] Run the domain through the
      [Safe Browsing site status checker](https://transparencyreport.google.com/safe-browsing/search).
