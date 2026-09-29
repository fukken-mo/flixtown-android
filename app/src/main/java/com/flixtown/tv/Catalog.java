package com.flixtown.tv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class Catalog {
    static final class Category {final String id,name;Category(String id,String name){this.id=id;this.name=name;}}
    static List<Category> parseCategories(String json){ArrayList<Category> list=new ArrayList<>();try{
        JSONArray array=new JSONArray(json);for(int i=0;i<array.length();i++){
            JSONObject o=array.optJSONObject(i);if(o!=null)list.add(new Category(o.optString("category_id"),o.optString("category_name","Category")));
        }}catch(Exception ignored){}return list;}

    /** In-memory copy of the catalog for this process, shared by Home, Details and Actor pages. */
    static final class Store {
        static volatile List<Item> movies=java.util.Collections.emptyList(),series=java.util.Collections.emptyList();
        static void set(List<Item> m,List<Item> s){movies=new ArrayList<>(m);series=new ArrayList<>(s);}
        static boolean loaded(){return !movies.isEmpty()||!series.isEmpty();}
    }

    /** Titles from the same category (then best rated of the same kind), never including the title itself. */
    static List<Item> similar(Item self,List<Item> pool,int limit){
        ArrayList<Item> same=new ArrayList<>(),rest=new ArrayList<>();
        for(Item item:pool){if(item.id.equals(self.id))continue;
            if(!self.categoryId.isEmpty() && self.categoryId.equals(item.categoryId))same.add(item);else rest.add(item);}
        java.util.Comparator<Item> order=(a,b)->a.rating!=b.rating?Double.compare(b.rating,a.rating):Integer.compare(b.added,a.added);
        same.sort(order);
        ArrayList<Item> out=new ArrayList<>(same.subList(0,Math.min(limit,same.size())));
        if(out.size()<limit)out.addAll(topRated(rest,limit-out.size()));
        return out;
    }
    static final class Item {
        final String id,title,poster,backdrop,kind,extension,categoryId,overview;
        final int year,added; final double rating;
        Item(JSONObject j,String kind) {
            this.kind=kind; id=j.optString("stream_id",j.optString("series_id",""));
            title=j.optString("name",j.optString("title","Untitled"));
            poster=j.optString("stream_icon",j.optString("cover",""));
            String bg=j.optString("backdrop_path","");
            if (bg.startsWith("[")) { JSONArray a=j.optJSONArray("backdrop_path"); bg=a!=null ? a.optString(0,"") : ""; }
            backdrop=bg.isEmpty()?poster:bg;
            extension=j.optString("container_extension","mp4");
            categoryId=j.optString("category_id","");
            overview=j.optString("plot",j.optString("description",""));
            year=j.optInt("year",0); added=j.optInt("added",j.optInt("last_modified",0));
            double parsed;try{parsed=Double.parseDouble(j.optString("rating","0"));}catch(Exception e){parsed=0;}rating=parsed;
        }
    }
    static List<Item> parse(String json,String kind) {
        ArrayList<Item> items=new ArrayList<>();
        try { JSONArray array=new JSONArray(json); for(int i=0;i<array.length();i++) {
            JSONObject j=array.optJSONObject(i); if(j!=null) { Item item=new Item(j,kind); if(!item.id.isEmpty())items.add(item); }
        }} catch (Exception ignored) {} return items;
    }
    static List<Item> recent(List<Item> original,int limit) {
        java.util.PriorityQueue<Item> queue=new java.util.PriorityQueue<>(Math.max(1,limit),
            (a,b)->Integer.compare(a.added,b.added));
        for(Item item:original){if(queue.size()<limit)queue.add(item);
            else if(item.added>queue.peek().added){queue.poll();queue.add(item);}}
        ArrayList<Item> sorted=new ArrayList<>(queue);sorted.sort((a,b)->Integer.compare(b.added,a.added));return sorted;
    }
    static List<Item> topRated(List<Item> original,int limit) {
        java.util.PriorityQueue<Item> queue=new java.util.PriorityQueue<>(Math.max(1,limit),
            (a,b)->Double.compare(a.rating,b.rating));
        for(Item item:original){if(queue.size()<limit)queue.add(item);
            else if(item.rating>queue.peek().rating){queue.poll();queue.add(item);}}
        ArrayList<Item> sorted=new ArrayList<>(queue);sorted.sort((a,b)->Double.compare(b.rating,a.rating));return sorted;
    }
    static List<Item> alphabetical(List<Item> original) {
        ArrayList<Item> sorted=new ArrayList<>(original);sorted.sort((a,b)->a.title.compareToIgnoreCase(b.title));return sorted;
    }
    static List<Item> continueWatching(Context c,List<Item> movies,List<Item> series) {
        String[] keys=Api.prefs(c).getString("continue_ids","").split(",");
        java.util.Map<String,Integer> wanted=new java.util.HashMap<>();
        for(String key:keys) {
            if(key.isEmpty() || wanted.containsKey(key))continue;
            boolean started=Api.prefs(c).getLong("resume_position_"+key,0)>=15000;
            // A finished episode leaves the next episode queued at 0:00; the show stays in the row.
            boolean upNext=key.startsWith("series:") && !Api.prefs(c).getString("resume_episode_"+key,"").isEmpty();
            if(started || upNext)wanted.put(key,wanted.size());
        }
        Item[] ordered=new Item[wanted.size()];
        if(!wanted.isEmpty()) {
            for(Item item:movies){Integer at=wanted.get("movie:"+item.id);if(at!=null)ordered[at]=item;}
            for(Item item:series){Integer at=wanted.get("series:"+item.id);if(at!=null)ordered[at]=item;}
        }
        List<Item> result=new ArrayList<>();
        for(Item item:ordered)if(item!=null)result.add(item);
        return result;
    }
    static void remember(Context c,Item item) { remember(c,item.kind+":"+item.id); }
    static void saveProgress(Context c,String kind,String id,String episodeId,String episodeExt,
                             String nextId,String nextExt,long position,long duration) {
        if(kind==null || id==null || id.isEmpty() || position<15000)return;
        String key=kind+":"+id;
        if(duration>0 && (position>=duration-30000 || position*100>=duration*95)){
            markFinished(c,kind,id,nextId,nextExt);return;
        }
        android.content.SharedPreferences.Editor edit=Api.prefs(c).edit();
        edit.putLong("resume_position_"+key,position).putLong("resume_duration_"+key,duration);
        if("series".equals(kind)){
            edit.putString("resume_episode_"+key,episodeId==null?"":episodeId);
            edit.putString("resume_extension_"+key,episodeExt==null?"mp4":episodeExt);
            edit.putString("resume_next_"+key,nextId==null?"":nextId);
            edit.putString("resume_next_ext_"+key,nextExt==null?"mp4":nextExt);
        }
        edit.apply();
        remember(c,key);
    }
    /** Movie finished: forget it. Episode finished: queue the next episode so Continue Watching moves on. */
    static void markFinished(Context c,String kind,String id,String nextId,String nextExt) {
        if(!"series".equals(kind) || nextId==null || nextId.isEmpty()){clearProgress(c,kind,id);return;}
        String key=kind+":"+id;
        Api.prefs(c).edit().remove("resume_position_"+key).remove("resume_duration_"+key)
            .putString("resume_episode_"+key,nextId).putString("resume_extension_"+key,nextExt==null||nextExt.isEmpty()?"mp4":nextExt)
            .putString("resume_next_"+key,"").putString("resume_next_ext_"+key,"mp4").apply();
        remember(c,key);
    }
    private static void remember(Context c,String key) {
        String existing=Api.prefs(c).getString("continue_ids","");
        StringBuilder s=new StringBuilder(key);
        for(String other:existing.split(",")) if(!other.isEmpty()&&!other.equals(key)&&s.length()<500) s.append(',').append(other);
        Api.prefs(c).edit().putString("continue_ids",s.toString()).apply();
    }
    static void clearProgress(Context c,String kind,String id) {
        String key=kind+":"+id;
        StringBuilder updated=new StringBuilder();
        for(String other:Api.prefs(c).getString("continue_ids","").split(","))
            if(!other.isEmpty()&&!other.equals(key)){if(updated.length()>0)updated.append(',');updated.append(other);}
        Api.prefs(c).edit().remove("resume_position_"+key).remove("resume_duration_"+key)
            .remove("resume_episode_"+key).remove("resume_extension_"+key)
            .remove("resume_next_"+key).remove("resume_next_ext_"+key)
            .putString("continue_ids",updated.toString()).apply();
    }
}
