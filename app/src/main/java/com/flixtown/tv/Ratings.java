package com.flixtown.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TMDB ratings ("★ 8.2", TMDB's vote_average on a 10-point scale), looked up through the panel's
 * ratings.php, which matches titles by TMDB ID or by title + year + type with safeguards.
 *
 * The Xtream "rating" field is never shown: providers fill it inconsistently (often "10").
 * A title without a reliable TMDB rating simply shows no rating.
 *
 * Lookups happen only for cards that are bound on screen (never on focus moves), in the background,
 * at most 10 titles per request with a pause between requests, and answers are kept in
 * files/ratings.json (7 days for a rating, 2 days for "no rating"), so a title is looked up about
 * once a week.
 */
final class Ratings {
    private Ratings(){}
    static final long FOUND_TTL=7L*86400_000,NONE_TTL=2L*86400_000,KEEP_MS=30L*86400_000;
    private static final int BATCH=10,QUEUE_MAX=240;
    private static final long FIRST_DELAY_MS=700,BETWEEN_MS=350,PAUSE_MS=10*60_000L,UNAVAILABLE_PAUSE_MS=30*60_000L;

    /** rating < 0: TMDB has no reliable rating for this title. */
    static final class Entry{final double rating;final long at,releaseAt;final int sig;
        Entry(double r,long a,int s){this(r,a,s,0);}
        Entry(double r,long a,int s,long release){rating=r;at=a;sig=s;releaseAt=release;}
        boolean fresh(long now){return now-at<(rating>0?FOUND_TTL:NONE_TTL);}}

    private static final Map<String,Entry> CACHE=new ConcurrentHashMap<>();
    private static final LinkedHashMap<String,Catalog.Item> QUEUE=new LinkedHashMap<>();
    private static final List<Runnable> LISTENERS=new CopyOnWriteArrayList<>();
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean SCHEDULED=new AtomicBoolean();
    private static volatile File file;private static volatile boolean loaded;
    private static volatile long pausedUntil;private static int failures;
    private static Handler main;private static boolean notifyPosted;

    /** Signature of what was looked up, so a renamed or re-dated title is looked up again. */
    static int sig(Catalog.Item item){return (item.title+"|"+item.year+"|"+item.tmdbId).hashCode();}

    /** TMDB rating, or -1 when none is known. */
    static long releaseDate(Catalog.Item item){
        Entry e=CACHE.get(item.key());return e!=null && e.sig==sig(item)?e.releaseAt:0;
    }
    static double value(Catalog.Item item){
        Entry e=CACHE.get(item.key());
        return e!=null && e.rating>0 && e.sig==sig(item)?e.rating:-1;
    }
    /** "★ 8.2", or "" when there is no reliable rating. */
    static String label(Catalog.Item item){return format(value(item));}
    static String format(double rating){return rating>0 && rating<=10?String.format(Locale.US,"★ %.1f",rating):"";}

    static void init(Context c){
        if(file!=null)return;
        synchronized(Ratings.class){if(file==null)file=new File(c.getApplicationContext().getFilesDir(),"ratings_v2.json");}
    }
    /** Reads the saved ratings; call from a background thread (it is quick and idempotent). */
    static void ensureLoaded(Context c){init(c);load();}
    private static void load(){
        if(loaded || file==null)return;
        synchronized(Ratings.class){if(loaded)return;
            try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
                JSONObject all=new JSONObject(bytes.toString("UTF-8"));long now=System.currentTimeMillis();
                for(Iterator<String> it=all.keys();it.hasNext();){String k=it.next();JSONArray a=all.optJSONArray(k);
                    if(a==null||a.length()<3)continue;long at=a.optLong(1);
                    if(now-at<KEEP_MS)CACHE.put(k,new Entry(a.optDouble(0,-1),at,a.optInt(2),a.optLong(3)));}
            }catch(Exception ignored){}
            loaded=true;
        }
    }

    static void listen(Runnable r){if(!LISTENERS.contains(r))LISTENERS.add(r);}
    static void unlisten(Runnable r){LISTENERS.remove(r);}

    /** Asks for ratings of titles that are on screen. Cheap: known titles are skipped at once. */
    static void want(Catalog.Item item){
        if(item==null || file==null)return;
        Entry e=CACHE.get(item.key());long now=System.currentTimeMillis();
        if(e!=null && e.sig==sig(item) && e.fresh(now))return;
        if(now<pausedUntil)return;
        synchronized(QUEUE){
            QUEUE.remove(item.key());QUEUE.put(item.key(),item);   // most recently shown goes first
            while(QUEUE.size()>QUEUE_MAX){Iterator<String> it=QUEUE.keySet().iterator();it.next();it.remove();}
        }
        if(SCHEDULED.compareAndSet(false,true))WORKER.execute(Ratings::pump);
    }
    static void want(List<Catalog.Item> items,int max){for(int i=0;i<Math.min(max,items.size());i++)want(items.get(i));}

    /** A rating that came with an exact TMDB match (Trending): no separate lookup needed. */
    static void seed(Catalog.Item item,double rating){
        Entry old=CACHE.get(item.key());
        CACHE.put(item.key(),new Entry(rating>0 && rating<=10?rating:-1,old!=null && old.sig==sig(item)?old.at:0,sig(item),old!=null && old.sig==sig(item)?old.releaseAt:0));
    }

    private static void pump(){
        try{
            Thread.sleep(FIRST_DELAY_MS);   // let a burst of card binds collect into full batches
            while(true){
                List<Catalog.Item> batch=new ArrayList<>();
                synchronized(QUEUE){
                    List<String> keys=new ArrayList<>(QUEUE.keySet());
                    for(int i=keys.size()-1;i>=0 && batch.size()<BATCH;i--)batch.add(QUEUE.remove(keys.get(i)));
                }
                if(batch.isEmpty() || System.currentTimeMillis()<pausedUntil)break;
                load();   // titles bound before the saved ratings were read may not need a lookup
                long now=System.currentTimeMillis();
                for(Iterator<Catalog.Item> it=batch.iterator();it.hasNext();){Catalog.Item i=it.next();Entry e=CACHE.get(i.key());
                    if(e!=null && e.sig==sig(i) && e.fresh(now))it.remove();}
                if(batch.isEmpty())continue;
                if(fetch(batch))notifyChanged();
                Thread.sleep(BETWEEN_MS);
            }
        }catch(InterruptedException ignored){
        }finally{
            SCHEDULED.set(false);
            boolean more;synchronized(QUEUE){more=!QUEUE.isEmpty();}
            if(more && System.currentTimeMillis()>=pausedUntil && SCHEDULED.compareAndSet(false,true))WORKER.execute(Ratings::pump);
        }
    }
    /** One panel request. Returns true when something new can be shown. */
    private static boolean fetch(List<Catalog.Item> batch){
        try{
            JSONArray items=new JSONArray();
            for(Catalog.Item i:batch)items.put(new JSONObject().put("key",i.key()).put("kind",i.kind).put("tmdb",i.tmdbId)
                .put("title",i.title).put("year",i.year));
            JSONObject answer=Api.post("ratings",new JSONObject().put("items",items));
            failures=0;
            if(answer.has("unavailable")){pausedUntil=System.currentTimeMillis()+UNAVAILABLE_PAUSE_MS;clearQueue();return false;}
            JSONObject map=answer.optJSONObject("ratings");if(map==null)return false;
            long now=System.currentTimeMillis();boolean changed=false;
            for(Catalog.Item i:batch){
                if(!map.has(i.key()))continue;                       // not checked this time; asked again later
                JSONObject r=map.optJSONObject(i.key());
                double rating=r==null?-1:r.optDouble("rating",-1);
                long release=r==null?0:ReleaseDates.parse(r.optString("release_date",""));
                Entry old=CACHE.put(i.key(),new Entry(rating>0 && rating<=10?rating:-1,now,sig(i),release));
                if(old==null || old.rating!=rating || old.releaseAt!=release)changed=true;
            }
            save();
            return changed;
        }catch(Exception e){
            // An older panel without ratings.php, or no network: stop asking for a while.
            if(++failures>=2){failures=0;pausedUntil=System.currentTimeMillis()+PAUSE_MS;clearQueue();}
            return false;
        }
    }
    private static void clearQueue(){synchronized(QUEUE){QUEUE.clear();}}
    private static synchronized void save(){
        File target=file;if(target==null)return;
        try{
            JSONObject all=new JSONObject();long now=System.currentTimeMillis();
            for(Map.Entry<String,Entry> e:CACHE.entrySet())if(now-e.getValue().at<KEEP_MS)
                all.put(e.getKey(),new JSONArray().put(e.getValue().rating).put(e.getValue().at).put(e.getValue().sig).put(e.getValue().releaseAt));
            File temp=new File(target.getPath()+".tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(all.toString().getBytes(StandardCharsets.UTF_8));}
            if(!temp.renameTo(target))temp.delete();
        }catch(Exception ignored){}
    }
    /** Saves ratings that were seeded from Trending. */
    static void persist(){WORKER.execute(Ratings::save);}

    /** Listeners run once on the main thread for a burst of answers. */
    private static void notifyChanged(){
        synchronized(Ratings.class){
            if(main==null)main=new Handler(Looper.getMainLooper());
            if(notifyPosted)return;notifyPosted=true;
        }
        main.postDelayed(()->{synchronized(Ratings.class){notifyPosted=false;}for(Runnable r:LISTENERS)r.run();},250);
    }
}
