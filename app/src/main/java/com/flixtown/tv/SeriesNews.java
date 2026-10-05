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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "NEW SERIES" / "NEW EPISODES" badges for TV shows, only when the data proves it.
 *
 * Xtream's series list has no "added" time, and its last_modified is often touched for every
 * series at once, so neither the list nor the premiere year is used as proof. Evidence, in order:
 *  1. Episode "added" times from get_series_info (the server's own upload times):
 *     - newest episode within 14 days and at least a day newer than the first one → NEW EPISODES;
 *     - every episode within 14 days, and the series was not already listed before that → NEW SERIES.
 *  2. Episode IDs remembered from an earlier check (for servers without episode times): IDs that
 *     appeared since, while last_modified moved forward → NEW EPISODES. A first check only records.
 *  3. A series ID that was not in the previous catalog, when that catalog was at most 14 days old
 *     → NEW SERIES. The first catalog after install, a reinstall, or a jump of many new IDs at once
 *     (a partial catalog followed by a full one) only records a baseline.
 * Otherwise there is no badge. NEW EPISODES wins when both apply.
 *
 * Work is bounded: series info is fetched only for series whose last_modified changed within the
 * last 30 days (newest first) or that newly appeared, at most 12 per catalog refresh, one at a time
 * after start-up has settled. Results are kept in files/series_news.json.
 */
final class SeriesNews {
    static final long DAY=86400,WINDOW=14*DAY,CANDIDATE_AGE=30*DAY;
    static final int PER_REFRESH=12,PER_PROCESS=36,MAX_CHECKED=800;
    static final int NONE=0,NEW_SERIES=1,NEW_EPISODES=2;

    static final class Badge{final int type;final long at;Badge(int t,long a){type=t;at=a;}
        String label(){return type==NEW_EPISODES?"NEW EPISODES":type==NEW_SERIES?"NEW SERIES":null;}}
    static final Badge NO_BADGE=new Badge(NONE,0);

    /** One get_series_info result. min/max: oldest/newest episode "added" (0 when the server has none). */
    static final class Check{final long lm,at,min,max,det;final Set<String> ids;
        Check(long lm,long at,long min,long max,long det,Set<String> ids){this.lm=lm;this.at=at;this.min=min;this.max=max;this.det=det;this.ids=ids;}}
    static final class Episode{final String id;final long added;Episode(String i,long a){id=i;added=a;}}

    /** Everything that is remembered between launches. Pure logic: unit-tested on the JVM. */
    static final class State{
        long baselineAt,lastCatalogAt;
        /** Series ID → when first seen; positive = proven new at that time, negative = seen without proof. */
        final Map<String,Long> seen=new HashMap<>();
        final Map<String,Check> checked=new HashMap<>();

        /** A catalog from the server (not the saved copy). */
        void onCatalog(List<Catalog.Item> series,long now){
            if(series.isEmpty())return;
            if(baselineAt==0){baselineAt=now;for(Catalog.Item i:series)seen.put(i.id,-now);lastCatalogAt=now;return;}
            List<String> fresh=new ArrayList<>();for(Catalog.Item i:series)if(!seen.containsKey(i.id))fresh.add(i.id);
            boolean recent=now-lastCatalogAt<=WINDOW;
            boolean bulk=fresh.size()>Math.max(20,seen.size()/10);   // a partial catalog before, not new shows
            for(String id:fresh)seen.put(id,recent && !bulk?now:-now);
            lastCatalogAt=now;
        }
        /** Series worth a get_series_info call now, newest last_modified first. */
        List<Catalog.Item> candidates(List<Catalog.Item> series,long now,int max){
            List<Catalog.Item> out=new ArrayList<>();
            for(Catalog.Item i:series){Check c=checked.get(i.id);Long s=seen.get(i.id);
                boolean touched=i.lastModified>0 && now-i.lastModified<=CANDIDATE_AGE && (c==null || c.lm!=i.lastModified);
                boolean appeared=s!=null && s>0 && now-s<=WINDOW && c==null;
                if(touched||appeared)out.add(i);}
            Collections.sort(out,(a,b)->Integer.compare(b.lastModified,a.lastModified));
            return out.size()>max?new ArrayList<>(out.subList(0,max)):out;
        }
        void record(String id,long lm,List<Episode> episodes,long now){
            long min=0,max=0;Set<String> ids=new HashSet<>();int timed=0;
            for(Episode e:episodes){ids.add(e.id);if(e.added>0 && e.added<=now+DAY){timed++;min=min==0?e.added:Math.min(min,e.added);max=Math.max(max,e.added);}}
            if(timed*2<episodes.size()){min=0;max=0;}   // too few times to say anything from them
            Check prev=checked.get(id);long det=prev!=null?prev.det:0;
            if(prev!=null && !prev.ids.isEmpty()){
                Set<String> added=new HashSet<>(ids);added.removeAll(prev.ids);
                // Every ID replaced means the provider re-imported the show, not that it has new episodes.
                if(!added.isEmpty() && added.size()<ids.size()){
                    if(lm>0 && lm>prev.lm)det=Math.min(now,lm);
                    else if(now-prev.at<=WINDOW)det=now;
                }
            }
            checked.put(id,new Check(lm,now,min,max,det,ids));
            if(checked.size()>MAX_CHECKED){String oldest=null;long at=Long.MAX_VALUE;
                for(Map.Entry<String,Check> e:checked.entrySet())if(e.getValue().at<at){at=e.getValue().at;oldest=e.getKey();}
                checked.remove(oldest);}
        }
        Badge badge(String id,long now){
            Long s=seen.get(id);long since=s==null?0:Math.abs(s);boolean proven=s!=null && s>0;
            Check c=checked.get(id);
            if(c!=null && c.min>0){
                boolean listedBefore=since>0 && since<c.min-DAY;   // in the catalog before its "first" episode: re-import
                boolean olderShow=now-c.min>WINDOW;
                if(now-c.max<=WINDOW && c.max-c.min>=DAY && (olderShow||!listedBefore))return new Badge(NEW_EPISODES,c.max);
                if(now-c.min<=WINDOW && !listedBefore && !(c.det>0 && now-c.det<=WINDOW))return new Badge(NEW_SERIES,c.min);
            }
            if(c!=null && c.det>0 && now-c.det<=WINDOW)return new Badge(NEW_EPISODES,c.det);
            if(proven && now-s<=WINDOW)return new Badge(NEW_SERIES,s);
            return NO_BADGE;
        }
        JSONObject toJson()throws Exception{
            JSONObject seenJson=new JSONObject();for(Map.Entry<String,Long> e:seen.entrySet())seenJson.put(e.getKey(),e.getValue());
            JSONObject checks=new JSONObject();
            for(Map.Entry<String,Check> e:checked.entrySet()){Check c=e.getValue();JSONArray ids=new JSONArray();for(String i:c.ids)ids.put(i);
                checks.put(e.getKey(),new JSONObject().put("lm",c.lm).put("at",c.at).put("min",c.min).put("max",c.max).put("det",c.det).put("ids",ids));}
            return new JSONObject().put("v",1).put("baselineAt",baselineAt).put("lastCatalogAt",lastCatalogAt).put("seen",seenJson).put("checked",checks);
        }
        static State fromJson(JSONObject j){
            State s=new State();s.baselineAt=j.optLong("baselineAt");s.lastCatalogAt=j.optLong("lastCatalogAt");
            JSONObject seen=j.optJSONObject("seen");
            if(seen!=null)for(Iterator<String> it=seen.keys();it.hasNext();){String k=it.next();s.seen.put(k,seen.optLong(k));}
            JSONObject checks=j.optJSONObject("checked");
            if(checks!=null)for(Iterator<String> it=checks.keys();it.hasNext();){String k=it.next();JSONObject c=checks.optJSONObject(k);if(c==null)continue;
                Set<String> ids=new HashSet<>();JSONArray a=c.optJSONArray("ids");if(a!=null)for(int i=0;i<a.length();i++)ids.add(a.optString(i));
                s.checked.put(k,new Check(c.optLong("lm"),c.optLong("at"),c.optLong("min"),c.optLong("max"),c.optLong("det"),ids));}
            return s;
        }
    }

    /** Episodes from a get_series_info answer ("episodes" is a season map, or a list on some servers). */
    static List<Episode> episodes(JSONObject info){
        List<Episode> out=new ArrayList<>();Object raw=info.opt("episodes");List<JSONArray> seasons=new ArrayList<>();
        if(raw instanceof JSONObject){JSONObject map=(JSONObject)raw;for(Iterator<String> it=map.keys();it.hasNext();){JSONArray a=map.optJSONArray(it.next());if(a!=null)seasons.add(a);}}
        else if(raw instanceof JSONArray){JSONArray a=(JSONArray)raw;for(int i=0;i<a.length();i++){Object x=a.opt(i);
            if(x instanceof JSONArray)seasons.add((JSONArray)x);else if(x instanceof JSONObject){JSONArray one=new JSONArray();one.put(x);seasons.add(one);}}}
        for(JSONArray season:seasons)for(int i=0;i<season.length();i++){JSONObject e=season.optJSONObject(i);if(e==null)continue;
            String id=e.optString("id","");if(id.isEmpty())continue;
            long added;try{added=Long.parseLong(e.optString("added","0").trim());}catch(Exception x){added=0;}
            out.add(new Episode(id,added));}
        return out;
    }

    /* ---------------- App side: saved state, bounded background checks ---------------- */

    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static State state=new State();private static boolean loaded;private static File file;
    private static volatile Map<String,Badge> badges=Collections.emptyMap();
    private static int checksThisProcess;
    private static final long START_DELAY_MS=6000,GAP_MS=900;

    static long now(){return System.currentTimeMillis()/1000;}
    /** The badge for a series card, from the last evaluation. */
    static Badge badge(Catalog.Item item){
        if(!"series".equals(item.kind))return NO_BADGE;
        return item.isNew()?new Badge(NEW_SERIES,item.releaseDate()):NO_BADGE;}

    /** Reads the saved state and evaluates badges for this catalog. Background thread. */
    static synchronized void ensureLoaded(Context c,List<Catalog.Item> series){
        if(!loaded){file=new File(c.getApplicationContext().getFilesDir(),"series_news.json");
            try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
                state=State.fromJson(new JSONObject(bytes.toString("UTF-8")));
            }catch(Exception ignored){state=new State();}
            loaded=true;}
        evaluate(series);
    }
    private static synchronized boolean evaluate(List<Catalog.Item> series){
        Map<String,Badge> next=new HashMap<>();long now=now();
        for(Catalog.Item i:series){Badge b=state.badge(i.id,now);if(b.type!=NONE)next.put(i.id,b);}
        boolean changed=!sameBadges(next,badges);badges=next;return changed;
    }
    private static boolean sameBadges(Map<String,Badge> a,Map<String,Badge> b){
        if(a.size()!=b.size())return false;
        for(Map.Entry<String,Badge> e:a.entrySet()){Badge o=b.get(e.getKey());if(o==null||o.type!=e.getValue().type)return false;}
        return true;
    }
    private static synchronized void save(){
        if(file==null)return;
        try{File temp=new File(file.getPath()+".tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(state.toJson().toString().getBytes(StandardCharsets.UTF_8));}
            if(!temp.renameTo(file))temp.delete();
        }catch(Exception ignored){}
    }

    /**
     * A catalog just came from the server: record new series IDs, then (after start-up settles)
     * look at the episodes of a few changed series. {@code changed} runs on the main thread
     * whenever the set of badges changes.
     */
    static void onServerCatalog(Context context,List<Catalog.Item> series,Runnable changed){
        // Upload timestamps and imported episode IDs are not release dates.
        // Premiere dates arrive in the catalog or the existing cached TMDB ratings request.
    }

    /** "Latest TV Shows": series with a badge first (latest event first), then the rest by last change. */
    static List<Catalog.Item> latest(List<Catalog.Item> series,int limit){
        List<Catalog.Item> flagged=new ArrayList<>();
        for(Catalog.Item i:series)if(badge(i).type!=NONE)flagged.add(i);
        Collections.sort(flagged,(a,b)->Long.compare(badge(b).at,badge(a).at));
        List<Catalog.Item> out=new ArrayList<>(flagged.subList(0,Math.min(limit,flagged.size())));
        if(out.size()<limit){Set<String> have=new HashSet<>();for(Catalog.Item i:out)have.add(i.id);
            for(Catalog.Item i:Catalog.recent(series,limit+out.size()))if(out.size()<limit && !have.contains(i.id))out.add(i);}
        return out;
    }
    static void setForTest(State s,List<Catalog.Item> series){synchronized(SeriesNews.class){state=s;loaded=true;}evaluate(series);}
}
