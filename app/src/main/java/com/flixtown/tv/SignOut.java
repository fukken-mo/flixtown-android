package com.flixtown.tv;

import android.content.Context;
import java.io.File;
import java.util.Collections;

/**
 * Signs this TV out from Settings: the saved credentials, the account status and the account's
 * catalog are removed. Playback settings, watch progress and My List stay on the TV, the same as
 * "Use a different account" on the renewal screen.
 */
final class SignOut {
    private SignOut(){}
    static void run(Context c){
        AccountStore.clear(c);
        android.content.SharedPreferences.Editor e=Api.prefs(c).edit().remove("expired").remove(SettingsPage.LAST_REFRESH);
        for(String key:AccountInfo.KEYS)e.remove(key);
        e.apply();
        File[] files=new File(c.getFilesDir(),"catalog").listFiles();
        if(files!=null)for(File f:files)f.delete();
        Catalog.Store.set(Collections.<Catalog.Item>emptyList(),Collections.<Catalog.Item>emptyList());
        StartupRefresh.reset();
    }
}
