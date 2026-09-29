package com.flixtown.tv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class Catalog {
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
        List<Item> result=new ArrayList<>();
        String saved=Api.prefs(c).getString("continue_ids","");
        for(String key:saved.split(",")) {
            if(Api.prefs(c).getLong("resume_position_"+key,0)<15000)continue;
            for(Item item:movies) if(("movie:"+item.id).equals(key)) { result.add(item); break; }
            for(Item item:series) if(("series:"+item.id).equals(key)) { result.add(item); break; }
        } return result;
    }
    static void remember(Context c,Item item) {
        String key=item.kind+":"+item.id;
        String existing=Api.prefs(c).getString("continue_ids","");
        StringBuilder s=new StringBuilder(key);
        for(String other:existing.split(",")) if(!other.isEmpty()&&!other.equals(key)&&s.length()<500) s.append(',').append(other);
        Api.prefs(c).edit().putString("continue_ids",s.toString()).apply();
    }
    static void saveProgress(Context c,String kind,String id,String episodeId,String episodeExt,
                             String nextId,String nextExt,long position,long duration) {
        if(kind==null || id==null || id.isEmpty() || position<15000)return;
        String key=kind+":"+id;
        if(duration>0 && (position>=duration-30000 || position*100>=duration*95)){
            clearProgress(c,kind,id);return;
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
        String saved=Api.prefs(c).getString("continue_ids","");
        StringBuilder updated=new StringBuilder(key);
        for(String other:saved.split(","))if(!other.isEmpty()&&!other.equals(key)&&updated.length()<500)updated.append(',').append(other);
        Api.prefs(c).edit().putString("continue_ids",updated.toString()).apply();
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
