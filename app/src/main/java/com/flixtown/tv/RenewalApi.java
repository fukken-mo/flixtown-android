package com.flixtown.tv;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * In-app renewal through the operator's renewal service (OnePanel). The service address comes from
 * the panel config (config.php "renewal_api_url"); without it the original renewal screen is used.
 *
 * The TV holds no payment or panel secret. It signs in once with the customer's own Flix Town
 * account (the service checks it against the Flix Town backend) and gets a short session; after that
 * it only sends the session, the chosen plan length and the payment id. Prices, the payment itself
 * and the renewal are decided and verified on the server; the TV only shows them.
 */
final class RenewalApi {
    private RenewalApi(){}
    static final String URL_KEY="renewal_api_url";
    private static String session;private static long sessionUntil;private static String sessionUser;

    /** The renewal service address from the panel config, or "" when in-app renewal is not offered. */
    static String url(Context c){
        String u=Api.prefs(c).getString(URL_KEY,"").trim();
        return u.startsWith("https://")?u:"";
    }
    static boolean available(Context c){return !url(c).isEmpty();}
    /** Saves (or clears) the address from a config.php answer. */
    static void remember(Context c,JSONObject config){
        if(config==null)return;
        String u=config.optString(URL_KEY,"").trim();
        Api.prefs(c).edit().putString(URL_KEY,u.startsWith("https://")?u:"").apply();
    }

    /** A service answer: ok, or a short error code and a message meant for the customer. */
    static final class Reply {
        final boolean ok;final String error,message;final JSONObject body;
        Reply(JSONObject body){this.body=body;ok=body.optBoolean("ok");error=body.optString("error","");
            message=body.optString("message","");}
    }

    /** Sign in and load the account, plans and prices. Runs off the main thread. */
    static Reply context(Context c)throws Exception{
        String[] account=AccountStore.read(c);if(account==null)throw new IllegalStateException("Sign in required");
        Reply r=post(c,new JSONObject().put("action","context").put("username",account[0]).put("password",account[1]));
        if(r.ok){session=r.body.optString("session","");sessionUser=account[0];
            sessionUntil=System.currentTimeMillis()+Math.max(60,r.body.optLong("session_ttl",1800)-60)*1000L;}
        return r;
    }
    static Reply create(Context c,int months)throws Exception{return withSession(c,new JSONObject().put("action","create").put("months",months));}
    static Reply status(Context c,String paymentId)throws Exception{return withSession(c,new JSONObject().put("action","status").put("payment_id",paymentId));}
    static Reply cancel(Context c,String paymentId)throws Exception{return withSession(c,new JSONObject().put("action","cancel").put("payment_id",paymentId));}

    /** Sends a call with the current session, signing in again once if the session ended. */
    private static Reply withSession(Context c,JSONObject body)throws Exception{
        String[] account=AccountStore.read(c);
        if(session==null||System.currentTimeMillis()>sessionUntil||account==null||!account[0].equals(sessionUser)){
            Reply r=context(c);if(!r.ok)return r;}
        Reply r=post(c,new JSONObject(body.toString()).put("session",session));
        if(!r.ok && "session".equals(r.error)){Reply again=context(c);if(!again.ok)return again;
            r=post(c,new JSONObject(body.toString()).put("session",session));}
        return r;
    }
    static void forget(){session=null;sessionUser=null;sessionUntil=0;}

    /** POST JSON; error answers (4xx/5xx) are still read so their message can be shown. */
    private static Reply post(Context c,JSONObject body)throws Exception{
        String url=url(c);if(url.isEmpty())throw new IllegalStateException("Renewal is not available");
        if(BuildConfig.DEMO)return new Reply(new JSONObject(DemoData.respond(url,body.toString())));
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(6000);connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
        connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json");
        try{
            connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
            int status=connection.getResponseCode();
            try(InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream()){
                if(stream==null)throw new IllegalStateException("Server returned "+status);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=stream.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>65536)break;}
                return new Reply(new JSONObject(bytes.toString("UTF-8")));   // not JSON (proxy page, outage) -> exception
            }
        }finally{connection.disconnect();}
    }
}
