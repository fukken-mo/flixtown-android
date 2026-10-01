FLIX TOWN — APP UPDATES FROM THE PANEL
=======================================

What this adds
--------------
* app-update-admin.php  Panel page: enter the APK link once, "Save & detect", "Detect / Refresh APK".
* app-update.php        The update API the Flix Town app (3.4.0 and later) asks on startup and from
                        Settings > Check for app updates.
* flixtown-update/      Library, settings and stored results (not reachable from the web).

The panel downloads the APK at your link and reads the package ID, versionCode and versionName
from the AndroidManifest inside it (never from the file name), plus its SHA-256 checksum and
signing certificate. The app only offers an update when that versionCode is higher than the
installed one, downloads it fresh, checks the checksum, package ID and signing key, and then
opens Android's install confirmation.

Nothing in your existing panel is changed or replaced.


1. Set the password (before uploading)
--------------------------------------
Open  api/flixtown-update/settings.php  in a text editor and replace CHANGE-ME:

    'admin_password' => 'a-long-password-only-you-know',

The update page stays locked until this is changed. Leave main_package as
com.myflixtown.tv.native.


2. Upload
---------
Upload the CONTENTS of the "api" folder in this ZIP into the folder on your server that already
contains config.php — the folder behind

    https://panelsandapps.com/panels/flixtown2027/api/

After uploading, that folder contains (next to your existing files):

    app-update.php
    app-update-admin.php
    flixtown-update/            (with ApkInspector.php, UpdateStore.php, settings.php,
                                 legacy.php, .htaccess, data/)

Make sure the folder  flixtown-update/data  is writable by PHP (in most control panels:
permissions 755, or 775 if PHP runs as a different user). The panel stores its results there.

Requirements: PHP 7.2 or newer with the curl or allow_url_fopen setting and zlib (standard on
almost every host). No database changes.

Apache/LiteSpeed: the included .htaccess files keep flixtown-update/ private.
nginx: add this to the site configuration instead:

    location ^~ /panels/flixtown2027/api/flixtown-update/ { deny all; }


3. Publish the APK
------------------
1. Upload your signed APK anywhere it can be downloaded over https, for example
       https://panelsandapps.com/apps/flixtown.apk
2. Open  https://panelsandapps.com/panels/flixtown2027/api/app-update-admin.php
   and sign in with the password from step 1.
3. Under "Main app", paste the link, add release notes if you like, and press "Save & detect".
   The page shows the package ID, versionName, versionCode, SHA-256, size and signing
   certificate it read from the APK. Check any yellow warnings.

The "Test build" section is optional: it is for the side-by-side "Flix Town Preview" test app
(package com.myflixtown.tv.native.preview). Each app is only ever offered the APK that has its own
package ID, so test builds never reach your customers.


4. Every new release (same link, same file name)
------------------------------------------------
1. Build the new APK with a HIGHER versionCode, signed with the SAME key as before.
2. Upload it over the old file at the same link (same name).
3. Press "Detect / Refresh APK" to publish it immediately. If you forget, the API notices the
   replaced file by itself within 10 minutes (it compares the file's ETag / Last-Modified / size
   with a light HEAD request and reads the APK again only when it changed).

The page warns when:
* the versionCode did not increase (TVs would not update),
* the APK is signed with a different key (Android would refuse to install it over the app),
* the APK is for a different package ID,
* the link is not https.

If the APK cannot be downloaded or read, the API reports a failure and TVs show
"Couldn't check for updates" — never "up to date".


5. Optional: keep config.php's old update fields filled in
----------------------------------------------------------
Flix Town 3.4.0 and later only use app-update.php. Older app builds read update_version_code /
update_apk_url / update_notes / update_required from config.php. To fill those automatically from
the detected APK, add these two lines to config.php just before it outputs its JSON (where $config
stands for the array config.php encodes — use that file's own variable name):

    require_once __DIR__ . '/flixtown-update/legacy.php';
    $config = array_merge($config, flixtown_update_legacy_fields());

Older builds do not verify checksums or signing keys, and only install the update if it is signed
with the same key they were.


Checking it works
-----------------
Open in a browser:
    https://panelsandapps.com/panels/flixtown2027/api/app-update.php?package=com.myflixtown.tv.native
It should show "ok": true, the versionCode/versionName read from the APK, the link and its
sha256. On the TV: Settings > Check for app updates.
