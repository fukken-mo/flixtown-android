package com.flixtown.tv;

import org.json.JSONObject;
import java.util.*;

/** Whitelisted account data. No credentials, URLs, artwork, settings or notice acknowledgements. */
final class SyncRecord {
    static String entity(String pref){
        if(pref.startsWith("watched_")&&valid("watched:"+pref.substring(8)))return "watched:"+pref.substring(8);
        for(String field:RESUME)if(pref.startsWith("resume_"+field+"_")){
            String key="resume:"+pref.substring(("resume_"+field+"_").length());if(valid(key))return key;
        }
        return null;
    }
    static final String[] RESUME={"position","duration","episode","extension","next","next_ext","label","done"};
    static boolean valid(String key){return key!=null&&key.matches("(?:(?:resume|favorite):(?:movie|series):[1-9][0-9]{0,9}|watched:movie:[1-9][0-9]{0,9}|watched:series:[1-9][0-9]{0,9}:[1-9][0-9]{0,9})");}
    static Set<String> favorites(String value){
        Set<String> out=new LinkedHashSet<>();for(String k:value.split("\\|"))if(valid("favorite:"+k))out.add(k);return out;
    }
    static Set<String> favoriteChanges(String old,String now){Set<String> a=favorites(old),b=favorites(now),out=new HashSet<>(a);out.addAll(b);for(Iterator<String> it=out.iterator();it.hasNext();){String k=it.next();if(a.contains(k)==b.contains(k))it.remove();}return out;}
    static JSONObject capture(Map<String,?> prefs,String key)throws Exception{
        if(!valid(key))throw new IllegalArgumentException("record");
        String media=key.substring(key.indexOf(':')+1);JSONObject v=new JSONObject();
        if(key.startsWith("favorite:"))return v.put("present",favorites(string(prefs,"favorites","")).contains(media));
        if(key.startsWith("watched:"))return v.put("present",Boolean.TRUE.equals(prefs.get("watched_"+media)));
        boolean present=number(prefs,"resume_position_"+media)>=15000||!string(prefs,"resume_episode_"+media,"").isEmpty();
        v.put("present",present);if(!present)return v;
        String episode=string(prefs,"resume_episode_"+media,""),next=string(prefs,"resume_next_"+media,"");
        if(key.startsWith("resume:series:")&&!episode.matches("[1-9][0-9]{0,9}"))throw new IllegalArgumentException("episode");
        if(!next.matches("(?:[1-9][0-9]{0,9})?"))next="";
        if(key.startsWith("resume:movie:")){episode="";next="";}
        v.put("position",Math.max(0,Math.min(604800000,number(prefs,"resume_position_"+media))))
            .put("duration",Math.max(0,Math.min(604800000,number(prefs,"resume_duration_"+media))))
            .put("episode",episode)
            .put("extension",extension(string(prefs,"resume_extension_"+media,"mp4")))
            .put("next",next)
            .put("next_ext",extension(string(prefs,"resume_next_ext_"+media,"mp4")))
            .put("done",Boolean.TRUE.equals(prefs.get("resume_done_"+media)));
        String label=string(prefs,"resume_label_"+media,"");
        // Older metadata can contain arbitrary provider text; omit rather than transmit it.
        if(label.length()>80||label.matches("(?is).*(https?:|[\\p{Cntrl}]|(?:password|token|session|username)\\s*[=:]).*"))label="";
        return v.put("label",label);
    }
    static String extension(String v){return v.matches("[a-zA-Z0-9]{1,10}")?v:"mp4";}
    static String string(Map<String,?> p,String k,String fallback){Object v=p.get(k);return v instanceof String?(String)v:fallback;}
    static long number(Map<String,?> p,String k){Object v=p.get(k);return v instanceof Long?(Long)v:0;}
    static boolean matchesOperation(String current,String sent){return current!=null&&current.equals(sent);}
}
