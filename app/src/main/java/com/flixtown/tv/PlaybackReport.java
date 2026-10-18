package com.flixtown.tv;

import org.json.JSONObject;
import java.util.UUID;

/** Immutable, allowlisted report snapshot. Never takes a stream URL, account or raw exception. */
final class PlaybackReport {
    static final String[] ISSUES={"stutter","no_audio","audio_sync","subtitles","wrong_title","wont_play"};
    static final String[] LABELS={"Video stutters / slow motion","No sound","Audio out of sync","Subtitle problem","Wrong movie or episode","Video won't play"};
    final String requestId=UUID.randomUUID().toString();
    final String kind,video,audio,error,device;final int mediaId,episodeId,androidApi;final long position;
    PlaybackReport(String kind,String id,String ep,long position,int api,String device,String video,String audio,String error){
        this.kind=kind;mediaId=id(id);episodeId="series".equals(kind)?id(ep):0;
        this.position=Math.max(0,Math.min(604800000L,position));androidApi=api;
        this.device=clean(device,120);this.video=clean(video,80);this.audio=clean(audio,80);this.error=clean(error,80);
    }
    boolean valid(){return ("movie".equals(kind)||"series".equals(kind))&&mediaId>0&&(!"series".equals(kind)||episodeId>0);}
    JSONObject payload(int issue)throws Exception{
        if(!valid()||issue<0||issue>=ISSUES.length)throw new IllegalArgumentException("Invalid report");
        return new JSONObject().put("request_id",requestId).put("type",kind).put("media_id",mediaId).put("episode_id",episodeId)
            .put("issue",ISSUES[issue]).put("position_ms",position).put("version_code",BuildConfig.VERSION_CODE)
            .put("version_name",BuildConfig.VERSION_NAME).put("android_api",androidApi).put("device",device)
            .put("video_format",video).put("audio_format",audio).put("error_code",error);
    }
    static int id(String s){try{int n=Integer.parseInt(s);return Math.max(0,n);}catch(Exception e){return 0;}}
    static String clean(String value,int max){
        if(value==null)return "";
        String v=value.trim();
        if(v.matches("(?is).*https?://.*")||v.matches("(?is).*(username|password|token|session)\\s*[=:].*"))return "";
        v=v.replaceAll("[\\p{Cntrl}]","");
        return v.length()>max?v.substring(0,max):v;
    }
}
