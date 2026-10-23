package com.flixtown.tv;

import android.app.Dialog;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Optional, bounded Home checks. Never blocks startup, plays video, or polls while idle. */
final class CustomerNotices {
    interface Host { boolean ready(); void watch(Catalog.Item item); }
    private final HomeActivity home;private final Host host;
    private final List<Notice> pending=new ArrayList<>();
    private boolean loading,snoozed;private volatile boolean closed;private long lastCheck=-1;private Dialog dialog;
    private String[] owner;
    CustomerNotices(HomeActivity home,Host host){this.home=home;this.host=host;}
    static final class Notice {
        String key,title,message,type,id,tmdb,year;boolean watchable;
    }
    /** A stable request identity includes seasons and creation time, like My Requests. */
    static String requestKey(JSONObject r){
        JSONArray a=r.optJSONArray("seasons");int[] seasons=new int[a==null?0:a.length()];
        for(int i=0;i<seasons.length;i++)seasons[i]=a.optInt(i);Arrays.sort(seasons);
        return r.optString("type")+":"+r.optInt("id")+":"+Arrays.toString(seasons)+":"+r.optLong("requested_at");
    }
    static String event(String state){
        if("local".equals(state)||"available".equals(state))return "ready";
        return "approved".equals(state)?"approved":"";
    }
    void check(){
        if(BuildConfig.DEMO&&!DemoData.customerNoticesEnabled())return;
        if(closed||snoozed||loading||dialog!=null||!host.ready())return;
        if(!pending.isEmpty()){show();return;}
        long now=SystemClock.elapsedRealtime();
        if(lastCheck>=0&&now-lastCheck<300000)return;
        String[] account=AccountStore.read(home);if(account==null)return;
        owner=account.clone();lastCheck=now;loading=true;
        String endpoint=ReportsApi.url(home); // snapshot configuration before moving off the UI thread
        Api.IO.execute(()->{
            List<Notice> found=new ArrayList<>();
            // Each service fails independently. History is limited to 10 pages per Home check.
            try{for(int page=1;page<=10;page++){
                if(!sameAccount())break;
                RequestsApi.Reply r=RequestsApi.history(home,page);if(!r.ok)break;
                JSONArray rows=r.body.optJSONArray("requests");
                for(int i=0;rows!=null&&i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                    String event=event(row.optString("state"));if(event.isEmpty())continue;
                    Notice n=new Notice();n.key="request:"+requestKey(row)+":"+event;
                    n.title=row.optString("title","Requested title");n.type=row.optString("type");
                    n.tmdb=row.optString("id");n.year=row.optString("year");n.watchable="ready".equals(event);
                    n.message=n.watchable?"Your requested title is ready to watch.":"Your request has been approved. We'll let you know when it is ready to watch.";
                    found.add(n);
                }
                if(!r.body.optBoolean("has_more"))break;
            }}catch(Exception ignored){}
            try{long before=0;for(int page=0;page<10&&!endpoint.isEmpty();page++){
                if(!sameAccount())break;
                JSONObject r=ReportsApi.resolved(endpoint,account,before);if(r==null)break;
                JSONArray rows=r.optJSONArray("reports");
                for(int i=0;rows!=null&&i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                    Notice n=new Notice();n.key="report:"+row.optString("id");n.title=row.optString("title","Reported title");
                    n.type=row.optString("type");n.id=row.optString("media_id");n.watchable=true;
                    n.message="Your reported issue has been marked resolved.";
                    if("series".equals(n.type))n.message+=" Season "+row.optInt("season")+", episode "+row.optInt("episode")+".";
                    found.add(n);
                }
                long next=r.optLong("before_id");if(!r.optBoolean("has_more")||next<=0||next==before)break;before=next;
            }}catch(Exception ignored){}
            home.runOnUiThread(()->{loading=false;if(closed||!sameAccount())return;
                for(Notice n:found)if(!Api.prefs(home).getBoolean("notice_seen_"+n.key,false))pending.add(n);
                show();});
        });
    }
    private boolean sameAccount(){return !closed&&Arrays.equals(owner,AccountStore.read(home));}
    private Catalog.Item match(Notice n){
        if(!n.watchable)return null;
        List<Catalog.Item> items="movie".equals(n.type)?Catalog.Store.movies:Catalog.Store.series;
        for(Catalog.Item item:items){
            if(n.id!=null&&n.id.equals(item.id))return item;
            if(n.tmdb!=null&&n.tmdb.equals(item.tmdbId)){
                if("movie".equals(n.type))try{if(item.year<=1900||Math.abs(item.year-Integer.parseInt(n.year))>1)continue;}catch(Exception e){continue;}
                return item;
            }
        }
        return null;
    }
    private void show(){
        if(closed||snoozed||dialog!=null||pending.isEmpty()||!sameAccount()||!host.ready())return;
        Notice n=pending.get(0);Catalog.Item item=match(n);
        String action=item!=null?"Watch":"View requests";
        boolean isReport=n.key.startsWith("report:");
        String[] labels=isReport&&item==null?new String[]{"Dismiss","Later"}:new String[]{action,"Dismiss","Later"};
        dialog=Ui.dialog(home,n.title,n.message,labels,0,index->{
            dialog=null;
            int dismiss=labels.length==2?0:1;
            if(index==labels.length-1){snoozed=true;return;} // Later and Back leave it unread for the next foreground visit.
            mark(n);pending.remove(0);
            if(index==dismiss){home.getWindow().getDecorView().post(this::show);return;}
            if(item!=null)host.watch(item);else home.startActivity(new Intent(home,RequestsActivity.class));
        },()->{dialog=null;snoozed=true;});
    }
    private void mark(Notice n){
        Api.prefs(home).edit().putBoolean("notice_seen_"+n.key,true).apply();
        if(n.key.startsWith("request:")&&n.key.endsWith(":ready")){
            String base=n.key.substring(0,n.key.length()-6);
            Api.prefs(home).edit().putBoolean("notice_seen_"+base+":approved",true)
                .putString("request_seen_"+base.substring("request:".length()),"available").apply();
        }
    }
    void resumed(){snoozed=false;}
    void close(){closed=true;pending.clear();if(dialog!=null){dialog.dismiss();dialog=null;}}
}
