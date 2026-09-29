package com.flixtown.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Offline catalog for the "qa" build type only (BuildConfig.DEMO). It lets the app be driven on an
 * emulator with a D-pad without touching the real panel or Xtream server. Release and debug builds
 * never reach this code because BuildConfig.DEMO is false there.
 */
final class DemoData {
    private DemoData(){}
    /** Public test stream with several audio languages and subtitle tracks. */
    static final String STREAM="https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8";
    private static final String[] A={"Silent","Crimson","Last","Midnight","Broken","Golden","Hidden","Northern","Electric","Lost","Wild","Paper","Iron","Distant","Hollow"};
    private static final String[] B={"Harbor","Frontier","Signal","Garden","Kingdom","Horizon","Letters","Station","Empire","Tide","Orchard","Protocol","Summer","Voyage","Circuit"};
    private static final String[] MOVIE_CATS={"Action","Drama","Comedy","Thriller","Family","Sci-Fi"};
    private static final String[] SERIES_CATS={"Crime","Documentary","Animation","Reality"};
    private static int polls;

    static String title(int i,int salt){String t=A[(i*7+salt)%A.length]+" "+B[(i*11+salt*3)%B.length];
        return i%9==4?"The "+t+" and the Long Road Home":i%5==0?"The "+t:t;}

    static volatile boolean offline,slow;
    static String respond(String url,String body)throws Exception{
        if(offline){Thread.sleep(400);throw new java.io.IOException("Demo: network unreachable");}
        Thread.sleep(url.contains("get_vod_streams")?(slow?21000:900):250);
        if(url.contains("config.php"))return new JSONObject().put("xtream_url","https://demo.flixtown.invalid").put("intro_enabled",false)
            .put("intro_url","").put("cashapp_url","https://cash.app/$FlixTownDemo")
            .put("plans",new JSONObject().put("1m","15").put("3m","40").put("6m","75").put("12m","140")).toString();
        if(url.contains("pair-start")){polls=0;return new JSONObject().put("code","FT4K2Q").put("verifier","demo")
            .put("activation_url","https://myflixtown.com/activate.php?code=FT4K2Q").toString();}
        if(url.contains("pair-poll")){polls++;
            // Stays pending long enough to capture the QR screen, then approves.
            return polls<5?new JSONObject().put("status","pending").toString()
                :new JSONObject().put("status","approved").put("account",new JSONObject().put("username","demo").put("password","demo")).toString();}
        if(url.contains("renewal-request"))return new JSONObject().put("ok",true).toString();
        if(url.contains("tmdb.php")){JSONArray cast=new JSONArray();
            String[] names={"Ava Moreno","Daniel Okafor","Mia Laurent","Jonah Reyes","Priya Nair","Leo Hart","Nora Quinn","Sam Whitaker"};
            for(int i=0;i<names.length;i++)cast.put(new JSONObject().put("name",names[i]).put("id",100+i).put("image",i%3==2?"":"demo://person/"+i));
            return new JSONObject().put("cast",cast).toString();}
        if(url.contains("actor.php")){JSONArray titles=new JSONArray();
            for(int i=0;i<6;i++)titles.put(new JSONObject().put("kind","movie").put("title",title(i*3,1)));
            return new JSONObject().put("titles",titles).toString();}
        if(url.contains("action=get_vod_categories"))return categories(MOVIE_CATS,1);
        if(url.contains("action=get_series_categories"))return categories(SERIES_CATS,50);
        if(url.contains("action=get_vod_streams")){JSONArray list=new JSONArray();
            for(int i=0;i<72;i++)list.put(new JSONObject().put("stream_id",String.valueOf(1000+i)).put("name",title(i,1))
                .put("stream_icon","demo://poster/m"+i).put("category_id",String.valueOf(1+i%MOVIE_CATS.length))
                .put("container_extension","mp4").put("added",String.valueOf(1760000000-i*86400)).put("rating",String.format(Locale.US,"%.1f",5+(i*37%45)/10.0))
                .put("year",1998+(i*5)%27).put("backdrop_path",new JSONArray().put("demo://backdrop/m"+i))
                .put("plot","A demo title used to check layouts. "+(i%2==0?"When an old signal returns from the past, a small crew must decide how much of the truth to share before the storm reaches the coast.":"Two strangers share one long night on the road.")));
            return list.toString();}
        if(url.contains("action=get_series_info")){JSONObject episodes=new JSONObject();
            for(int s=1;s<=3;s++){JSONArray list=new JSONArray();
                for(int e=1;e<=8;e++)list.put(new JSONObject().put("id",String.valueOf(9000+s*100+e)).put("episode_num",e).put("season",s)
                    .put("title","Chapter "+e+": "+B[(s*3+e)%B.length]).put("container_extension","mkv")
                    .put("info",new JSONObject().put("movie_image","demo://backdrop/e"+(s*10+e))));
                episodes.put(String.valueOf(s),list);}
            return new JSONObject().put("info",new JSONObject().put("plot","A demo series with three seasons, used to check the season selector and episode row.")
                .put("genre","Crime, Drama").put("rating","8.1").put("releaseDate","2021-04-02")).put("episodes",episodes).toString();}
        if(url.contains("action=get_vod_info"))return new JSONObject().put("info",new JSONObject()
                .put("plot","When an old signal returns from the past, a small crew must decide how much of the truth to share before the storm reaches the coast. A demo synopsis long enough to wrap onto three lines on a TV screen.")
                .put("genre","Drama, Thriller").put("duration_secs",6840).put("rating","7.6").put("releasedate","2019-09-14")
                .put("backdrop_path",new JSONArray().put("demo://backdrop/detail")).put("cast","Ava Moreno, Daniel Okafor"))
            .put("movie_data",new JSONObject().put("container_extension","mp4")).toString();
        if(url.contains("action=get_series")){JSONArray list=new JSONArray();
            for(int i=0;i<30;i++)list.put(new JSONObject().put("series_id",String.valueOf(5000+i)).put("name",title(i,5))
                .put("cover","demo://poster/s"+i).put("category_id",String.valueOf(50+i%SERIES_CATS.length))
                .put("last_modified",String.valueOf(1760000000-i*43200)).put("rating",String.format(Locale.US,"%.1f",6+(i*13%38)/10.0))
                .put("year",2005+i%19).put("backdrop_path",new JSONArray().put("demo://backdrop/s"+i)).put("plot","A demo series."));
            return list.toString();}
        if(url.contains("player_api.php"))return new JSONObject().put("user_info",new JSONObject()
            .put("status",Boolean.getBoolean("flix.demo.expired")||expired?"Expired":"Active").put("exp_date","1756684800").put("username","demo")).toString();
        throw new IllegalStateException("No demo response for "+url);
    }
    static volatile boolean expired;
    private static String categories(String[] names,int first)throws Exception{JSONArray list=new JSONArray();
        for(int i=0;i<names.length;i++)list.put(new JSONObject().put("category_id",String.valueOf(first+i)).put("category_name",names[i]));
        return list.toString();}

