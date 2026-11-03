package com.flixtown.tv;

import org.json.JSONArray;
import org.json.JSONObject;

/** Preview data only. Identity is TMDb show + explicit season/episode, never title or list position. */
final class IntroMarker {
    final long startMs,endMs,durationMs;
    IntroMarker(long start,long end,long duration){startMs=start;endMs=end;durationMs=duration;}
    boolean available(long position,long duration,boolean seekable){
        return seekable && duration>0 && Math.abs(duration-durationMs)<=2000
            && position>=startMs && position<endMs && endMs<duration;
    }
    static IntroMarker find(JSONObject manifest,JSONObject series,String episodeId){
        if(manifest==null||series==null||episodeId==null||!episodeId.matches("[0-9]+"))return null;
        if(manifest.optInt("schema")!=1)return null;
        JSONObject info=series.optJSONObject("info"),episodes=series.optJSONObject("episodes");
        if(info==null||episodes==null)return null;
        String tmdb=info.optString("tmdb_id","");if(tmdb.isEmpty())tmdb=info.optString("tmdb","");
        if(!tmdb.matches("[1-9][0-9]*"))return null;
        int season=-1,number=-1,matches=0;
        for(java.util.Iterator<String> it=episodes.keys();it.hasNext();){String sn=it.next();JSONArray a=episodes.optJSONArray(sn);if(a==null)continue;
            for(int i=0;i<a.length();i++){JSONObject ep=a.optJSONObject(i);if(ep==null||!episodeId.equals(ep.optString("id")))continue;
                matches++;season=ep.optInt("season",-1);number=ep.optInt("episode_num",-1);
                if(!sn.equals(String.valueOf(season)))return null;
            }}
        if(matches!=1||season<0||number<1)return null;
        JSONArray markers=manifest.optJSONArray("markers");if(markers==null||markers.length()>10000)return null;
        IntroMarker found=null;
        for(int i=0;i<markers.length();i++){JSONObject m=markers.optJSONObject(i);if(m==null)continue;
            if(!tmdb.equals(m.optString("tmdb"))||season!=m.optInt("season",-1)||number!=m.optInt("episode",-1))continue;
            long start=m.optLong("start_ms",-1),end=m.optLong("end_ms",-1),duration=m.optLong("duration_ms",-1);
            if(found!=null||start<0||end<=start||end-start>180000||duration<=end||duration>86400000)return null;
            found=new IntroMarker(start,end,duration);
        }
        return found;
    }
}
