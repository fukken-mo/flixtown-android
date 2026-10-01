package com.flixtown.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Account and catalog refresh.
 *
 * It runs on a fresh launch (from the launcher activity, before any intro video, so new titles are
 * fetched while the intro plays), when the app comes back from the background, and from Settings.
 * Screens attach as listeners and get the result on the main thread, even if it finished before
 * they attached. Only one refresh runs at a time, and an automatic one is skipped if the last
 * successful refresh was under a minute ago. Switching screens or returning from playback never
 * starts one.
 */
final class StartupRefresh {
    static final int DONE=1,FAILED=2,EXPIRED=3;
    interface Listener {
        void onConfig(JSONObject config);
        void onResult(Result result);
    }
    static final class Result {
        final int state;final boolean manual;final String error;
        final List<Catalog.Item> movies,series;final List<Catalog.Category> movieCategories,seriesCategories;
        Result(int state,boolean manual,String error,List<Catalog.Item> m,List<Catalog.Item> s,List<Catalog.Category> mc,List<Catalog.Category> sc){
            this.state=state;this.manual=manual;this.error=error;movies=m;series=s;movieCategories=mc;seriesCategories=sc;}
    }

    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService FETCH=Executors.newFixedThreadPool(4);
    private static final List<Listener> listeners=new ArrayList<>();
    private static JSONObject config;
    private static Result result;
    private static boolean running,started,watching,refreshAfterPlayer;
    private static int generation;
    /** Activities currently started; ones started before watching began are never counted or removed. */
    private static final java.util.Set<Integer> visible=new java.util.HashSet<>();
    private static long lastDoneAt,backgroundSince;
    private static final long MIN_INTERVAL_MS=60_000;
    private static final String TAG="FlixTown";

    static boolean startedThisProcess(){return started;}
    static boolean running(){return running;}

    /** Starts a refresh unless one is already running. Call on the main thread. */
    static void start(Context context,boolean manual){
        if(running)return;
        // A result this recent is still delivered to new listeners; no second download is needed.
        if(!manual && result!=null && result.state==DONE && SystemClock.elapsedRealtime()-lastDoneAt<MIN_INTERVAL_MS){
            Log.i(TAG,"Refresh skipped: the last one finished under a minute ago");return;}
        Log.i(TAG,"Refresh started"+(manual?" (from Settings)":""));
        Context app=context.getApplicationContext();
        running=true;started=true;result=null;if(!manual)config=null;
        final int token=++generation;
        Api.IO.execute(()->run(app,manual,token));
    }
    static void listen(Listener listener){
        if(!listeners.contains(listener))listeners.add(listener);
        if(config!=null)listener.onConfig(config);
        if(result!=null)listener.onResult(result);
    }
    static void unlisten(Listener listener){listeners.remove(listener);}

    /** After signing out: forget everything so the next account starts with a fresh refresh. */
    static void reset(){
        generation++;running=false;started=false;result=null;config=null;lastDoneAt=0;refreshAfterPlayer=false;
    }

    /**
     * Refreshes when the app returns from the background (Home button, screensaver, another app).
     * Returning from a Flix Town screen is not a return from the background, so it does not count.
     * If the app comes back straight into the player, the refresh waits until playback is left, so
     * it never competes with the video for bandwidth.
     */
    static void watchForeground(Application app){
        if(watching)return;watching=true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityStarted(Activity a){
                visible.add(System.identityHashCode(a));
                boolean returning=visible.size()==1 && backgroundSince>0;
                if(returning){backgroundSince=0;AppUpdates.onAppForeground();}   // Home checks for an app update when next shown
                if(!(returning || refreshAfterPlayer))return;
                if(a instanceof LoginActivity || a instanceof RenewalActivity || AccountStore.read(a)==null)return;
                if(a instanceof PlayerActivity){refreshAfterPlayer=true;Log.i(TAG,"Back from background in the player: refresh waits");return;}
                Log.i(TAG,"Back from background");
                refreshAfterPlayer=false;start(a,false);
            }
            @Override public void onActivityStopped(Activity a){
                if(visible.remove(System.identityHashCode(a)) && visible.isEmpty())backgroundSince=SystemClock.elapsedRealtime();
            }
            @Override public void onActivityCreated(Activity a,Bundle b){}
            @Override public void onActivityResumed(Activity a){}
            @Override public void onActivityPaused(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
            @Override public void onActivityDestroyed(Activity a){}
        });
    }
    static JSONObject config(){return config;}

    private static void run(Context c,boolean manual,int token){
        try{
            JSONObject cfg=Api.get(BuildConfig.PANEL_URL+"config.php");
            boolean introOn=cfg.optBoolean("intro_enabled");
            Api.prefs(c).edit().putString("intro_url",introOn?cfg.optString("intro_url",""):"").apply();
            MAIN.post(()->{if(token!=generation)return;config=cfg;for(Listener l:new ArrayList<>(listeners))l.onConfig(cfg);});

            JSONObject account=Api.get(Api.accountUrl(c));JSONObject user=account.optJSONObject("user_info");
            if(user!=null){
                Api.prefs(c).edit().putString("account_status",user.optString("status",""))
                    .putString("account_exp_date",user.optString("exp_date","")).apply();
                if(!"Active".equalsIgnoreCase(user.optString("status",""))){
                    Api.prefs(c).edit().putBoolean("expired",true).apply();
                    finish(token,new Result(EXPIRED,manual,null,null,null,null,null));return;
                }
            }
            // The four catalog calls are independent; fetching them together roughly halves startup time.
            Future<String> m=FETCH.submit(()->Api.request(Api.xtream(c,"get_vod_streams",""),null));
            Future<String> s=FETCH.submit(()->Api.request(Api.xtream(c,"get_series",""),null));
            Future<String> mc=FETCH.submit(()->Api.request(Api.xtream(c,"get_vod_categories",""),null));
            Future<String> sc=FETCH.submit(()->Api.request(Api.xtream(c,"get_series_categories",""),null));
            String ms=m.get(),ss=s.get(),mcs=mc.get(),scs=sc.get();
            List<Catalog.Item> movies=Catalog.parse(ms,"movie"),series=Catalog.parse(ss,"series");
            if(movies.isEmpty() && series.isEmpty()){
                finish(token,new Result(FAILED,manual,"The server returned an empty catalog.",null,null,null,null));return;
            }
            Api.cache(c,"movies",ms);Api.cache(c,"series",ss);
            Api.cache(c,"movie_categories",mcs);Api.cache(c,"series_categories",scs);
            Api.prefs(c).edit().putLong(SettingsPage.LAST_REFRESH,System.currentTimeMillis()).apply();
            finish(token,new Result(DONE,manual,null,movies,series,Catalog.parseCategories(mcs),Catalog.parseCategories(scs)));
        }catch(Exception e){
            android.util.Log.w("FlixTown","Startup refresh failed; cached titles remain",e);
            finish(token,new Result(FAILED,manual,e.getMessage(),null,null,null,null));
        }
    }
    private static void finish(int token,Result r){
        MAIN.post(()->{if(token!=generation)return;running=false;result=r;
            if(r.state==DONE)lastDoneAt=SystemClock.elapsedRealtime();
            Log.i(TAG,"Refresh finished: "+(r.state==DONE?"updated":r.state==EXPIRED?"account expired":"failed"));
            for(Listener l:new ArrayList<>(listeners))l.onResult(r);});
    }
}
