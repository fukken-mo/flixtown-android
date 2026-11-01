package com.flixtown.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.net.URL;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Account-owned metadata and background profile API. No changes to legacy/default history. */
final class Profiles {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Map<SharedPreferences,Map<String,ProfileCatalog>> CACHE=new WeakHashMap<>();
    private static final Set<String> CHOSEN=new HashSet<>();
    private static final Object AUTH_LOCK=new Object();
    private static volatile int epoch;
    private static final java.util.concurrent.ConcurrentHashMap<SharedPreferences,Session> SESSIONS=new java.util.concurrent.ConcurrentHashMap<>();
    static final class Session {final String scope,token;final long until;final int epoch;Session(String scope,String token,long until,int epoch){this.scope=scope;this.token=token;this.until=until;this.epoch=epoch;}}
    static SharedPreferences raw(Context c){return c.getSharedPreferences("flix",Context.MODE_PRIVATE);}
    static SharedPreferences store(Context c){return c.getSharedPreferences("profiles_v1",Context.MODE_PRIVATE);}
    static String scope(Context c){
        SharedPreferences p=raw(c);String owner=p.getString(SignOut.OWNER,"");if(!owner.matches("[a-f0-9]{64}"))return "";
        return digest(owner+"|"+p.getString("server","")+"|"+p.getString("progress_sync_api_url",""));
    }
    private static String digest(String s){try{return UpdateInfo.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static synchronized ProfileCatalog catalog(Context c){
        String scope=scope(c);SharedPreferences file=store(c);Map<String,ProfileCatalog> values=CACHE.get(file);if(values==null){values=new LinkedHashMap<>();CACHE.put(file,values);}ProfileCatalog found=values.get(scope);if(found!=null)return found;
        try{found=ProfileCatalog.decode(store(c).getString(scope+":catalog",""));}catch(Exception ignored){found=ProfileCatalog.initial();}
        if(values.size()>=16)values.remove(values.keySet().iterator().next());values.put(scope,found);return found;
    }
    static String activeId(Context c){return catalog(c).selected;}
    static SharedPreferences prefs(Context c,SharedPreferences raw){
        String id=activeId(c),scope=scope(c);if(scope.isEmpty()||"default".equals(id))return raw;
        return new ProfilePrefs(raw,c.getSharedPreferences("profile_history_"+scope+"_"+id,Context.MODE_PRIVATE));
    }
    static String journalSuffix(Context c){String id=activeId(c);return "default".equals(id)?"":"_"+scope(c)+"_"+id;}
    static synchronized boolean needsChoice(Context c){return !scope(c).isEmpty()&&!CHOSEN.contains(scope(c));}
    static synchronized void chosen(Context c){CHOSEN.add(scope(c));}
    static synchronized void signOut(){epoch++;CHOSEN.clear();SESSIONS.clear();}
    static synchronized void save(Context c,String scope,ProfileCatalog data,long revision){
        Map<String,ProfileCatalog> values=CACHE.get(store(c));if(values==null){values=new LinkedHashMap<>();CACHE.put(store(c),values);}values.put(scope,data);try{store(c).edit().putString(scope+":catalog",data.encode()).putLong(scope+":revision",revision).apply();}catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    static synchronized void select(Context c,String id){
        ProfileCatalog data=catalog(c).select(id);for(ProfileCatalog.Profile p:data.profiles)if(p.id.equals(id)&&p.kids)throw new IllegalArgumentException("Kids mode is not available yet");
        save(c,scope(c),data,store(c).getLong(scope(c)+":revision",0));chosen(c);
    }
    /** A separate endpoint ensures an old account-sync server can never accept non-default uploads. */
    static String endpoint(Context c){
        String source=raw(c).getString("progress_sync_api_url","");
        try{URL u=new URL(source);if(!ReportsApi.safeUrl(source)||!u.getPath().endsWith("/tv-account-sync.php")||u.getQuery()!=null||u.getRef()!=null)return "";
            return source.substring(0,source.length()-"tv-account-sync.php".length())+"tv-profile-sync.php";
        }catch(Exception e){return "";}
    }
    interface Done {void result(boolean ok,String error);}
    static void refresh(Context c,Done done){request(c,null,done);}
    static void mutate(Context c,String kind,ProfileCatalog.Profile profile,String id,Done done){
        try{JSONObject b=new JSONObject().put("mutation",kind).put("revision",store(c).getLong(scope(c)+":revision",0));if(profile!=null)b.put("profile",profile.json());if(id!=null)b.put("id",id);request(c,b,done);}
        catch(Exception e){done.result(false,"Check the profile name and avatar.");}
    }
    private static void request(Context c,JSONObject mutation,Done done){
        final int started=epoch;Context app=c.getApplicationContext();String scope=scope(c),url=endpoint(c);String[] credentials=AccountStore.read(c);
        if(scope.isEmpty()||url.isEmpty()||credentials==null){done.result(false,"Profiles need the updated account-sync service.");return;}
        Api.IO.execute(()->{
            ProfileCatalog imported=null;long revision=0;boolean ok=false;String error="Profiles are temporarily unavailable. Your current profile still works.";
            try{
                JSONObject body=mutation==null?new JSONObject():new JSONObject(mutation.toString());body.put("action","profiles");body.put("session",sessionFor(app,scope,url,credentials,Profiles::post).token);
                JSONObject r=post(url,body);
                if(!r.optBoolean("ok")){String why=r.optString("error");if(why.equals("session"))forgetSession(app);if(why.equals("profile_conflict"))error="Profiles changed on another TV. Refresh and try again.";else if(why.equals("profile_limit"))error="This account already has six profiles.";else if(why.equals("profile_capacity"))error="Profile storage is full. Please contact support.";throw new IllegalStateException();}
                if(r.optInt("profiles_version")!=1||!(r.opt("revision") instanceof Number)||r.optLong("revision")<=0)throw new IllegalStateException();
                JSONArray profiles=r.getJSONArray("profiles");String selected=catalog(app).selected;boolean found=false;
                for(int i=0;i<profiles.length();i++)if(selected.equals(profiles.getJSONObject(i).optString("id")))found=true;
                JSONObject encoded=new JSONObject().put("version",1).put("selected",found?selected:"default").put("profiles",profiles);
                ProfileCatalog data=ProfileCatalog.decode(encoded.toString());for(ProfileCatalog.Profile p:data.profiles)if(p.kids)throw new IllegalStateException();
                imported=data;revision=r.getLong("revision");ok=true;
            }catch(Exception ignored){}finally{Arrays.fill(credentials,"");}
            final boolean success=ok;final String message=error;final ProfileCatalog data=imported;final long rev=revision;
            MAIN.post(()->{if(started!=epoch||!scope.equals(scope(app)))return;
                if(success){
                    // Never change the active profile underneath Details or a running player.
                    if(c instanceof HomeActivity&&!((HomeActivity)c).profileMetadataAllowed()){done.result(false,"Profiles will refresh when you return.");return;}
                    if(c instanceof android.app.Activity){android.app.Activity a=(android.app.Activity)c;
                        if(a.isFinishing()||a.isDestroyed()||!a.hasWindowFocus()){done.result(false,"Profiles will refresh when you return.");return;}}
                    save(app,scope,data,rev);
                }done.result(success,success?"":message);
            });
        });
    }
    /** Shared by default sync, named-profile sync and metadata; never locks the main thread. */
    static Session sessionFor(Context c,String expectedScope,String url,String[] credentials,AccountSync.Transport transport)throws Exception{
        SharedPreferences file=store(c);String scope=scope(c);int started=epoch;
        String owner=SignOut.ownerId(credentials[0]);
        if(!expectedScope.equals(scope)||!owner.equals(raw(c).getString(SignOut.OWNER,"")))throw new IllegalStateException();
        Session found=SESSIONS.get(file);
        if(found!=null&&found.epoch==started&&scope.equals(found.scope)&&android.os.SystemClock.elapsedRealtime()<found.until)return found;
        synchronized(AUTH_LOCK){
            if(started!=epoch||!expectedScope.equals(scope(c))||!owner.equals(raw(c).getString(SignOut.OWNER,"")))throw new IllegalStateException();
            found=SESSIONS.get(file);if(found!=null&&found.epoch==started&&scope.equals(found.scope)&&android.os.SystemClock.elapsedRealtime()<found.until)return found;
            JSONObject r=transport.post(url,new JSONObject().put("action","session").put("username",credentials[0]).put("password",credentials[1]));
            String token=r.optString("session");if(!r.optBoolean("ok")||token.isEmpty()||token.length()>512||started!=epoch||!scope.equals(scope(c)))throw new IllegalStateException();
            long until=android.os.SystemClock.elapsedRealtime()+Math.min(840,Math.max(1,r.optInt("ttl",900)-60))*1000L;
            found=new Session(scope,token,until,started);SESSIONS.put(file,found);return found;
        }
    }
    static void forgetSession(Context c){SESSIONS.remove(store(c));}
    /** Bounded HTTPS transport, also used for profile progress. */
    private static AccountSync.Transport fixtureTransport;
    static void fixture(AccountSync.Transport transport){if(!BuildConfig.DEMO)throw new IllegalStateException();fixtureTransport=transport;}
    static JSONObject post(String url,JSONObject body)throws Exception{
        if(BuildConfig.DEMO&&fixtureTransport!=null)return fixtureTransport.post(url,body);
        return AccountSync.profilePost(url,body);
    }
}
