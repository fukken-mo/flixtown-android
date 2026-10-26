FLIX TOWN — SEASONAL SPLASH (panel patch)                                    NOT DEPLOYED
=========================================

For the existing Flix Town panel only:  https://panelsandapps.com/panels/flixtown2027/
Not for OnePanel, panel.myflixtown.com or Seerr.  No database changes (no SQL).  No cron.

What it adds (new files only — nothing existing is replaced)
------------------------------------------------------------
  admin/seasonal.php                       Admin page: Seasonal splash (uses your existing panel login)
  api/seasonal.php                         Public, read-only schedule the TV app reads (GET only)
  api/flixtown-seasonal/SeasonalStore.php  Library: validation, holiday dates, picture uploads
  api/flixtown-seasonal/settings.php       Public address of api/ and the admin page time zone
  api/flixtown-seasonal/.htaccess          Blocks web access to this folder
  api/flixtown-seasonal/data/              Saved schedule (seasonal.json) — must be writable by PHP
  api/seasonal-art/                        Uploaded pictures (re-encoded JPEG, generated names);
                                           its .htaccess turns off PHP/CGI and serves only those files

Before you upload: back up
--------------------------
1. In File Manager, download (or copy) your current  /panels/flixtown2027/  folder. This patch only
   adds files, but keep the backup.

Install
-------
1. Upload the CONTENTS of the "upload" folder in this ZIP into  /panels/flixtown2027/  so that
   admin/seasonal.php lands next to your existing admin/index.php and api/seasonal.php next to your
   existing api/config.php. Do not replace private/config.php or any existing file (there are none
   with the same names).
2. Permissions: folders 755, files 644. api/flixtown-seasonal/data and api/seasonal-art must be
   writable by PHP (755 is enough when PHP runs as your account; 775 if not). Never 777.
3. Check api/flixtown-seasonal/settings.php: 'public_api_base' must be
       https://panelsandapps.com/panels/flixtown2027/api/
   (the app only shows pictures from the same https host as the panel).
4. nginx only (Apache/LiteSpeed use the included .htaccess files):
       location ^~ /panels/flixtown2027/api/flixtown-seasonal/ { deny all; }
       location ~ ^/panels/flixtown2027/api/seasonal-art/.*\.php$ { deny all; }
5. Requirements: PHP 7.4+ with GD (JPEG/PNG; WebP if your GD has it) and fileinfo.

Use
---
1. Sign in at https://panelsandapps.com/panels/flixtown2027/admin/ as usual, then open
       https://panelsandapps.com/panels/flixtown2027/admin/seasonal.php
   (signed out, it sends you to the normal login page; it never shows anything without it).
2. Under Pictures, upload the approved 16:9 designs (1920×1080 recommended; 1280×720 to 3840×2160;
   JPEG/PNG/WebP; 8 MB at most). The approved designs already contain the logo; the app shows them
   full screen with nothing on top.
     Everyday design      → row "Everyday design" (normal days, holidays without a picture, seasonal off)
     New Year             → New Year            Valentine's Day → Valentine's Day
     St. Patrick's Day    → St. Patrick's Day   Easter          → Easter
     Memorial Day         → Memorial Day        Independence Day (fireworks) → Independence Day
     Halloween            → Halloween           Thanksgiving    → Thanksgiving
     Christmas            → Christmas
3. Adjust dates/priority if needed, then Save schedule. Status shows today's selected theme.
4. "Restore normal splash now" turns the holiday pictures off at once: from their next launch after
   checking, TVs show the everyday design (or the built-in Flix Town background if none is uploaded).
5. Add future events (for example Labor Day) without an app update: Add an event, set its dates,
   upload a picture, turn it on.

How TVs use it
--------------
Each TV checks api/seasonal.php in the background at most every 30 minutes and uses the result from
its next launch. It never waits for the panel: offline or before this patch is installed it uses its
built-in schedule with the normal background. Start and end dates are inclusive and use the TV's
own date. Highest priority wins; then the shorter date range; then the event id.

Rollback
--------
Delete these files and folders (or restore your backup):
  admin/seasonal.php, api/seasonal.php, api/flixtown-seasonal/, api/seasonal-art/
TVs then get 404 from api/seasonal.php and keep the last schedule they saved; with the seasonal
splash turned off first ("Restore normal splash now"), they show the normal splash.
