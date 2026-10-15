package com.flixtown.tv;

import org.json.JSONObject;

/** Episode metadata already supplied by get_series_info. No network or provider rating fallback. */
final class EpisodePreview {
    private EpisodePreview(){}
    private static String first(JSONObject object,String... keys){
        if(object==null)return "";
        for(String key:keys){Object raw=object.opt(key);
            if(raw instanceof String){String value=((String)raw).trim();
                if(!value.isEmpty() && !"null".equalsIgnoreCase(value))return value;}}
        return "";
    }
    static String title(JSONObject ep){
        int season=ep.optInt("season",0),number=ep.optInt("episode_num",0);
        String name=first(ep,"title","name");
        String prefix=(season>0?"S"+season+" · ":"")+(number>0?"E"+number:"");
        return name.isEmpty()?(prefix.isEmpty()?"Episode":prefix):prefix.isEmpty()?name:prefix+"  —  "+name;
    }
    static String description(JSONObject ep){
        String text=first(ep.optJSONObject("info"),"plot","overview","description");
        if(text.isEmpty())text=first(ep,"plot","overview","description");
        return text.isEmpty()?"Episode description unavailable.":text;
    }
    static String art(JSONObject ep){
        String url=first(ep.optJSONObject("info"),"movie_image","still_url","image_url");
        if(url.isEmpty())url=first(ep,"movie_image","still_url","image_url");
        return url.startsWith("https://")||url.startsWith("http://")||url.startsWith("demo:")?url:"";
    }
    static String meta(JSONObject ep){
        JSONObject info=ep.optJSONObject("info");
        int seconds=info==null?0:info.optInt("duration_secs",0);
        if(seconds<=0){String duration=first(info,"duration");String[] p=duration.split(":");
            try{if(p.length==3)seconds=Integer.parseInt(p[0])*3600+Integer.parseInt(p[1])*60+Integer.parseInt(p[2]);
                else if(p.length==2)seconds=Integer.parseInt(p[0])*60+Integer.parseInt(p[1]);}catch(NumberFormatException ignored){}}
        String date=first(info,"air_date","releasedate","release_date");
        if(date.isEmpty())date=first(ep,"air_date","releasedate","release_date");
        if(date.matches("\\d{4}-\\d{2}-\\d{2}.*"))date="Aired "+date.substring(0,10);else date="";
        String runtime=seconds>=60?seconds/60+" min":"";
        return runtime.isEmpty()?date:date.isEmpty()?runtime:runtime+"   ·   "+date;
    }
}
