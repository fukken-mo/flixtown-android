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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Trending Now" = this week's TMDB trending movies and TV shows (panel trending.php) that exist in
 * this Xtream catalog, in TMDB's order. Nothing is guessed from the provider's own data: with no
 * TMDB list (old panel, no TMDB key, offline for two days) or fewer than three titles on the
 * server, Home simply has no Trending row.
 *
 * Matching: the catalog's TMDB ID first (when its title or year agrees), otherwise the same
 * normalised title (TitleMatch) and type with the year within one; a catalog title without a year
 * matches only if it is the only one with that name. Each catalog title is used once.
 */
final class Trending {
    private Trending(){}
    static final int LIMIT=10,MIN_ROW=3;
    private static final long FRESH_MS=6*3600_000L,STALE_MS=48*3600_000L;

    static final class Entry{
        final String media,id,title,original;final int year;final double rating;
        Entry(String m,String i,String t,String o,int y,double r){media=m;id=i;title=t;original=o;year=y;rating=r;}
        String kind(){return "tv".equals(media)?"series":"movie";}
    }
    static final class Match{final Catalog.Item item;final Entry entry;Match(Catalog.Item i,Entry e){item=i;entry=e;}}

    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static volatile List<Entry> entries=Collections.emptyList();
    private static volatile long fetchedAt;
    private static volatile List<Catalog.Item> matched=Collections.emptyList();
    private static volatile int matchedFor;

    /** Trending titles on this server for the given catalog (empty until matched). */
    static List<Catalog.Item> current(List<Catalog.Item> movies,List<Catalog.Item> series){
        return matchedFor==identity(movies,series)?matched:Collections.<Catalog.Item>emptyList();
    }
    static int identity(List<Catalog.Item> movies,List<Catalog.Item> series){
        int h=movies.size()*31+series.size();
        for(int i=0;i<Math.min(50,movies.size());i++)h=h*31+movies.get(i).id.hashCode();
        for(int i=0;i<Math.min(50,series.size());i++)h=h*31+series.get(i).id.hashCode();
        return h;
    }

    static List<Entry> parse(JSONObject json){
        List<Entry> out=new ArrayList<>();JSONArray items=json.optJSONArray("items");if(items==null)return out;
        for(int i=0;i<items.length();i++){JSONObject o=items.optJSONObject(i);if(o==null)continue;
            String media=o.optString("media");if(!"movie".equals(media) && !"tv".equals(media))continue;
            out.add(new Entry(media,o.optString("id"),o.optString("title"),o.optString("original"),o.optInt("year",0),
                o.isNull("rating")?-1:o.optDouble("rating",-1)));}
        return out;
    }

