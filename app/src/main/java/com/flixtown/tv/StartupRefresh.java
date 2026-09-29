package com.flixtown.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One catalog refresh per fresh app launch.
 *
 * It starts from the launcher activity, before any intro video, so new titles are fetched while
 * the intro plays. Screens attach as listeners and get the result on the main thread, even if it
 * finished before they attached. Switching screens or returning from playback never starts
 * another full download; only a new launch or "Check for updates" does.
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
    private static boolean running,started;
    private static int generation;

    static boolean startedThisProcess(){return started;}
    static boolean running(){return running;}

    /** Starts a refresh unless one is already running. Call on the main thread. */
    static void start(Context context,boolean manual){
        if(running)return;
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
            finish(token,new Result(DONE,manual,null,movies,series,Catalog.parseCategories(mcs),Catalog.parseCategories(scs)));
        }catch(Exception e){
            android.util.Log.w("FlixTown","Startup refresh failed; cached titles remain",e);
            finish(token,new Result(FAILED,manual,e.getMessage(),null,null,null,null));
        }
    }
    private static void finish(int token,Result r){
        MAIN.post(()->{if(token!=generation)return;running=false;result=r;
            for(Listener l:new ArrayList<>(listeners))l.onResult(r);});
    }
}
