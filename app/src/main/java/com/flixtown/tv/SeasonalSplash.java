package com.flixtown.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * Seasonal startup background: which picture to show, from what is already on the TV.
 *
 * Startup never waits for the network. The decision uses the last valid schedule saved on the TV
 * (or the built-in schedule) and the TV's local date. The schedule and pictures are refreshed in the
 * background and used from the next launch. A picture is shown only when it was downloaded for that
 * exact event and address and passed validation; otherwise the normal Flix Town background shows.
 *
 * Panel: {@code PANEL_URL + "seasonal.php"} (Flix Town panel, not OnePanel). Pictures must be https on
 * the panel's own host, at most 8 MB, decode as at least 1280×720 and be about 16:9.
 */
final class SeasonalSplash {
    private SeasonalSplash(){}
    private static final String PREFS="seasonal",KEY_SCHEDULE="schedule",KEY_FETCHED="fetched_at";
    private static final long REFRESH_MS=30*60_000L;          // at most one schedule check per 30 minutes
    private static final long MAX_IMAGE_BYTES=8L*1024*1024,MAX_CACHE_BYTES=60L*1024*1024;
    private static final int PREFETCH_DAYS=21;
    private static volatile boolean refreshing;

    static final class Choice{
        final String eventId,eventName;final File image;final int displayMs;
        Choice(String id,String name,File image,int displayMs){eventId=id;eventName=name;this.image=image;this.displayMs=displayMs;}
    }

