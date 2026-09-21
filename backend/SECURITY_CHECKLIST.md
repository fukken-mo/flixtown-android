# cPanel security audit checklist

Run this before and after deploying, and periodically afterward. It exists
because the activation domain (`tv.streamtown.live`) has previously triggered
a Google Safe Browsing warning; the goal is a account that would pass a
Safe Browsing review cleanly, not just a working pairing flow.

## Old / unknown files

- [ ] List everything under `public_html` (`find public_html -type f`) and
      account for every file. Delete anything you didn't just deploy.
- [ ] Search for leftover test/debug scripts: `phpinfo.php`, `test.php`,
      `info.php`, `adminer.php`, old panel installers, `.php.bak` files.
- [ ] Search for old pairing/activation scripts from any previous version of
      this backend that are no longer referenced by the current codebase.
- [ ] Check for web shells / obfuscated PHP: search for `eval(`, `base64_decode(`,
      `gzinflate(`, `assert(`, `system(`, `shell_exec(` in anything you didn't
      write yourself (`grep -rEn "eval\(|base64_decode\(|gzinflate\(|assert\(|system\(|shell_exec\(" public_html`).
- [ ] Confirm file modification times (`find public_html -newer schema.sql`)
      match your own deploys — unexpected recent changes are a compromise signal.

## JavaScript

- [ ] `pair.php` ships with **zero** JavaScript and a strict CSP
      (`default-src 'none'`). Keep it that way — every future change to this
      page should ask "does this really need JS?" before adding any.
- [ ] No third-party script tags, analytics, ad scripts, or embeds anywhere
      under `public_html`.
- [ ] No inline `onclick=`/`onload=` handlers (CSP with a nonce blocks these
      anyway; don't work around it).

## .htaccess / server config

- [ ] Only the `.htaccess` shipped in this repo exists under `public_html`
      (`find public_html -iname ".htaccess"` should return exactly one file).
- [ ] No unexpected `RewriteRule`/`RewriteCond` entries — especially ones
      that redirect to external domains (an open-redirect / malware-injection
      pattern).
- [ ] No `php_value auto_prepend_file` / `auto_append_file` directives you
      didn't add yourself.

## Cron jobs

- [ ] Review cPanel → Cron Jobs for anything you didn't schedule.
- [ ] If a pairing-cleanup cron is added later (deleting expired `pairings`
      rows), confirm it only runs `DELETE FROM pairings WHERE expires_at < NOW()`
      style queries — nothing that shells out or fetches remote URLs.

## Old test/admin panels

- [ ] Confirm no phpMyAdmin, Adminer, or similar tool is exposed under
      `public_html` (these belong behind cPanel's own auth, not the app's
      web root).
- [ ] Confirm no previous version's admin panel or debug dashboard remains
      reachable.

## File permissions

- [ ] PHP files: `644`. Directories: `755`. Nothing under `public_html`
      should be group- or world-writable (`find public_html -perm -o+w`).
- [ ] `secrets.php` (outside `public_html`) is `600` and owned by the cPanel
      account user only.
- [ ] `backend/includes` is not readable by any other cPanel account on
      shared hosting (check with your host if unsure).

## Network / TLS

- [ ] `https://tv.streamtown.live/pair` loads with a valid certificate and no
      mixed-content warnings (check DevTools console).
- [ ] Plain `http://tv.streamtown.live/pair` either redirects to HTTPS at the
      server level or is blocked — `pair.php` itself refuses to render over
      HTTP, but don't rely on that alone.
- [ ] Run the domain through Google's Safe Browsing site status
      (`https://transparencyreport.google.com/safe-browsing/search`) after
      deployment and again after any DNS/hosting change.
- [ ] `Strict-Transport-Security` is only enabled (see `pair.php`) once HTTPS
      has been confirmed stable — enabling it prematurely on a
      misconfigured host can lock out legitimate visitors.
