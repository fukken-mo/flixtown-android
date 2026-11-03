package com.flixtown.tv;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** No Jellyfin credentials or direct Jellyfin access. Isolated worker; failures quietly hide the button. */
final class IntroPreview {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"intro-preview");t.setPriority(Thread.MIN_PRIORITY);return t;});
    interface Reply {void loaded(IntroMarker marker);}
    static Future<?> load(String seriesUrl,String episodeId,Reply reply){return IO.submit(()->{
        IntroMarker marker=null;
        try{
            JSONObject manifest=read(BuildConfig.PANEL_URL+"intro-preview.json");
            marker=IntroMarker.find(manifest,read(seriesUrl),episodeId);
        }catch(Exception ignored){}
        reply.loaded(marker);
    });}
    private static JSONObject read(String address)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        try{if(c.getResponseCode()!=200)throw new IOException();
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException();out.write(b,0,n);}
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{c.disconnect();}
    }
}
