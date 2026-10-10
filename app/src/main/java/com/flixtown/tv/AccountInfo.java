package com.flixtown.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The account details shown in Settings, exactly as the Flix Town backend reports them in the
 * Xtream player_api user_info (the same data OnePanel edits): status, exp_date (null when the line
 * never expires) and max_connections. Nothing is calculated or guessed here.
 *
 * A response is only saved when it really is an account answer (a user_info with a status), so a
 * network error, a proxy page or a server hiccup never marks the account Expired; the last valid
 * details stay on screen and the failure is shown as a subtle note instead.
 */
final class AccountInfo {
    private AccountInfo(){}
    static final String STATUS="account_status",EXP_DATE="account_exp_date",CONNECTIONS="account_max_connections",
        TIMEZONE="account_timezone",CHECKED_AT="account_checked_at",FAILED_AT="account_failed_at";
    static final String[] KEYS={STATUS,EXP_DATE,CONNECTIONS,TIMEZONE,CHECKED_AT,FAILED_AT};
    /** A check this recent (for example the startup refresh that just finished) is not repeated when Settings opens. */
    private static final long RECENT_MS=10_000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static boolean checking;

    /** Saves a player_api answer. Returns false (and saves nothing) when it is not a usable account answer. */
    static boolean save(Context c,JSONObject response){
        JSONObject user=response==null?null:response.optJSONObject("user_info");
        String status=user==null?"":user.optString("status","").trim();
        if(status.isEmpty() || "null".equals(status))return false;
        SharedPreferences.Editor e=Api.prefs(c).edit().putString(STATUS,status)
            .putString(EXP_DATE,user.isNull("exp_date")?"":user.optString("exp_date","").trim());
        int connections=-1;try{connections=Integer.parseInt(user.optString("max_connections","").trim());}catch(Exception ignored){}
        if(connections>0)e.putInt(CONNECTIONS,connections);
        else if(isActive(status))e.remove(CONNECTIONS);   // an expired line's answer has no count: keep the last one
        JSONObject server=response.optJSONObject("server_info");
        String zone=server==null?"":server.optString("timezone","").trim();
        if(!zone.isEmpty() && TimeZone.getTimeZone(zone).getID().equals(zone))e.putString(TIMEZONE,zone);
        e.putLong(CHECKED_AT,System.currentTimeMillis()).remove(FAILED_AT).apply();
        return true;
    }
    static void failed(Context c){Api.prefs(c).edit().putLong(FAILED_AT,System.currentTimeMillis()).apply();}

    static boolean checking(){return checking;}

    /**
     * Account-only check (one small request, no catalog download). Calls back on the main thread
     * with true when fresh details were saved. Skipped when one is running or one just finished.
     */
    static void refresh(Context context,boolean force,Callback done){
        Context c=context.getApplicationContext();
        if(checking || AccountStore.read(c)==null)return;
        if(!force && System.currentTimeMillis()-Api.prefs(c).getLong(CHECKED_AT,0)<RECENT_MS)return;
        checking=true;
        String[] account=AccountStore.read(c);
        Api.IO.execute(()->{boolean ok=false;
            try{
                JSONObject response=Api.get(Api.accountUrl(c));
                String[] now=AccountStore.read(c);   // signed out (or switched) while waiting: drop the answer
                if(now!=null && now[0].equals(account[0]))ok=save(c,response);
                if(!ok && now!=null)failed(c);
            }catch(Exception e){Log.w("FlixTown","Account check failed; the last details stay");failed(c);}
            boolean saved=ok;
            MAIN.post(()->{checking=false;if(done!=null)done.onDone(saved);});
        });
    }
    interface Callback { void onDone(boolean saved); }

    static boolean isActive(String status){return "Active".equalsIgnoreCase(status);}
    static String status(Context c){return Api.prefs(c).getString(STATUS,"");}

    /** "Never" for a line with no expiration date, MM/dd/yyyy (as OnePanel shows it) otherwise, "" when unknown. */
    static String expiration(Context c){
        SharedPreferences p=Api.prefs(c);
        if(p.getString(STATUS,"").isEmpty())return "";
        String raw=p.getString(EXP_DATE,"").trim();
        if(raw.isEmpty() || "null".equals(raw) || "0".equals(raw))return "Never";
        long secs;try{secs=Long.parseLong(raw);}catch(Exception e){return "";}
        if(secs<=0)return "Never";
        SimpleDateFormat f=new SimpleDateFormat("MM/dd/yyyy",Locale.US);
        // The panel's own timezone, so the date matches OnePanel; the TV's if the server did not say.
        String zone=p.getString(TIMEZONE,"");
        f.setTimeZone(zone.isEmpty()?TimeZone.getDefault():TimeZone.getTimeZone(zone));
        return f.format(new Date(secs*1000));
    }
    /** Allowed connections from the backend, or 0 when it has not reported one. */
    static int connections(Context c){return Api.prefs(c).getInt(CONNECTIONS,0);}
    static long checkedAt(Context c){return Api.prefs(c).getLong(CHECKED_AT,0);}
    /** True when the latest check failed (the details shown are the last valid ones). */
    static boolean lastCheckFailed(Context c){return Api.prefs(c).getLong(FAILED_AT,0)>0;}
}
