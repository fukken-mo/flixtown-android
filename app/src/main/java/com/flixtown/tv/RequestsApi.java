package com.flixtown.tv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Media requests through the operator's OnePanel (which talks to Seerr; no Seerr, OnePanel or
 * provider secret is in the app). The address comes from the panel config ("requests_api_url"),
 * read from the config answer the app already downloaded or the copy saved here; nothing in this
 * class runs during sign-in or Home loading. Requests screens and optional Home notices call it on workers.
 *
 * Identity: the customer's own saved Flix Town account is sent once (POST body) to get a short
 * session; later calls send only that session.
 */
final class RequestsApi {
    private RequestsApi(){}
    static final String URL_KEY="requests_api_url";
    static final String UNAVAILABLE="Requests are temporarily unavailable.";
    private static String session,sessionUser;private static long sessionUntil;

    static String url(Context c){
        JSONObject latest=StartupRefresh.config();   // this launch's config.php answer, when it has arrived
        if(latest!=null){String u=latest.optString(URL_KEY,"").trim();
            Api.prefs(c).edit().putString(URL_KEY,u.startsWith("https://")?u:"").apply();}
        String u=Api.prefs(c).getString(URL_KEY,"").trim();
        return u.startsWith("https://")?u:"";
    }

    /** A service answer. Network problems, server errors and anything unreadable are "unavailable". */
    static final class Reply {
        final boolean ok;final int http;final String error,message;final JSONObject body;
        Reply(int http,JSONObject body){this.http=http;this.body=body;ok=body.optBoolean("ok");
            error=body.optString("error","");String m=body.optString("message","");
            message=ok?"":(http>=500||error.equals("unavailable")||m.isEmpty()?UNAVAILABLE:m);}
        static Reply failed(){return new Reply(503,new JSONObject());}
        boolean unavailable(){return !ok && message.equals(UNAVAILABLE);}
    }

    static Reply search(Context c,String query,int page){return call(c,"search",o->o.put("query",query).put("page",page));}
    static Reply details(Context c,String type,int id){return call(c,"details",o->o.put("type",type).put("id",id));}
    static Reply history(Context c,int page){return call(c,"history",o->o.put("page",page));}
    static Reply request(Context c,String type,int id,int[] seasons){
        return call(c,"request",o->{o.put("type",type).put("id",id);
            if(seasons!=null&&seasons.length>0){JSONArray a=new JSONArray();for(int n:seasons)a.put(n);o.put("seasons",a);}});
    }
    static void forget(){session=null;sessionUser=null;sessionUntil=0;}

    private interface Fill{void into(JSONObject o)throws Exception;}
    /** Runs off the main thread. Signs in when needed and once more if the session ended. */
    private static Reply call(Context c,String action,Fill fill){
        try{
            JSONObject body=new JSONObject().put("action",action);fill.into(body);
            String[] account=AccountStore.read(c);
            if(account==null)return new Reply(401,new JSONObject().put("error","auth").put("message","Please sign in again."));
            if(session==null||System.currentTimeMillis()>sessionUntil||!account[0].equals(sessionUser)){Reply s=signIn(c,account);if(!s.ok)return s;}
            Reply r=post(c,new JSONObject(body.toString()).put("session",session));
            if(!r.ok && r.error.equals("session")){forget();Reply s=signIn(c,account);if(!s.ok)return s;
                r=post(c,new JSONObject(body.toString()).put("session",session));}
            return r;
        }catch(Exception e){return Reply.failed();}
    }
    private static Reply signIn(Context c,String[] account)throws Exception{
        Reply r=post(c,new JSONObject().put("action","session").put("username",account[0]).put("password",account[1]));
        if(r.ok){session=r.body.optString("session","");sessionUser=account[0];
            sessionUntil=System.currentTimeMillis()+Math.max(60,r.body.optLong("session_ttl",1800)-60)*1000L;}
        return r;
    }
    private static Reply post(Context c,JSONObject body)throws Exception{
        String url=url(c);if(url.isEmpty())return Reply.failed();
        if(BuildConfig.DEMO){JSONObject j=new JSONObject(DemoData.respond(url,body.toString()));return new Reply(j.optInt("_http",200),j);}
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(6000);connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
        connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json");
        try{
            connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
            int status=connection.getResponseCode();
            try(InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream()){
                if(stream==null)return Reply.failed();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=stream.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>262144)return Reply.failed();}
                return new Reply(status,new JSONObject(bytes.toString("UTF-8")));
            }
        }finally{connection.disconnect();}
    }
}
