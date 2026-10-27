package com.flixtown.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Home notices an account has dismissed or acted on (Dismiss, Watch, View requests). Kept in their own
 * file, per account: each entry is named after a one-way fingerprint of the username (never the username
 * itself), so signing out, signing back in to the same account and another account using the TV never
 * remove them, and an account never sees another's. Later is not stored: the notice stays unread.
 *
 * Earlier versions kept one unscoped flag per notice ("notice_seen_<key>") with the TV's other personal
 * data; those belong to the recorded owner ({@link SignOut#OWNER}) and are moved here for that account.
 */
final class NoticeAcks {
    private NoticeAcks(){}
    static final String FILE="notice_acks_v1";
    static final String LEGACY="notice_seen_";
    /** Oldest acknowledgements are forgotten beyond this many (all accounts on the TV together). */
    static final int MAX=1000;

    private static SharedPreferences store(Context c){return c.getApplicationContext().getSharedPreferences(FILE,Context.MODE_PRIVATE);}
    /** Entry name: the account's fingerprint (shortened) and the notice key. */
    static String entry(String owner,String key){return owner.substring(0,Math.min(24,owner.length()))+"|"+key;}
    private static boolean usable(String owner,String key){return owner!=null&&!owner.isEmpty()&&key!=null&&!key.isEmpty();}

    /** Whether this account (an {@link SignOut#ownerId} fingerprint) has acknowledged the notice. */
    static boolean seen(Context c,String owner,String key){
        if(!usable(owner,key))return false;
        if(store(c).contains(entry(owner,key)))return true;
        // Not yet moved from an earlier version: only the recorded owner's flags count.
        return owner.equals(Api.prefs(c).getString(SignOut.OWNER,""))&&Api.prefs(c).getBoolean(LEGACY+key,false);
    }
    static void mark(Context c,String owner,String... keys){
        if(owner==null||owner.isEmpty())return;
        SharedPreferences s=store(c);SharedPreferences.Editor e=s.edit();long now=System.currentTimeMillis();int added=0;
        for(String key:keys)if(usable(owner,key)&&!s.contains(entry(owner,key))){e.putLong(entry(owner,key),now);added++;}
        if(added==0)return;
        e.apply();prune(s);
    }
    /** Moves an earlier version's flags to the recorded owner and removes them from the shared preferences. */
    static void adoptLegacy(Context c){
        SharedPreferences p=Api.prefs(c);String owner=p.getString(SignOut.OWNER,"");
        List<String> keys=new ArrayList<>(),seen=new ArrayList<>();
        for(Map.Entry<String,?> item:p.getAll().entrySet())if(item.getKey().startsWith(LEGACY)){
            keys.add(item.getKey());if(Boolean.TRUE.equals(item.getValue()))seen.add(item.getKey().substring(LEGACY.length()));}
        if(keys.isEmpty())return;
        if(!owner.isEmpty())mark(c,owner,seen.toArray(new String[0]));
        // With no recorded owner the flags cannot be attributed; they are dropped (as the sign-in cleanup did).
        SharedPreferences.Editor e=p.edit();for(String k:keys)e.remove(k);e.apply();
    }
    private static void prune(SharedPreferences s){
        Map<String,?> all=s.getAll();if(all.size()<=MAX)return;
        List<Map.Entry<String,?>> entries=new ArrayList<>(all.entrySet());
        Collections.sort(entries,(a,b)->Long.compare(time(a.getValue()),time(b.getValue())));
        SharedPreferences.Editor e=s.edit();
        for(int i=0;i<entries.size()-MAX;i++)e.remove(entries.get(i).getKey());
        e.apply();
    }
    private static long time(Object v){return v instanceof Long?(Long)v:0;}
}