    /** Today on this TV (local time zone, read each time: date and zone changes apply at once). */
    static long today(){
        if(BuildConfig.DEMO){String d=DemoData.seasonalToday();long v=SeasonalSchedule.parseDate(d);if(v!=Long.MIN_VALUE)return v;}
        Calendar c=Calendar.getInstance();
        return SeasonalSchedule.day(c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));
    }
    /** The last valid schedule from the panel, else the built-in one. Fast: one small prefs read. */
    static SeasonalSchedule schedule(Context c){
        String saved=prefs(c).getString(KEY_SCHEDULE,"");
        if(!saved.isEmpty()){
            try{SeasonalSchedule s=SeasonalSchedule.parse(new JSONObject(saved),true,url->validImageUrl(url));
                if(!s.expired(today()))return s;}catch(Exception ignored){}
        }
        return SeasonalSchedule.defaults();
    }
    /**
     * What to show at this launch. Called on the main thread; reads only small local state.
     * Today's event picture if it is on the TV; otherwise the everyday design if it is on the TV
     * (also when seasonal is off); otherwise null: the bundled Flix Town background and logo.
     */
    static Choice choose(Context c){
        SeasonalSchedule s=schedule(c);SeasonalSchedule.Event e=s.select(today());
        File f=e==null||e.image.isEmpty()?null:ready(cached(c,e.image));
        if(f==null && !s.defaultImage.isEmpty())f=ready(cached(c,s.defaultImage));
        return e==null?new Choice("","",f,s.displayMs):new Choice(e.id,e.name,f,s.displayMs);
    }
    private static File ready(File f){return f!=null&&f.isFile()&&f.length()>0?f:null;}

    /** Decodes a cached picture at no more than the screen size (RGB_565 on low-RAM TVs). Off the main thread. */
    static Bitmap decode(Context c,File f,int screenW,int screenH){
        try{
            BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),b);
            if(b.outWidth<1280||b.outHeight<720)return null;
            int sample=1;while(b.outWidth/(sample*2)>=screenW && b.outHeight/(sample*2)>=screenH)sample*=2;
            BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=sample;
            ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            o.inPreferredConfig=am!=null&&am.isLowRamDevice()?Bitmap.Config.RGB_565:Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(),o);
        }catch(Throwable t){return null;}   // includes OutOfMemoryError: the normal background shows instead
    }

    /** Background refresh (non-blocking). Skipped when one ran in the last 30 minutes or is running. */
    static void refreshAsync(Context context){
        Context c=context.getApplicationContext();
        long last=prefs(c).getLong(KEY_FETCHED,0),now=System.currentTimeMillis();
        if(refreshing || (now>=last && now-last<REFRESH_MS))return;
        refreshing=true;
        Api.IO.execute(()->{try{refresh(c);}catch(Throwable ignored){}finally{refreshing=false;
            if(BuildConfig.DEMO)android.util.Log.i("FlixTownQA","seasonal_refresh_done");}});   // QA: the emulator test waits for this
    }
    private static void refresh(Context c) throws Exception {
        String body;
        try{body=Api.request(BuildConfig.PANEL_URL+"seasonal.php",null);}
        catch(Api.HttpError e){
            // Not installed on the panel yet (404): keep using what is on the TV, check again later.
            prefs(c).edit().putLong(KEY_FETCHED,System.currentTimeMillis()).apply();return;}
        JSONObject root=new JSONObject(body);
        SeasonalSchedule parsed=SeasonalSchedule.parse(root,true,SeasonalSplash::validImageUrl);   // throws: the last good one stays
        prefs(c).edit().putString(KEY_SCHEDULE,root.toString()).putLong(KEY_FETCHED,System.currentTimeMillis()).apply();
        // Pictures for events showing now or within three weeks; everything else is removed.
        Set<String> keep=new HashSet<>();long today=today();
        if(!parsed.defaultImage.isEmpty()){File f=cached(c,parsed.defaultImage);keep.add(f.getName());if(!f.isFile())download(parsed.defaultImage,parsed.defaultImageSha256,f);}
        for(SeasonalSchedule.Event e:parsed.events){
            if(!e.enabled || e.image.isEmpty())continue;
            boolean soon=false;for(int d=0;d<=PREFETCH_DAYS && !soon;d++)soon=e.covers(today+d);
            if(!soon)continue;
            File f=cached(c,e.image);keep.add(f.getName());
            if(!f.isFile())download(e.image,e.imageSha256,f);
        }
        prune(c,keep);
    }
    static boolean validImageUrl(String url){
        if(url==null || url.length()>500)return false;
        if(BuildConfig.DEMO && url.startsWith("http://10.0.2.2"))return true;
        try{URL u=new URL(url);URL panel=new URL(BuildConfig.PANEL_URL);
            return "https".equals(u.getProtocol()) && u.getHost().equalsIgnoreCase(panel.getHost()) && u.getUserInfo()==null;}
        catch(Exception e){return false;}
    }
    /** Downloads to a temporary file; only a complete, checked picture replaces anything. */
    private static void download(String url,String sha256,File target){
        File tmp=new File(target.getParentFile(),target.getName()+".tmp");HttpURLConnection conn=null;
        try{
            conn=(HttpURLConnection)new URL(url).openConnection();
            conn.setConnectTimeout(7000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);conn.setUseCaches(false);
            if(conn.getResponseCode()!=200)return;
            String declared=conn.getHeaderField("Content-Length");
            if(declared!=null){try{if(Long.parseLong(declared.trim())>MAX_IMAGE_BYTES)return;}catch(NumberFormatException ignored){}}
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(tmp)){
                byte[] buf=new byte[16384];int n;
                while((n=in.read(buf))!=-1){total+=n;if(total>MAX_IMAGE_BYTES)return;out.write(buf,0,n);digest.update(buf,0,n);}
            }
            if(!sha256.isEmpty() && !sha256.equals(UpdateInfo.hex(digest.digest())))return;
            BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(tmp.getAbsolutePath(),b);
            float ratio=b.outHeight>0?b.outWidth/(float)b.outHeight:0;
            if(b.outWidth<1280 || b.outHeight<720 || ratio<1.70f || ratio>1.85f)return;   // not a 16:9 TV picture
            if(!tmp.renameTo(target))tmp.delete();
        }catch(Exception ignored){
        }finally{if(conn!=null)conn.disconnect();if(tmp.exists())tmp.delete();}
    }
    private static void prune(Context c,Set<String> keep){
        File[] files=dir(c).listFiles();if(files==null)return;long total=0;
        for(File f:files){if(!keep.contains(f.getName()))f.delete();else total+=f.length();}
        if(total>MAX_CACHE_BYTES)for(File f:files)if(f.exists())f.delete();   // never expected; start over rather than grow
    }
    /** The cache file for one picture address (the name changes with the address, so a replaced picture is fetched again). */
    static File cached(Context c,String url){
        try{return new File(dir(c),UpdateInfo.hex(MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"))).substring(0,32)+".img");}
        catch(Exception e){return new File(dir(c),"invalid.img");}
    }
    private static File dir(Context c){File d=new File(c.getApplicationContext().getFilesDir(),"seasonal");if(!d.isDirectory())d.mkdirs();return d;}
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    /** QA only: the next refreshAsync runs even within 30 minutes of the last one. */
    static void allowRefreshNow(Context c){prefs(c).edit().remove(KEY_FETCHED).apply();}
    /** QA only: forget the schedule and pictures. */
    static void reset(Context c){prefs(c).edit().clear().apply();File[] f=dir(c).listFiles();if(f!=null)for(File x:f)x.delete();}
}
