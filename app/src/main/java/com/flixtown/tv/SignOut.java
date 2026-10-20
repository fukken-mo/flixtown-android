package com.flixtown.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

/**
 * Signs this TV out (Settings, or "Use a different account" on the renewal screen): the saved
 * credentials, the account status, the account's catalog and its service sessions are removed.
 * Playback settings, watch progress and My List stay on the TV, so signing back in to the same
 * account keeps them. When a different account signs in, the previous account's My List, watch
 * progress and Continue Watching are removed first (see {@link #beforeSignIn}).
 *
 * Migration from versions without an owner record: a TV that is still signed in records its
 * account as the owner when the app next starts or refreshes ({@link #rememberOwner}), so that
 * customer keeps everything. Data found with no owner at sign-in (the TV was signed out before
 * updating) cannot be attributed to anyone and is removed.
 */
final class SignOut {
    private SignOut(){}
    /** A one-way fingerprint of the account whose personal data is on this TV (never the username itself). */
    static final String OWNER="account_owner";

    static void run(Context c){
        AccountStore.clear(c);
        SharedPreferences.Editor e=Api.prefs(c).edit().remove("expired").remove(SettingsPage.LAST_REFRESH);
        for(String key:AccountInfo.KEYS)e.remove(key);
        e.apply();
        File[] files=new File(c.getFilesDir(),"catalog").listFiles();
        if(files!=null)for(File f:files)f.delete();
        Catalog.Store.set(Collections.<Catalog.Item>emptyList(),Collections.<Catalog.Item>emptyList());
        // StartupRefresh is main-thread state; remote sign-in reaches here from its background thread.
        if(Looper.myLooper()==Looper.getMainLooper())StartupRefresh.reset();else new Handler(Looper.getMainLooper()).post(StartupRefresh::reset);
        RenewalApi.forget();RequestsApi.forget();
    }

    /**
     * Call before an account's credentials or details are saved. If the personal data on this TV
     * belongs to a different account, it is removed (and the previous account is signed out
     * completely), so the new account never sees it. Same account: nothing changes.
     */
    static void beforeSignIn(Context c,String username){
        String owner=Api.prefs(c).getString(OWNER,""),mine=ownerId(username);
        // No recorded owner (data left by a version before this check, after its account signed out):
        // ownership cannot be verified, so the data is treated as another customer's and removed.
        if(!owner.equals(mine)){
            run(c);
            SharedPreferences.Editor e=Api.prefs(c).edit();
            for(Map.Entry<String,?> entry:Api.prefs(c).getAll().entrySet())if(isPersonalKey(entry.getKey()))e.remove(entry.getKey());
            e.apply();
        }
        Api.prefs(c).edit().putString(OWNER,mine).apply();
    }
    /** Installs from before this check: records the signed-in account as the owner of the data already here. */
    static void rememberOwner(Context c,String username){
        if(Api.prefs(c).getString(OWNER,"").isEmpty())Api.prefs(c).edit().putString(OWNER,ownerId(username)).apply();
    }
    /** My List, watch progress and Continue Watching: what one customer must not see of another. */
    static boolean isPersonalKey(String key){
        return key.startsWith("watched_") || key.startsWith("request_seen_") || key.startsWith("resume_") || key.equals("continue_ids") || key.equals("favorites");
    }
    static String ownerId(String username){
        try{return UpdateInfo.hex(MessageDigest.getInstance("SHA-256").digest(("flixtown-owner:"+username).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){return "";}
    }
}
