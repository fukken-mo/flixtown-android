# Flix Town backend — read this first

Control/activation domain: **`https://flixtown.panelsandapps.com`**
Xtream server (admin-configurable after install): `http://streamtown.live:8080`

## What's in this package

```
backend/
  secrets.example.php      copy to secrets.php, fill in, keep OUTSIDE public_html
  includes/                 shared PHP library code (db, crypto, http, rate limiting, audit log, Xtream client)
  tools/                    CLI-only maintenance scripts (cron), never web-exposed
  storage/                  logs/ and cache/ — writable, outside public_html
  schema.sql                run once against a fresh database
  public_html/              upload the CONTENTS of this folder into your account's public_html
  README-FIRST.md           this file
  CPANEL-INSTALL-GUIDE.md   step-by-step install
  DATABASE-SETUP.md         schema + config details
  SAFE-BROWSING-GUIDE.md    why the old domain got a red warning, and how this build avoids it
  SECURITY-CHECKLIST.md     pre-launch and periodic audit
  TROUBLESHOOTING.md        common problems and fixes
```

## Read next

1. **CPANEL-INSTALL-GUIDE.md** — do this first, in order.
2. **DATABASE-SETUP.md** — schema details, if you need them.
3. **SAFE-BROWSING-GUIDE.md** and **SECURITY-CHECKLIST.md** — before pointing
   the Android app at this backend in production.
4. **TROUBLESHOOTING.md** — if something doesn't work.

## Never commit or upload real secrets

`secrets.example.php` contains placeholders only. Real database credentials
and the libsodium key belong in `secrets.php`, which must never be inside
`public_html` and never committed to version control.

## What this milestone does NOT include

No admin panel, no renewal payment API, no TMDB proxy. Their database tables
exist (`admin_users`, `renewal_requests`) so a later milestone doesn't need a
schema migration, but no endpoints use them yet.
