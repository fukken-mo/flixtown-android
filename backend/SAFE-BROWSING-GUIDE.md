# Safe Browsing guide

The previous activation domain triggered a Google/Chrome red warning
("deceptive site" / "dangerous site"). **Do not try to bypass browser
security warnings** — the only real fix is a clean site plus a Safe Browsing
review after cleanup. This build was designed around that constraint from the
start; this guide explains why and what to check before and after deploying
`flixtown.panelsandapps.com`.

## HTTPS

- `pair.php` refuses to render at all over plain HTTP (checks `$_SERVER['HTTPS']`
  / `X-Forwarded-Proto` and exits with a plain-text error otherwise).
- No redirect chains: `pair.php` never redirects anywhere, including to
  itself. Its own `<form>` posts back to `/pair.php` and nothing else.
- `Strict-Transport-Security` is shipped commented out in `pair.php`. Only
  enable it after confirming HTTPS is stable — enabling HSTS on a
  misconfigured host can lock out real visitors, which looks exactly like the
  kind of breakage Safe Browsing flags.

## Mixed content

- `pair.php` has **zero external resources**: no scripts, no stylesheets, no
  fonts, no images from another origin. Its `<style>` block is inline with a
  per-request CSP nonce (`style-src 'nonce-...'`), so there's nothing to load
  over HTTP even by accident.
- If you ever add the Flix Town logo or any asset to this page, serve it from
  the same HTTPS origin — never hotlink from a third-party or HTTP source.

## Old / test PHP files

This is the single most common way an account gets flagged: a leftover
`test.php`, `phpinfo.php`, `info.php`, an old pairing script from a previous
build, or a debug endpoint someone forgot about. Before going live:

```
find public_html -type f -iname "*.php" | sort
```

Every file in that list should be one you recognize from this package
(`pair.php`, `api/v1/config.php`, `api/v1/auth/register.php`,
`api/v1/pair/{start,status,ack}.php`) and nothing else. Delete anything you
don't recognize — see SECURITY-CHECKLIST.md for the full audit.

## Suspicious `.htaccess`

Only one `.htaccess` should exist, at `public_html/.htaccess`, matching the
one shipped in this package. A second `.htaccess` in a subdirectory, or
unexpected `RewriteRule`/`RewriteCond` lines (especially ones pointing at an
external domain), is a strong compromise signal — Safe Browsing crawlers
specifically look for injected redirects like this.

## Cron jobs

Review cPanel → Cron Jobs. The only job this backend needs is the
`cleanup_pairings.php` line from CPANEL-INSTALL-GUIDE.md step 13. Anything
else you didn't add yourself warrants investigation — a malicious cron job is
a common persistence mechanism after a compromise.

## Injected code

Search for obfuscated PHP patterns that don't belong in this codebase:

```
grep -rEn "eval\(|base64_decode\(|gzinflate\(|assert\(|shell_exec\(|system\(" public_html
```

None of the files in this package use any of those functions. Any match is
either a false positive worth double-checking or an indicator of compromise.

## Google Search Console

Add and verify `flixtown.panelsandapps.com` in
[Search Console](https://search.google.com/search-console). Check
**Security Issues** there periodically — it's usually faster to notice a flag
here than to wait for a customer to report the red warning.

## Requesting a Safe Browsing review

If the domain (or a previous domain pointed at the same hosting account) was
ever flagged:

1. Complete the cleanup above first — do not request a review before you've
   actually removed the cause.
2. Use [Search Console's Security Issues report](https://search.google.com/search-console)
   to request a review once it shows no active issues, or use the
   [Safe Browsing site status checker](https://transparencyreport.google.com/safe-browsing/search)
   to confirm current status.
3. Reviews can take hours to days. Don't re-request repeatedly — that resets
   nothing and just adds noise.
4. Never claim the warning is fixed until you've verified — in an actual
   browser, on the actual domain — that it's gone.
