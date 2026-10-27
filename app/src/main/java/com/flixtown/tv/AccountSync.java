package com.flixtown.tv;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional foreground sync. All network work is on an independent single worker, never the player thread. */
final class AccountSync {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"flix-account-sync");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private static AccountSync instance;
    interface Transport { JSONObject post(String url,JSONObject body)throws Exception; }
    private Transport transport=AccountSync::post;
    private final Context app;
    private final SharedPreferences local,journal;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener=this::changed;
    private final Set<String> dirty=new HashSet<>();
    private String owner="",endpoint="",token="",favoriteSnapshot="";
    private String[] account;
    private int generation=0;
    private boolean importing=false,playing=false,busy=false,home=false,repeatRequested=false;
    private long nextAttempt=0,lastPull=0,backoff=60000,tokenUntil=0;
    private Runnable onChanged;
    private final Runnable capture=this::captureDirty;
    private final Runnable heartbeat=()->{cycle(home&&!playing);schedule();};
    private AccountSync(Context c){app=c.getApplicationContext();local=Api.prefs(app);journal=app.getSharedPreferences("account_sync_v1",Context.MODE_PRIVATE);local.registerOnSharedPreferenceChangeListener(listener);}
    static void home(Context c,Runnable callback){
        if(instance==null)instance=new AccountSync(c);
        AccountSync s=instance;s.home=true;s.onChanged=callback;s.configure();s.applyDeferred();s.cycle(true);s.schedule();
    }
    static void leaveHome(){if(instance!=null){instance.home=false;instance.onChanged=null;}}
    static void player(boolean active){if(instance!=null){instance.playing=active;if(!active){instance.captureDirty();instance.cycle(false);}instance.schedule();}}
    static void signOut(){if(instance!=null)instance.reset();}
    private void reset(){generation++;account=null;owner="";endpoint="";token="";tokenUntil=0;dirty.clear();repeatRequested=false;nextAttempt=0;lastPull=0;backoff=60000;journal.edit().clear().apply();MAIN.removeCallbacks(capture);MAIN.removeCallbacks(heartbeat);}
    private boolean current(int g){return generation==g&&account!=null&&owner.equals(local.getString(SignOut.OWNER,""));}
    private void configure(){
        JSONObject cfg=StartupRefresh.config();String url=cfg==null?local.getString("progress_sync_api_url",""):cfg.optString("progress_sync_api_url","").trim();
        if(cfg!=null)local.edit().putString("progress_sync_api_url",ReportsApi.safeUrl(url)?url:"").apply();
        String[] credentials=AccountStore.read(app);
        if(!ReportsApi.safeUrl(url)||credentials==null){account=null;endpoint="";return;}
        String mine=SignOut.ownerId(credentials[0]);if(!mine.equals(local.getString(SignOut.OWNER,"")))return;
        String scope=mine+":"+SignOut.ownerId(local.getString("server","")+"|"+url);
        if(!scope.equals(journal.getString("scope",""))){reset();journal.edit().putString("scope",scope).apply();}
        if(!owner.equals(mine)||!url.equals(endpoint)) {generation++;token="";tokenUntil=0;}
        owner=mine;endpoint=url;account=credentials;favoriteSnapshot=local.getString("favorites","");
        seedLocal();
    }
    private void seedLocal(){
        // Base 0 imports local-only records; an existing server record wins the initial merge.
        if(journal.getBoolean("seeded",false))return;
        Map<String,?> all=local.getAll();for(String pref:all.keySet()){String k=SyncRecord.entity(pref);if(k!=null)dirty.add(k);}
        for(String k:SyncRecord.favorites(local.getString("favorites","")))dirty.add("favorite:"+k);
        SharedPreferences.Editor backup=journal.edit();
        for(String key:dirty)try{backup.putString("initial:"+key,SyncRecord.capture(all,key).toString());}catch(Exception ignored){}
        backup.apply();
        captureDirty();journal.edit().putBoolean("seeded",true).apply();
    }
    private void changed(SharedPreferences p,String key){
        if(importing||account==null||key==null||!owner.equals(p.getString(SignOut.OWNER,"")))return;
        if(key.equals("favorites")){
            String now=p.getString(key,"");for(String k:SyncRecord.favoriteChanges(favoriteSnapshot,now))dirty.add("favorite:"+k);favoriteSnapshot=now;
        }else{String entity=SyncRecord.entity(key);if(entity!=null)dirty.add(entity);}
        if(!dirty.isEmpty()){MAIN.removeCallbacks(capture);MAIN.postDelayed(capture,250);}
    }
    private void captureDirty(){
        if(account==null||dirty.isEmpty())return;
        try{
            Map<String,?> all=local.getAll();SharedPreferences.Editor e=journal.edit();
            for(String k:dirty){JSONObject v;try{v=SyncRecord.capture(all,k);}catch(IllegalArgumentException invalid){continue;}String encoded=v.toString();
                if(encoded.equals(journal.getString("observed:"+k,"")))continue;
                JSONObject op=new JSONObject().put("key",k).put("base",journal.getLong("rev:"+k,0)).put("operation",UUID.randomUUID().toString()).put("value",v);
                e.putString("pending:"+k,op.toString()).putString("observed:"+k,encoded);
            }
            e.apply();dirty.clear();
        }catch(Exception ignored){/* Keep pending local changes for the next opportunity. */}
    }
    private void schedule(){MAIN.removeCallbacks(heartbeat);if(account!=null&&(home||playing))MAIN.postDelayed(heartbeat,60000);}
    private void cycle(boolean pull){
        captureDirty();if(account==null||SystemClock.elapsedRealtime()<nextAttempt)return;
        if(busy){repeatRequested=true;return;}
        if(pull&&SystemClock.elapsedRealtime()-lastPull<30000)pull=false;
        JSONArray operations=new JSONArray();
        for(Map.Entry<String,?> e:journal.getAll().entrySet())if(e.getKey().startsWith("pending:")&&operations.length()<40){try{operations.put(new JSONObject((String)e.getValue()));}catch(Exception ignored){}}
        if(operations.length()==0&&!pull)return;
        busy=true;final int g=generation;final String url=endpoint;final String[] credentials=account.clone();
        final String existing=token;final long until=tokenUntil;final boolean read=pull&&!playing;
        final long after=journal.getLong("cursor",0);
        IO.execute(()->{
            try{
                String session=existing;long expiry=until;
                if(session.isEmpty()||SystemClock.elapsedRealtime()>=expiry){
                    JSONObject auth=transport.post(url,new JSONObject().put("action","session").put("username",credentials[0]).put("password",credentials[1]));
                    session=auth.optString("session","");if(!auth.optBoolean("ok")||session.isEmpty()||session.length()>512)throw new IOException();
                    expiry=SystemClock.elapsedRealtime()+Math.min(840,Math.max(1,auth.optInt("ttl",900)-60))*1000L;
                }
                JSONObject reply=transport.post(url,new JSONObject().put("action","exchange").put("session",session).put("after",after).put("pull",read).put("operations",operations));
                final String saved=session;final long exp=expiry;
                MAIN.post(()->{busy=false;if(!current(g))return;
                    if(!reply.optBoolean("ok")){if("reset".equals(reply.optString("error")))resetRevisions();failed();return;}
                    token=saved;tokenUntil=exp;nextAttempt=0;backoff=60000;if(read)lastPull=SystemClock.elapsedRealtime();
                    receive(reply,operations,read);if(reply.optBoolean("more"))page(g,url,saved,reply.optLong("after"),reply.optLong("through"),read,1);else finishExchange();
                });
            }catch(Exception ignored){MAIN.post(()->{busy=false;if(current(g))failed();});}
            finally{Arrays.fill(credentials,"");}
        });
    }
    private void finishExchange(){if(repeatRequested){repeatRequested=false;cycle(home&&!playing);}}
    private void resetRevisions(){
        SharedPreferences.Editor e=journal.edit().putLong("cursor",0);
        for(Map.Entry<String,?> item:journal.getAll().entrySet()){
            String k=item.getKey();if(k.startsWith("rev:")||k.startsWith("remote:"))e.remove(k);
            if(k.startsWith("pending:"))try{e.putString(k,new JSONObject((String)item.getValue()).put("base",0).toString());}catch(Exception ignored){}
        }
        e.apply();
    }
    private void failed(){token="";tokenUntil=0;nextAttempt=SystemClock.elapsedRealtime()+backoff;backoff=Math.min(900000,backoff*2);}
    private void page(int g,String url,String session,long after,long through,boolean read,int count){
        if(count>=100||!current(g))return;if(playing){finishExchange();return;}busy=true;
        IO.execute(()->{try{
            JSONObject r=transport.post(url,new JSONObject().put("action","exchange").put("session",session).put("after",after).put("through",through));
            MAIN.post(()->{busy=false;if(!current(g))return;if(!r.optBoolean("ok")){failed();return;}receive(r,new JSONArray(),read);if(r.optBoolean("more"))page(g,url,session,r.optLong("after"),through,read,count+1);else finishExchange();});
        }catch(Exception ignored){MAIN.post(()->{busy=false;if(current(g))failed();});}});
    }
    private void receive(JSONObject reply,JSONArray sent,boolean read){
        try{
            captureDirty();SharedPreferences.Editor e=journal.edit();JSONArray acks=reply.optJSONArray("acks");
            if(acks!=null)for(int i=0;i<acks.length();i++){
                JSONObject a=acks.getJSONObject(i),r=a.getJSONObject("record");String k=r.getString("key");if(!SyncRecord.valid(k))throw new IOException();
                long rev=r.getLong("revision");if(rev<journal.getLong("rev:"+k,0))continue;
                String pending=journal.getString("pending:"+k,"");JSONObject op=pending.isEmpty()?null:new JSONObject(pending);
                e.putLong("rev:"+k,rev);
                if(a.optBoolean("accepted"))e.remove("remote:"+k);
                if(op!=null&&SyncRecord.matchesOperation(op.optString("operation"),a.optString("operation"))){
                    e.remove("pending:"+k);
                    if(!a.optBoolean("accepted"))e.putString("remote:"+k,r.toString());
                }else if(op!=null)e.putString("pending:"+k,op.put("base",rev).toString());
            }
            e.apply();
            JSONArray records=reply.optJSONArray("records");e=journal.edit();
            if(records!=null)for(int i=0;i<records.length();i++){
                JSONObject r=records.getJSONObject(i);String k=r.getString("key");long rev=r.getLong("revision");
                if(!SyncRecord.valid(k)||rev<=0)throw new IOException();
                if(!journal.contains("pending:"+k)&&rev>journal.getLong("rev:"+k,0))e.putLong("rev:"+k,rev).putString("remote:"+k,r.toString());
            }
            e.putLong("cursor",reply.getLong("after")).apply();
            if(home&&!playing)applyDeferred();
        }catch(Exception ignored){failed();}
    }
    private void applyDeferred(){
        if(!home||playing||account==null||!dirty.isEmpty())return;
        boolean changed=false;importing=true;
        try{
            List<Map.Entry<String,?>> remote=new ArrayList<>();
            for(Map.Entry<String,?> entry:journal.getAll().entrySet())if(entry.getKey().startsWith("remote:"))remote.add(entry);
            Collections.sort(remote,(a,b)->{try{return Long.compare(new JSONObject((String)a.getValue()).getLong("revision"),new JSONObject((String)b.getValue()).getLong("revision"));}catch(Exception ignored){return 0;}});
            for(Map.Entry<String,?> entry:remote){
                String k=entry.getKey().substring(7);if(journal.contains("pending:"+k))continue;
                JSONObject r=new JSONObject((String)entry.getValue()),v=r.getJSONObject("value");
                String media=k.substring(k.indexOf(':')+1);boolean present=v.getBoolean("present");SharedPreferences.Editor edit=local.edit();
                if(k.startsWith("favorite:")){
                    Set<String> favorites=SyncRecord.favorites(local.getString("favorites",""));if(present)favorites.add(media);else favorites.remove(media);
                    StringBuilder b=new StringBuilder();for(String f:favorites)b.append('|').append(f).append('|');edit.putString("favorites",b.toString());
                }else if(k.startsWith("watched:"))edit.putBoolean("watched_"+media,present);
                else {
                    for(String field:SyncRecord.RESUME)edit.remove("resume_"+field+"_"+media);
                    edit.remove("resume_art_"+media); // Never import remote artwork URLs.
                    if(present){
                        edit.putLong("resume_position_"+media,v.getLong("position")).putLong("resume_duration_"+media,v.getLong("duration"))
                            .putString("resume_episode_"+media,v.getString("episode")).putString("resume_extension_"+media,v.getString("extension"))
                            .putString("resume_next_"+media,v.getString("next")).putString("resume_next_ext_"+media,v.getString("next_ext"))
                            .putString("resume_label_"+media,v.getString("label")).putBoolean("resume_done_"+media,v.getBoolean("done"));
                    }
                    LinkedHashSet<String> ids=new LinkedHashSet<>();if(present)ids.add(media);
                    for(String id:local.getString("continue_ids","").split(","))if(!id.isEmpty()&&!id.equals(media))ids.add(id);
                    StringBuilder b=new StringBuilder();for(String id:ids){if(b.length()>500)break;if(b.length()>0)b.append(',');b.append(id);}edit.putString("continue_ids",b.toString());
                }
                edit.apply();favoriteSnapshot=local.getString("favorites","");
                journal.edit().remove(entry.getKey()).putString("observed:"+k,SyncRecord.capture(local.getAll(),k).toString()).apply();changed=true;
            }
        }catch(Exception ignored){/* Malformed remote data never interrupts startup or playback. */}
        finally{importing=false;}
        if(changed&&onChanged!=null)onChanged.run();
    }
    // In-process instrumentation fixtures only. Unreachable in Preview/production (DEMO is false).
    static AccountSync fixture(Context c,Transport transport){
        if(!BuildConfig.DEMO)throw new IllegalStateException();
        AccountSync s=new AccountSync(c);s.transport=transport;s.owner=SignOut.ownerId("fixture");
        s.local.edit().putString(SignOut.OWNER,s.owner).apply();s.account=new String[]{"fixture","fixture-password"};
        s.endpoint="https://fixture.invalid/sync";s.home=true;s.favoriteSnapshot=s.local.getString("favorites","");s.seedLocal();return s;
    }
    void fixtureTick(boolean playing){if(!BuildConfig.DEMO)throw new IllegalStateException();this.playing=playing;lastPull=0;nextAttempt=0;cycle(true);}
    void fixtureClose(){if(!BuildConfig.DEMO)throw new IllegalStateException();reset();local.unregisterOnSharedPreferenceChangeListener(listener);}
    private static JSONObject post(String url,JSONObject body)throws Exception{
        if(!ReportsApi.safeUrl(url))throw new IOException();
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);c.setUseCaches(false);
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
        byte[] bytes=body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
        long deadline=SystemClock.elapsedRealtime()+10000;
        try{
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();if(stream==null)throw new IOException();
            try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>262144||SystemClock.elapsedRealtime()>deadline)throw new IOException();}
                JSONObject r=new JSONObject(out.toString("UTF-8"));if(status<200||status>=300)r.put("ok",false);return r;
            }
        }finally{c.disconnect();}
    }
}
