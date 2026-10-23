package com.flixtown.tv;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reporting and optional Home resolution notices. No persisted sessions, admin secrets or raw URL logs. */
final class ReportsApi {
    static final String URL_KEY="reports_api_url";
    static final String UNAVAILABLE="Reports are temporarily unavailable. Please try again later.";
    static String url(Context c){
        JSONObject cfg=StartupRefresh.config();
        if(cfg!=null){String value=cfg.optString(URL_KEY,"").trim();Api.prefs(c).edit().putString(URL_KEY,safeUrl(value)?value:"").apply();}
        String value=Api.prefs(c).getString(URL_KEY,"");return safeUrl(value)?value:"";
    }
    static boolean safeUrl(String s){
        try{URL u=new URL(s);return "https".equals(u.getProtocol())&&!u.getHost().isEmpty()&&u.getUserInfo()==null&&u.getQuery()==null&&u.getRef()==null;}
        catch(Exception e){return false;}
    }
    static final class Reply {
        final boolean ok;final String message;
        Reply(boolean ok,String message){this.ok=ok;this.message=message;}
    }
    /** Called on a worker with a frozen account and endpoint, never an Activity reference. */
    static Reply submit(String endpoint,String[] account,JSONObject report){
        try{
            if(!safeUrl(endpoint)||account==null)return new Reply(false,UNAVAILABLE);
            JSONObject auth=post(endpoint,new JSONObject().put("action","session").put("username",account[0]).put("password",account[1]));
            if(!auth.optBoolean("ok"))return failure(auth);
            String session=auth.optString("session","");if(session.isEmpty()||session.length()>400)return new Reply(false,UNAVAILABLE);
            JSONObject result=post(endpoint,new JSONObject(report.toString()).put("action","submit").put("session",session));
            if(!result.optBoolean("ok"))return failure(result);
            return new Reply(true,result.optBoolean("duplicate")?"You already reported this problem. Thank you.":"Report sent. Thank you for letting us know.");
        }catch(Exception ignored){return new Reply(false,UNAVAILABLE);}
    }
    /** Optional Home check, using the same short-lived authenticated report session. */
    static JSONObject resolved(String endpoint,String[] account,long before){
        try{
            if(!safeUrl(endpoint)||account==null)return null;
            JSONObject auth=post(endpoint,new JSONObject().put("action","session").put("username",account[0]).put("password",account[1]));
            if(!auth.optBoolean("ok"))return null;
            JSONObject r=post(endpoint,new JSONObject().put("action","resolved").put("session",auth.optString("session")).put("before_id",before));
            return r.optBoolean("ok")?r:null;
        }catch(Exception ignored){return null;}
    }
    private static Reply failure(JSONObject r){
        String code=r.optString("error","");String message=UNAVAILABLE;
        if("busy".equals(code))message="You have sent several reports. Please try again later.";
        if("auth".equals(code)||"session".equals(code)||"inactive".equals(code))message="Please sign in with an active Flix Town account.";
        if("media".equals(code))message="This title is no longer available. Please refresh your catalog.";
        return new Reply(false,message);
    }
    private static JSONObject post(String endpoint,JSONObject body)throws Exception{
        if(BuildConfig.DEMO)return new JSONObject(DemoData.respond(endpoint,body.toString()));
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
        byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);
        try{
            try(java.io.OutputStream out=c.getOutputStream()){out.write(data);}
            int status=c.getResponseCode();
            try(InputStream in=status>=400?c.getErrorStream():c.getInputStream()){
                if(in==null)throw new IllegalStateException();
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>65536)throw new IllegalStateException();}
                JSONObject r=new JSONObject(out.toString("UTF-8"));
                if(status<200||status>=300)r.put("ok",false);
                return r;
            }
        }finally{c.disconnect();}
    }
}
