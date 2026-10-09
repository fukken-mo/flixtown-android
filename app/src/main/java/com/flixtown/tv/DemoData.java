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

    static volatile boolean offline,slow,intro,holdPairing;
    /** Catalog downloads in this process; from the second one on, the demo server has one new movie,
     *  one new series and a new episode for series 5003. */
    private static int movieFetches,seriesFetches;
    private static final long DAY=86400;
    /** Today at 00:00 UTC, so demo times move only once a day (the catalog looks unchanged between refreshes). */
    static long base(){return System.currentTimeMillis()/1000/DAY*DAY;}
    static int movieYear(int i){return i==0?java.util.Calendar.getInstance().get(java.util.Calendar.YEAR):1998+(i*5)%27;}
    static int seriesYear(int i){return i==1?java.util.Calendar.getInstance().get(java.util.Calendar.YEAR):2005+i%19;}
    static String respond(String url,String body)throws Exception{
        if(offline){Thread.sleep(400);throw new java.io.IOException("Demo: network unreachable");}
        Thread.sleep(url.contains("get_vod_streams")?(slow?21000:900):250);
        if(url.contains("config.php"))return new JSONObject().put("xtream_url","https://demo.flixtown.invalid").put("intro_enabled",intro)
            .put("intro_url",intro?STREAM:"").put("cashapp_url","https://cash.app/$FlixTownDemo")
            .put("plans",new JSONObject().put("1m","15").put("3m","40").put("6m","75").put("12m","140")).toString();
        if(url.contains("pair-start")){polls=0;return new JSONObject().put("code","FT4K2Q").put("verifier","demo")
            .put("activation_url","https://myflixtown.com/activate.php?code=FT4K2Q").toString();}
        if(url.contains("pair-poll")){polls++;
            // Stays pending long enough to capture the QR screen, then approves a digits-only account with
            // leading zeros (the format the panel generates), so QA proves they survive pairing.
            return polls<5||holdPairing?new JSONObject().put("status","pending").toString()
                :new JSONObject().put("status","approved").put("account",new JSONObject().put("username","0048213977").put("password","0000731946205518")).toString();}
        if(url.contains("renewal-request"))return new JSONObject().put("ok",true).toString();
        if(url.contains("tmdb.php")){JSONArray cast=new JSONArray();
            String[] names={"Ava Moreno","Daniel Okafor","Mia Laurent","Jonah Reyes","Priya Nair","Leo Hart","Nora Quinn","Sam Whitaker"};
            for(int i=0;i<names.length;i++)cast.put(new JSONObject().put("name",names[i]).put("id",100+i).put("image",i%3==2?"":"demo://person/"+i));
            return new JSONObject().put("cast",cast).put("trailer",STREAM).toString();}
        if(url.contains("ratings.php"))return ratings(body);
        if(url.contains("trending.php"))return trending();
        if(url.contains("actor.php")){JSONArray titles=new JSONArray();
            for(int i=0;i<6;i++)titles.put(new JSONObject().put("kind","movie").put("title",title(i*3,1)));
            return new JSONObject().put("titles",titles).toString();}
        if(url.contains("action=get_vod_categories"))return categories(MOVIE_CATS,1);
        if(url.contains("action=get_series_categories"))return categories(SERIES_CATS,50);
        if(url.contains("action=get_vod_streams")){JSONArray list=new JSONArray();
            if(++movieFetches>=2)list.put(new JSONObject().put("stream_id","1999").put("name","The Late Arrival")
                .put("stream_icon","demo://poster/m99").put("category_id","1").put("container_extension","mp4")
                .put("added",String.valueOf(base()-3600)).put("rating","10").put("year",2026)
                .put("backdrop_path",new JSONArray().put("demo://backdrop/m99")).put("plot","Added to the demo server after launch, to check that new titles appear without a restart."));
            for(int i=0;i<72;i++)list.put(new JSONObject().put("stream_id",String.valueOf(1000+i)).put("name",title(i,1))
                .put("stream_icon","demo://poster/m"+i).put("category_id",String.valueOf(1+i%MOVIE_CATS.length))
                .put("container_extension","mp4").put("added",String.valueOf(base()-DAY/2-i*2*DAY))
                // Provider ratings like many real servers: often "10". The app must ignore them.
                .put("rating",i%3==0?"10":String.format(Locale.US,"%.1f",5+(i*37%45)/10.0))
                .put("tmdb",i%4==3?String.valueOf(700000+i):"")
                .put("release_date",i==0?releaseDate(base()-3*DAY):movieYear(i)+"-01-01")
                .put("year",movieYear(i)).put("backdrop_path",i%3==1?new JSONArray():new JSONArray().put("demo://backdrop/m"+i))
                .put("plot","A demo title used to check layouts. "+(i%2==0?"When an old signal returns from the past, a small crew must decide how much of the truth to share before the storm reaches the coast.":"Two strangers share one long night on the road.")));
            return list.toString();}
        if(url.contains("action=get_series_info")){JSONObject episodes=new JSONObject();int sid=Integer.parseInt(param(url,"series_id"));
            for(int s=1;s<=3;s++){JSONArray list=new JSONArray();
                for(int e=1;e<=8;e++){JSONObject ep=new JSONObject().put("id",String.valueOf(9000+s*100+e)).put("episode_num",e).put("season",s)
                    .put("title","Chapter "+e+": "+B[(s*3+e)%B.length]).put("container_extension","mkv")
                    .put("info",new JSONObject().put("movie_image","demo://backdrop/e"+(s*10+e)));
                    long added=episodeAdded(sid,s,e,base());if(added>0)ep.put("added",String.valueOf(added));
                    list.put(ep);}
                if(s==3 && sid==5003 && seriesFetches>=2)list.put(new JSONObject().put("id","9399").put("episode_num",9).put("season",3)
                    .put("title","Chapter 9: Late Arrival").put("container_extension","mkv"));
                episodes.put(String.valueOf(s),list);}
            return new JSONObject().put("info",new JSONObject().put("plot","A demo series with three seasons, used to check the season selector and episode row.")
                .put("genre","Crime, Drama").put("rating","10").put("releaseDate","2021-04-02")).put("episodes",episodes).toString();}
        if(url.contains("action=get_vod_info"))return new JSONObject().put("info",new JSONObject()
                .put("plot","When an old signal returns from the past, a small crew must decide how much of the truth to share before the storm reaches the coast. A demo synopsis long enough to wrap onto three lines on a TV screen.")
                .put("genre","Drama, Thriller").put("duration_secs",6840).put("rating","7.6").put("releasedate","2019-09-14")
                .put("backdrop_path",new JSONArray().put("demo://backdrop/detail"+param(url,"vod_id"))).put("cast","Ava Moreno, Daniel Okafor"))
            .put("movie_data",new JSONObject().put("container_extension","mp4")).toString();
        if(url.contains("action=get_series")){JSONArray list=new JSONArray();seriesFetches++;long b=base();
            for(int i=0;i<30;i++)list.put(new JSONObject().put("series_id",String.valueOf(5000+i)).put("name",title(i,5))
                .put("cover","demo://poster/s"+i).put("category_id",String.valueOf(50+i%SERIES_CATS.length))
                .put("last_modified",String.valueOf(seriesModified(i,b))).put("rating","10")
                .put("first_air_date",i==1?releaseDate(base()-3*DAY):seriesYear(i)+"-01-01")
                .put("year",seriesYear(i)).put("backdrop_path",new JSONArray().put("demo://backdrop/s"+i)).put("genre",i%2==0?"Crime, Drama":"Documentary")
                .put("plot","A demo series about a small town, its secrets and the people who keep them. Used to check the Home preview."));
            if(seriesFetches>=2)list.put(new JSONObject().put("series_id","5099").put("name","The Night Shift Files").put("cover","demo://poster/s99")
                .put("category_id","50").put("first_air_date",releaseDate(b-2*DAY)).put("last_modified",String.valueOf(b-1800)).put("year",2026).put("backdrop_path",new JSONArray().put("demo://backdrop/s99"))
                .put("plot","Added to the demo server after the first catalog, so it is proven new."));
            return list.toString();}
        if(url.contains("player_api.php")){
            // Same shape as the Flix Town backend: exp_date is null for Never Expire; an expired line's
            // answer has auth 0 and no connection count.
            // QA: files/demo_account.json (written with run-as) changes the account while the app stays open.
            JSONObject live=null;
            try{if(accountFile!=null && accountFile.exists()){
                java.io.FileInputStream in=new java.io.FileInputStream(accountFile);byte[] data=new byte[(int)accountFile.length()];
                int n=0;while(n<data.length){int r=in.read(data,n,data.length-n);if(r<0)break;n+=r;}in.close();
                live=new JSONObject(new String(data,0,n,"UTF-8"));}}catch(Exception ignored){}
            if(live!=null && live.optBoolean("offline"))throw new java.io.IOException("Demo: account server unreachable");
            int connections=live!=null?live.optInt("connections",DemoData.connections):DemoData.connections;
            String expDate=live!=null && live.has("exp")?("never".equals(live.optString("exp"))?"":live.optString("exp")):DemoData.expDate;
            boolean ended=Boolean.getBoolean("flix.demo.expired")||expired||(live!=null && live.optBoolean("expired"));
            JSONObject user=new JSONObject().put("status",ended?"Expired":"Active").put("username","demo")
                .put("exp_date",expDate.isEmpty()?JSONObject.NULL:expDate);
            if(ended)user.put("auth",0);else user.put("auth",1).put("max_connections",String.valueOf(connections)).put("active_cons","0");
            return new JSONObject().put("user_info",user).put("server_info",new JSONObject().put("timezone","America/Denver")).toString();}
        throw new IllegalStateException("No demo response for "+url);
    }
    static volatile boolean expired;
    /** Demo account details (QA extras demo_connections and demo_exp: seconds, or "never"). */
    static volatile int connections=1;
    static volatile String expDate="1830254400";
    static volatile java.io.File accountFile;

    /*
     * Mixed cases for the ratings and "new" badges (times relative to today):
     *  5000 older series with a new episode 2 days ago        → NEW EPISODES (episode times)
     *  5001 whole series uploaded 3 days ago                    → NEW SERIES (episode times)
     *  5002 last_modified touched today, all episodes old       → no badge
     *  5003 no episode times; a new episode ID appears later    → NEW EPISODES on the second catalog
     *  5004–5009 last_modified bulk-touched 6 h ago, old episodes → no badge
     *  5099 appears in the second catalog (no episode times)    → NEW SERIES (catalog comparison)
     *  everything else: old                                     → no badge
     */
    static long seriesModified(int i,long b){
        switch(i){case 0:return b-DAY;case 1:return b-3*DAY;case 2:return b-7200;
            case 3:return seriesFetches>=2?b-3600:b-5*DAY;}
        return i<=9?b-6*3600:b-(40+i)*DAY;
    }
    static long episodeAdded(int sid,int season,int episode,long b){
        switch(sid){
            case 5000:return season==3 && episode==8?b-2*DAY:b-400*DAY+season*30*DAY+episode*DAY;
            case 5001:return b-3*DAY+season*600+episode*60;
            case 5003:case 5099:return 0;
            default:return b-500*DAY+season*30*DAY+episode*DAY;
        }
    }
    /** TMDB ratings as the panel would answer: some titles have none (shown without a rating). */
    private static String releaseDate(long at){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));return f.format(new java.util.Date(at*1000));}
    private static String ratings(String body)throws Exception{
        JSONArray items=new JSONObject(body).getJSONArray("items");JSONObject out=new JSONObject();
        for(int i=0;i<items.length();i++){JSONObject it=items.getJSONObject(i);String key=it.getString("key");
            int id=Integer.parseInt(key.substring(key.indexOf(':')+1));
            // TMDB agrees with the demo server: movie 1000 and series 5001/5099 were released 3 days ago.
            if(id%6==0||id==5003)out.put(key,JSONObject.NULL);
            else out.put(key,new JSONObject().put("rating",Math.round((5.8+(id*7%35)/10.0)*10)/10.0).put("votes",100+id%900).put("tmdb_id",id).put("release_date",id==1000||id==5001||id==5099?releaseDate(base()-3*DAY):"2000-01-01"));}
        return new JSONObject().put("ratings",out).toString();
    }
    /** TMDB trending as the panel would answer: a mix of titles on and not on the demo server. */
    private static String trending()throws Exception{
        JSONArray items=new JSONArray();
        Object[][] list={
            {"movie","900001","Dune: Part Three",2026,8.1},                 // not on the server
            {"movie","700003",title(3,1),movieYear(3),7.9},                  // by TMDB ID (movie 1003)
            {"tv","800002",title(2,5),seriesYear(2),8.6},                    // series 5002 by title + year
            {"movie","900002",title(5,1),movieYear(5)+6,7.0},                // same title, wrong year: no match
            {"movie","900010",title(10,1),movieYear(10)+1,6.4},              // title + year within one
            {"tv","800000",title(0,5),seriesYear(0),8.9},                    // series 5000
            {"movie","900020",title(20,1),movieYear(20),null},               // matched, too few votes: no rating
            {"tv","900099","Not On This Server",2026,7.7},
            {"movie","900040",title(40,1),movieYear(40),7.2},
            {"movie","900050",title(50,1),movieYear(50),6.9},
        };
        for(int i=0;i<list.length;i++){Object[] r=list[i];
            items.put(new JSONObject().put("rank",i+1).put("media",r[0]).put("id",Integer.parseInt((String)r[1])).put("title",r[2]).put("original",r[2])
                .put("year",r[3]).put("rating",r[4]==null?JSONObject.NULL:r[4]).put("votes",r[4]==null?4:1200));}
        return new JSONObject().put("items",items).toString();
    }
    private static String param(String url,String name){int at=url.indexOf(name+"=");if(at<0)return "";int end=url.indexOf('&',at);
        return url.substring(at+name.length()+1,end<0?url.length():end);}
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
        if(!poster && !person){Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG);glow.setColor(android.graphics.Color.HSVToColor(90,new float[]{(hue+180)%360,.5f,.9f}));
            c.drawCircle(w*.72f,h*.4f,h*.45f,glow);}
        if(poster){TextPaint t=new TextPaint(Paint.ANTI_ALIAS_FLAG);t.setColor(0xF0FFFFFF);t.setTextSize(w/8f);t.setFakeBoldText(true);
            String key=url.substring(url.lastIndexOf('/')+1);int i=Integer.parseInt(key.substring(1));
            String name=key.startsWith("m")?title(i,1):title(i,5);
            StaticLayout layout=new StaticLayout(name.toUpperCase(Locale.US),t,w-w/6,Layout.Alignment.ALIGN_NORMAL,1f,0,false);
            c.save();c.translate(w/12f,h-layout.getHeight()-w/10f);layout.draw(c);c.restore();}
        return bmp;
    }
}
