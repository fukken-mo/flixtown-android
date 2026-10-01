<?php
/**
 * Flix Town app updates — panel settings.
 *
 * Edit admin_password before uploading. The update pages stay locked until it is changed.
 */
return array(
    // Password for app-update-admin.php. Use something long; it is compared exactly.
    'admin_password' => 'CHANGE-ME',

    // The package ID the main APK must have. The panel warns if the APK at the main URL is a
    // different app, and the API only ever offers an APK to the app with the same package ID.
    'main_package' => 'com.myflixtown.tv.native',

    // How often the API re-checks the APK URL for a replaced file (HEAD request; the APK is only
    // downloaded again when the server reports a change, or when it sends no ETag/Last-Modified).
    'recheck_minutes' => 10,

    // Largest APK the panel will download and inspect.
    'max_apk_mb' => 200,

    // Add a unique query parameter when downloading, so CDNs and proxies cannot return an older
    // copy. If the server rejects it (for example a signed link), the download is retried without it.
    'cache_bust' => true,
);