    /** Pure matching, in TMDB order, at most {@code limit} titles. */
    static List<Match> match(List<Entry> trending,List<Catalog.Item> movies,List<Catalog.Item> series,int limit){
        Map<String,List<Catalog.Item>> byId=new HashMap<>(),byName=new HashMap<>();
        for(List<Catalog.Item> list:java.util.Arrays.asList(movies,series))for(Catalog.Item item:list){
            if(!item.tmdbId.isEmpty())add(byId,item.kind+":"+item.tmdbId,item);
            String name=TitleMatch.normalize(item.title);if(!name.isEmpty())add(byName,item.kind+":"+name,item);
        }
        List<Match> out=new ArrayList<>();Set<String> used=new HashSet<>();
        for(Entry e:trending){
            if(out.size()>=limit)break;
            String kind=e.kind(),title=TitleMatch.normalize(e.title),original=TitleMatch.normalize(e.original);
            Catalog.Item pick=null;
            List<Catalog.Item> ids=byId.get(kind+":"+e.id);
            if(ids!=null){List<Catalog.Item> ok=new ArrayList<>();
                for(Catalog.Item i:ids){String n=TitleMatch.normalize(i.title);
                    boolean sameName=n.equals(title)||(!original.isEmpty() && n.equals(original));
                    boolean sameYear=i.year>0 && e.year>0 && i.year==e.year;
                    if(!used.contains(i.key()) && (sameName||sameYear))ok.add(i);}
                pick=best(ok,e.year);}
            if(pick==null){
                List<Catalog.Item> named=new ArrayList<>();
                if(!title.isEmpty() && byName.containsKey(kind+":"+title))named.addAll(byName.get(kind+":"+title));
                if(!original.isEmpty() && !original.equals(title) && byName.containsKey(kind+":"+original))named.addAll(byName.get(kind+":"+original));
                List<Catalog.Item> dated=new ArrayList<>(),undated=new ArrayList<>();
                for(Catalog.Item i:named){if(used.contains(i.key()))continue;
                    if(i.year>0 && e.year>0){if(Math.abs(i.year-e.year)<=1)dated.add(i);}
                    else undated.add(i);}
                // Several same-named titles without a year could be different films: never guess.
                if(dated.isEmpty() && withoutUhdCopies(undated).size()==1 && namedVersions(named)==1)dated=undated;
                pick=best(dated,e.year);
            }
            if(pick!=null){used.add(pick.key());out.add(new Match(pick,e));}
        }
        return out;
    }
    private static void add(Map<String,List<Catalog.Item>> map,String key,Catalog.Item item){
        List<Catalog.Item> l=map.get(key);if(l==null){l=new ArrayList<>();map.put(key,l);}l.add(item);}
    /** Distinct years among same-named catalog titles (an unknown year counts once). */
    private static int namedVersions(List<Catalog.Item> named){Set<Integer> years=new HashSet<>();for(Catalog.Item i:named)years.add(i.year);return years.size();}
    private static List<Catalog.Item> withoutUhdCopies(List<Catalog.Item> list){
        List<Catalog.Item> plain=new ArrayList<>();for(Catalog.Item i:list)if(!i.isUhd())plain.add(i);
        return plain.isEmpty()?list:plain;}
    /** Prefer the regular (not 4K) copy, then the exact year, then the newest addition. */
    private static Catalog.Item best(List<Catalog.Item> list,int year){
        Catalog.Item best=null;
        for(Catalog.Item i:list){
            if(best==null){best=i;continue;}
            int a=(i.isUhd()?0:4)+(i.year==year?2:0),b=(best.isUhd()?0:4)+(best.year==year?2:0);
            if(a>b || a==b && i.added>best.added)best=i;
        }
        return best;
    }

    /**
     * Loads the saved list (fresh for 6 hours, usable for 48), fetches a new one when needed, then
     * matches it to the catalog off the main thread. {@code changed} runs on the main thread when
     * the Trending titles differ from before.
     */
    static void refresh(Context context,List<Catalog.Item> movies,List<Catalog.Item> series,Runnable changed){
        Context c=context.getApplicationContext();
        List<Catalog.Item> m=new ArrayList<>(movies),s=new ArrayList<>(series);
        WORKER.execute(()->{
            File file=new File(c.getFilesDir(),"trending.json");
            if(entries.isEmpty())readFile(file);
            if(System.currentTimeMillis()-fetchedAt>FRESH_MS){
                try{String body=Api.request(BuildConfig.PANEL_URL+"trending.php",null);
                    JSONObject json=new JSONObject(body);List<Entry> list=parse(json);
                    if(!list.isEmpty() && !json.optBoolean("stale")){entries=list;fetchedAt=System.currentTimeMillis();writeFile(file,body);}
                    else if(json.has("unavailable")){entries=Collections.emptyList();fetchedAt=System.currentTimeMillis();}
                }catch(Exception ignored){/* keep the saved list while it is usable */}
            }
            List<Entry> list=System.currentTimeMillis()-fetchedAt<STALE_MS?entries:Collections.<Entry>emptyList();
            List<Match> found=match(list,m,s,LIMIT);
            List<Catalog.Item> items=new ArrayList<>();
            for(Match x:found){items.add(x.item);Ratings.seed(x.item,x.entry.rating);}
            if(!found.isEmpty())Ratings.persist();
            if(items.size()<MIN_ROW)items=Collections.emptyList();
            boolean differs=!keys(items).equals(keys(matched)) || matchedFor!=identity(m,s);
            matched=items;matchedFor=identity(m,s);
            if(differs)new Handler(Looper.getMainLooper()).post(changed);
        });
    }
    private static List<String> keys(List<Catalog.Item> items){List<String> k=new ArrayList<>();for(Catalog.Item i:items)k.add(i.key());return k;}
    private static void readFile(File file){
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            JSONObject json=new JSONObject(bytes.toString("UTF-8"));
            entries=parse(json);fetchedAt=json.optLong("saved_at",0);
        }catch(Exception ignored){}
    }
    private static void writeFile(File file,String body){
        try{JSONObject json=new JSONObject(body).put("saved_at",System.currentTimeMillis());
            File temp=new File(file.getPath()+".tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));}
            if(!temp.renameTo(file))temp.delete();
        }catch(Exception ignored){}
    }
}