    /** Locally drawn artwork: a colour gradient with the title, so layouts can be judged without network images. */
    static Bitmap image(String url,int width){
        boolean poster=url.contains("/poster/"),person=url.contains("/person/");
        int w=Math.max(64,Math.min(width,480)),h=poster?w*3/2:person?w:w*9/16;
        Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.RGB_565);Canvas c=new Canvas(bmp);
        int hash=url.hashCode()*0x9E3779B1;hash^=hash>>>15;float hue=Math.abs(hash%360);
        int top=android.graphics.Color.HSVToColor(new float[]{hue,.55f,.55f}),bottom=android.graphics.Color.HSVToColor(new float[]{(hue+40)%360,.7f,.18f});
        Paint p=new Paint();p.setShader(new LinearGradient(0,0,w,h,top,bottom,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);
        if(poster){TextPaint t=new TextPaint(Paint.ANTI_ALIAS_FLAG);t.setColor(0xF0FFFFFF);t.setTextSize(w/8f);t.setFakeBoldText(true);
            String key=url.substring(url.lastIndexOf('/')+1);int i=Integer.parseInt(key.substring(1));
            String name=key.startsWith("m")?title(i,1):title(i,5);
            StaticLayout layout=new StaticLayout(name.toUpperCase(Locale.US),t,w-w/6,Layout.Alignment.ALIGN_NORMAL,1f,0,false);
            c.save();c.translate(w/12f,h-layout.getHeight()-w/10f);layout.draw(c);c.restore();}
        return bmp;
    }
}
