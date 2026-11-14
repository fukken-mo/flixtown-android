package com.flixtown.tv;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Read-only discovery on the signed-in Xtream backend. No filename guessing or public file URLs. */
final class ExternalSubtitles {
    static final String ID_PREFIX="sidecar:";
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    static final class Track {
        final String name,language,mime,url;final boolean hearingImpaired,forced;
        Track(String name,String language,String mime,String url,boolean hearingImpaired,boolean forced){
            this.name=name;this.language=language;this.mime=mime;this.url=url;
            this.hearingImpaired=hearingImpaired;this.forced=forced;
        }
        String label(){
            String lang="und".equals(language)?"Subtitle":Locale.forLanguageTag(language).getDisplayLanguage(Locale.getDefault());
            return (lang.isEmpty()?language:lang)+(hearingImpaired?" · SDH":"")+(forced?" · Forced":"")+" · External";
        }
    }
    interface Reply {void loaded(List<Track> tracks,boolean unavailable);}
    static Future<?> load(String endpoint,Reply reply){return IO.submit(()->{
        List<Track> tracks=Collections.emptyList();boolean unavailable=false;
        try{tracks=parse(endpoint,read(endpoint));}catch(Exception ignored){unavailable=true;}
        if(!Thread.currentThread().isInterrupted())reply.loaded(tracks,unavailable);
    });}
    static List<Track> parse(String endpoint,JSONObject response)throws Exception{
        if(response.optInt("schema",0)!=1)throw new IOException("Unknown subtitle schema");
        JSONArray array=response.optJSONArray("tracks");if(array==null)throw new IOException("Missing tracks");
        URL base=new URL(endpoint);
        if(!"https".equals(base.getProtocol())&&!"http".equals(base.getProtocol()))throw new IOException();
        List<Track> tracks=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<array.length()&&tracks.size()<32;i++){
            JSONObject t=array.optJSONObject(i);if(t==null)continue;
            String name=t.optString("name","");String mime=t.optString("mime","");
            if(name.isEmpty()||name.length()>255||name.contains("/")||name.contains("\\")||name.indexOf(0)>=0||!seen.add(name))continue;
            if(!"application/x-subrip".equals(mime)&&!"text/vtt".equals(mime))continue;
            String language=t.optString("language","und").toLowerCase(Locale.ROOT);
            if(!language.matches("[a-z]{2,3}(-[a-z]{2})?"))language="und";
            // Ignore any server-supplied URL; the authenticated file route stays on this origin.
            String address=base.getProtocol()+"://"+base.getAuthority()+base.getPath()+"/file?"+base.getQuery()+"&name="+Api.enc(name);
            tracks.add(new Track(name,language,mime,address,t.optBoolean("hearing_impaired"),t.optBoolean("forced")));
        }
        return tracks;
    }
    private static JSONObject read(String address)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        try{if(c.getResponseCode()!=200)throw new IOException();
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>128*1024)throw new IOException();out.write(b,0,n);}
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{c.disconnect();}
    }
}
