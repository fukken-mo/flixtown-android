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
        final String id,title,poster,backdrop,kind,extension;
        final int year,added;
        Item(JSONObject j,String kind) {
            this.kind=kind; id=j.optString("stream_id",j.optString("series_id",""));
            title=j.optString("name",j.optString("title","Untitled"));
            poster=j.optString("stream_icon",j.optString("cover",""));
            String bg=j.optString("backdrop_path","");
            if (bg.startsWith("[")) { JSONArray a=j.optJSONArray("backdrop_path"); bg=a!=null ? a.optString(0,"") : ""; }
            backdrop=bg.isEmpty()?poster:bg;
            extension=j.optString("container_extension","mp4");
            year=j.optInt("year",0); added=j.optInt("added",j.optInt("last_modified",0));
        }
    }
    static List<Item> parse(String json,String kind) {
        ArrayList<Item> items=new ArrayList<>();
        try { JSONArray array=new JSONArray(json); for(int i=0;i<array.length();i++) {
            JSONObject j=array.optJSONObject(i); if(j!=null) { Item item=new Item(j,kind); if(!item.id.isEmpty())items.add(item); }
        }} catch (Exception ignored) {} return items;
    }
    static List<Item> recent(List<Item> original,int limit) {
        ArrayList<Item> sorted=new ArrayList<>(original); sorted.sort((a,b)->Integer.compare(b.added,a.added));
        return sorted.subList(0,Math.min(limit,sorted.size()));
    }
    static List<Item> continueWatching(Context c,List<Item> movies,List<Item> series) {
        List<Item> result=new ArrayList<>();
        String saved=Api.prefs(c).getString("continue_ids","");
        for(String key:saved.split(",")) {
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
}
