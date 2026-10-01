<?php
/**
 * Optional: lets config.php keep serving its old update fields (update_version_code,
 * update_apk_url, update_notes, update_required) from the detected APK, for app builds that
 * read updates from config.php. See README-UPDATES.txt, step 5.
 */
require_once __DIR__ . '/UpdateStore.php';

function flixtown_update_legacy_fields()
{
    try {
        $store = new FlixUpdateStore(require __DIR__ . '/settings.php');
        $r = $store->apiResponse($store->setting('main_package'));
        if (empty($r['ok']) || empty($r['update_version_code'])) return array();
        return array('update_version_code' => $r['update_version_code'], 'update_apk_url' => $r['update_apk_url'],
            'update_notes' => $r['update_notes'], 'update_required' => $r['update_required']);
    } catch (Exception $e) {
        return array();
    }
}
