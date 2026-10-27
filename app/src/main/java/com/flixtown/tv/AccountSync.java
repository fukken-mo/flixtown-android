package com.flixtown.tv;

import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Optional foreground sync of resume points, watched flags and My List.
 *
 * Threads: the main thread only notes which records changed and commits prepared imports in small
 * slices. Reading the whole progress store, building records and every journal write run on WORK
 * (one low-priority thread, the journal's only writer); HTTP runs on NET. While video plays, saves
 * are not captured one by one: they are collected once a minute and when playback stops.
 */
final class AccountSync {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK=single("flix-account-sync"),NET=single("flix-account-sync-net");
    private static ExecutorService single(String name){return Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,name);t.setPriority(Thread.MIN_PRIORITY);return t;});}
    /** Records per upload (small while video plays), records per main-thread import slice, quick catch-up rounds at Home. */
    static final int PLAYING_BATCH=40,IDLE_BATCH=100,IMPORT_SLICE=200,CATCH_UP=8;
    /** Longest main-thread import slice in this process, for the QA device tests. */
    static volatile long maxMainSliceMs;
    private static AccountSync instance;
    interface Transport { JSONObject post(String url,JSONObject body)throws Exception; }
    private Transport transport=AccountSync::post;
    private final Context app;
    private final SharedPreferences local,journal,initial;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener=this::changed;
    private final Set<String> dirty=ConcurrentHashMap.newKeySet();
    // Main-thread state; WORK and NET only compare the generation they were started with.
    private volatile int generation=0;
    private volatile boolean home=false,playing=false;
    /** Set on WORK when a restore repair queued uploads: sent in the next round, not a minute later. */
    private volatile boolean resend=false;
    private String owner="",endpoint="",token="",favoriteSnapshot="",scope="";
    private String[] account;
    private boolean importing=false,busy=false,repeatRequested=false,importScheduled=false;
    private long nextAttempt=0,lastPull=0,backoff=60000,tokenUntil=0;
    private int catchUp=0;
    private Runnable onChanged;
    private final Runnable capture=this::captureDirty;
    private final Runnable heartbeat=()->{cycle(home&&!playing);schedule();};
    private AccountSync(Context c){app=c.getApplicationContext();local=Api.prefs(app);
        journal=app.getSharedPreferences("account_sync_v1",Context.MODE_PRIVATE);initial=app.getSharedPreferences("account_sync_initial_v1",Context.MODE_PRIVATE);
        local.registerOnSharedPreferenceChangeListener(listener);}
    static void home(Context c,Runnable callback){
        if(instance==null)instance=new AccountSync(c);
        AccountSync s=instance;s.home=true;s.onChanged=callback;s.configure();s.applyDeferred();s.cycle(true);s.schedule();
    }
    static void leaveHome(){if(instance!=null){instance.home=false;instance.onChanged=null;}}
    /** Stopping playback collects and uploads its saves and, back at Home, applies changes that waited. */
    static void player(boolean active){
        if(instance==null)return;AccountSync s=instance;s.playing=active;
        if(active)MAIN.removeCallbacks(s.capture);else{s.cycle(s.home);s.applyDeferred();}
        s.schedule();
    }
    static void signOut(){if(instance!=null)instance.reset(null);}
    /** Forgets this TV's sync state (not its local progress). {@code newScope}: the account/server the journal now belongs to. */
    private void reset(String newScope){
        generation++;account=null;owner="";endpoint="";token="";tokenUntil=0;dirty.clear();busy=false;repeatRequested=false;importScheduled=false;
        nextAttempt=0;lastPull=0;backoff=60000;catchUp=0;scope=newScope==null?"":newScope;MAIN.removeCallbacks(capture);MAIN.removeCallbacks(heartbeat);
        WORK.execute(()->{journal.edit().clear().commit();initial.edit().clear().commit();if(newScope!=null)journal.edit().putString("scope",newScope).commit();});
    }
    private void configure(){
        JSONObject cfg=StartupRefresh.config();String url=cfg==null?local.getString("progress_sync_api_url",""):cfg.optString("progress_sync_api_url","").trim();
        if(cfg!=null){String keep=ReportsApi.safeUrl(url)?url:"";if(!keep.equals(local.getString("progress_sync_api_url","")))local.edit().putString("progress_sync_api_url",keep).apply();}
        String[] credentials=AccountStore.read(app);
        if(!ReportsApi.safeUrl(url)||credentials==null){account=null;endpoint="";return;}
        String mine=SignOut.ownerId(credentials[0]);if(!mine.equals(local.getString(SignOut.OWNER,"")))return;
        String wanted=mine+":"+SignOut.ownerId(local.getString("server","")+"|"+url);
        if(!wanted.equals(scope)&&!wanted.equals(journal.getString("scope","")))reset(wanted);
        scope=wanted;
        if(!owner.equals(mine)||!url.equals(endpoint)){generation++;token="";tokenUntil=0;busy=false;}
        boolean reconnect=account==null;
        owner=mine;endpoint=url;account=credentials;favoriteSnapshot=local.getString("favorites","");
        if(reconnect){final int g=generation;WORK.execute(()->attach(g));}
    }
    /** WORK: first connection in this process. Imports the existing history once per account and finds saves a killed process missed. */
    private void attach(int g){
        if(g!=generation)return;
        Map<String,?> all=local.getAll();Set<String> keys=localKeys(all);
        if(!journal.getBoolean("seeded",false)){
            // Base 0 uploads local-only records; an existing server record wins the initial merge.
            SharedPreferences.Editor backup=initial.edit();
            for(String key:keys)try{backup.putString(key,SyncRecord.capture(all,key).toString());}catch(Exception ignored){}
            backup.commit();journal.edit().putBoolean("seeded",true).commit();
        }
        for(String k:journal.getAll().keySet())if(k.startsWith("observed:")&&SyncRecord.valid(k.substring(9)))keys.add(k.substring(9));
        writePending(g,keys,all,Collections.<String>emptySet());
    }
    private static Set<String> localKeys(Map<String,?> all){
        Set<String> keys=new HashSet<>();
        for(String pref:all.keySet()){String k=SyncRecord.entity(pref);if(k!=null)keys.add(k);}
        Object favorites=all.get("favorites");
        if(favorites instanceof String)for(String k:SyncRecord.favorites((String)favorites))keys.add("favorite:"+k);
        return keys;
    }
    private void changed(SharedPreferences p,String key){
        if(importing||account==null||key==null||!owner.equals(p.getString(SignOut.OWNER,"")))return;
        if(key.equals("favorites")){
            String now=p.getString(key,"");for(String k:SyncRecord.favoriteChanges(favoriteSnapshot,now))dirty.add("favorite:"+k);favoriteSnapshot=now;
        }else{String entity=SyncRecord.entity(key);if(entity!=null)dirty.add(entity);}
        // While video plays, saves are collected by the heartbeat and when playback stops, not every few seconds.
        if(!dirty.isEmpty()&&!playing){MAIN.removeCallbacks(capture);MAIN.postDelayed(capture,250);}
    }
    /** Main: hands the changed record names to WORK, which reads the store and writes the journal. */
    private void captureDirty(){
        MAIN.removeCallbacks(capture);
        if(account==null||dirty.isEmpty())return;
        final List<String> keys=new ArrayList<>(dirty);dirty.removeAll(keys);final int g=generation;
        WORK.execute(()->writePending(g,keys,local.getAll(),Collections.<String>emptySet()));
    }
    /** WORK: one journal write for all changed records. {@code force}: upload even if unchanged since last seen (restore repair). */
    private void writePending(int g,Collection<String> keys,Map<String,?> all,Set<String> force){
        if(g!=generation||keys.isEmpty())return;
        SharedPreferences.Editor e=journal.edit();boolean any=false;
        for(String k:keys){
            JSONObject v;try{v=SyncRecord.capture(all,k);}catch(Exception invalid){continue;}
            String encoded=v.toString();
            if(!force.contains(k)&&encoded.equals(journal.getString("observed:"+k,"")))continue;
            try{e.putString("pending:"+k,new JSONObject().put("key",k).put("base",journal.getLong("rev:"+k,0)).put("operation",UUID.randomUUID().toString()).put("value",v).toString());}
            catch(JSONException ignored){continue;}
            e.putString("observed:"+k,encoded);any=true;
        }
        if(any)e.commit();
    }
    private void schedule(){MAIN.removeCallbacks(heartbeat);if(account!=null&&(home||playing))MAIN.postDelayed(heartbeat,60000);}
    private void cycle(boolean pull){
        captureDirty();if(account==null||SystemClock.elapsedRealtime()<nextAttempt)return;
        if(busy){repeatRequested=true;return;}
        if(pull&&SystemClock.elapsedRealtime()-lastPull<30000)pull=false;
        busy=true;final int g=generation;final String url=endpoint;final String[] credentials=account.clone();
        final String existing=token;final long until=tokenUntil;final boolean read=pull&&!playing;final int limit=playing?PLAYING_BATCH:IDLE_BATCH;
        WORK.execute(()->{
            if(g!=generation){Arrays.fill(credentials,"");return;}
            // After a restore is detected, nothing is uploaded until the history has been re-read (see receive).
            boolean restoring=journal.getBoolean("restoring",false);JSONArray operations=new JSONArray();
            if(!restoring)for(Map.Entry<String,?> e:journal.getAll().entrySet())if(e.getKey().startsWith("pending:")&&operations.length()<limit){try{operations.put(new JSONObject((String)e.getValue()));}catch(Exception ignored){}}
            final long after=journal.getLong("cursor",0);final String anchor=journal.getString("anchor","");
            if(operations.length()==0&&!read){Arrays.fill(credentials,"");MAIN.post(()->{if(g==generation){busy=false;finishExchange();}});return;}
            NET.execute(()->exchange(g,url,credentials,existing,until,after,anchor,read,operations,limit));
        });
    }
    /** NET: the request; the reply is processed on WORK and finished on the main thread. */
    private void exchange(int g,String url,String[] credentials,String existing,long until,long after,String anchor,boolean read,JSONArray operations,int limit){
        try{
            String session=existing;long expiry=until;
            if(session.isEmpty()||SystemClock.elapsedRealtime()>=expiry){
                JSONObject auth=transport.post(url,new JSONObject().put("action","session").put("username",credentials[0]).put("password",credentials[1]));
                session=auth.optString("session","");if(!auth.optBoolean("ok")||session.isEmpty()||session.length()>512)throw new IOException();
                expiry=SystemClock.elapsedRealtime()+Math.min(840,Math.max(1,auth.optInt("ttl",900)-60))*1000L;
            }
            JSONObject body=new JSONObject().put("action","exchange").put("session",session).put("after",after).put("pull",read).put("operations",operations);
            if(!anchor.isEmpty())body.put("anchor",anchor);
            JSONObject reply=transport.post(url,body);
            final String saved=session;final long exp=expiry;
            WORK.execute(()->{
                if(g!=generation)return;
                if(!reply.optBoolean("ok")){if("reset".equals(reply.optString("error"))){beginRestore();MAIN.post(()->restart(g));}else MAIN.post(()->failed(g));return;}
                boolean ok=receive(reply,read);
                MAIN.post(()->{
                    if(g!=generation)return;if(!ok){failed(g);return;}
                    token=saved;tokenUntil=exp;nextAttempt=0;backoff=60000;if(read)lastPull=SystemClock.elapsedRealtime();
                    if(read&&reply.optBoolean("more")){page(g,url,saved,reply.optLong("after"),reply.optLong("through"),1);return;}
                    busy=false;
                    // A full batch at Home means more history is waiting: a few quick rounds instead of one per minute.
                    catchUp=operations.length()>=limit&&!playing&&catchUp<CATCH_UP?catchUp+1:0;if(catchUp>0)repeatRequested=true;
                    if(resend){resend=false;repeatRequested=true;}
                    finishExchange();applyDeferred();
                });
            });
        }catch(Exception ignored){MAIN.post(()->failed(g));}
        finally{Arrays.fill(credentials,"");}
    }
    private void page(int g,String url,String session,long after,long through,int count){
        if(g!=generation)return;
        if(count>=100||playing){busy=false;finishExchange();return;}
        WORK.execute(()->{
            if(g!=generation)return;final String anchor=journal.getString("anchor","");
            NET.execute(()->{
                try{
                    JSONObject body=new JSONObject().put("action","exchange").put("session",session).put("after",after).put("through",through);
                    if(!anchor.isEmpty())body.put("anchor",anchor);
                    JSONObject r=transport.post(url,body);
                    WORK.execute(()->{
                        if(g!=generation)return;
                        if(!r.optBoolean("ok")){if("reset".equals(r.optString("error"))){beginRestore();MAIN.post(()->restart(g));}else MAIN.post(()->failed(g));return;}
                        boolean ok=receive(r,true);
                        MAIN.post(()->{if(g!=generation)return;if(!ok){failed(g);return;}
                            if(r.optBoolean("more"))page(g,url,session,r.optLong("after"),through,count+1);
                            else{busy=false;if(resend){resend=false;repeatRequested=true;}finishExchange();applyDeferred();}});
                    });
                }catch(Exception ignored){MAIN.post(()->failed(g));}
            });
        });
    }
    private void finishExchange(){if(repeatRequested){repeatRequested=false;cycle(home&&!playing);}}
    /** After a detected restore the cursor is 0, so the next exchange cannot be refused again: re-read at once. */
    private void restart(int g){if(g!=generation)return;busy=false;lastPull=0;cycle(home&&!playing);}
    private void failed(int g){if(g!=generation)return;busy=false;token="";tokenUntil=0;nextAttempt=SystemClock.elapsedRealtime()+backoff;backoff=Math.min(900000,backoff*2);}
    /**
     * WORK: the server's history went back (restored from a backup). Everything is read again; for each
     * record, this TV's copy is uploaded again where the server's is older than the one this TV had seen,
     * and records the server lost are uploaded again. Newer records from other TVs are imported as usual.
     */
    private void beginRestore(){
        SharedPreferences.Editor e=journal.edit().putLong("cursor",0).remove("anchor").putBoolean("restoring",true);
        for(Map.Entry<String,?> item:journal.getAll().entrySet()){
            String k=item.getKey();
            if(k.startsWith("rev:")){e.remove(k);if(item.getValue() instanceof Long)e.putLong("known:"+k.substring(4),(Long)item.getValue());}
            else if(k.startsWith("remote:"))e.remove(k);
        }
        e.commit();
    }
    /** WORK: acknowledgements and records of one reply. False: malformed reply (the cursor does not move). */
    private boolean receive(JSONObject reply,boolean read){
        try{
            SharedPreferences.Editor e=journal.edit();JSONArray acks=reply.optJSONArray("acks");
            if(acks!=null)for(int i=0;i<acks.length();i++){
                JSONObject a=acks.getJSONObject(i),r=a.optJSONObject("record");String k=r!=null?r.getString("key"):a.optString("key","");
                String pending=SyncRecord.valid(k)?journal.getString("pending:"+k,""):"";JSONObject op=pending.isEmpty()?null:new JSONObject(pending);
                boolean mine=op!=null&&SyncRecord.matchesOperation(op.optString("operation"),a.optString("operation"));
                if(r==null){if(mine)e.remove("pending:"+k);continue;}   // refused (invalid, account full): local data stays, this version is not resent
                if(!SyncRecord.valid(k))throw new IOException();
                long rev=r.getLong("revision");boolean accepted=a.optBoolean("accepted");
                if(!accepted&&rev==0){if(mine)e.putString("pending:"+k,op.put("base",0).toString());e.remove("rev:"+k);continue;}   // the server lost it: send again
                if(rev<journal.getLong("rev:"+k,0))continue;
                e.putLong("rev:"+k,rev);
                if(accepted)e.remove("remote:"+k);
                if(mine){e.remove("pending:"+k);if(!accepted)e.putString("remote:"+k,r.toString());}
                else if(op!=null)e.putString("pending:"+k,op.put("base",rev).toString());   // edited while in flight: rebased, sent next
            }
            e.commit();
            JSONArray records=reply.optJSONArray("records");e=journal.edit();
            boolean restoring=journal.getBoolean("restoring",false);Set<String> force=new HashSet<>(),seen=new HashSet<>();String last="";long lastRev=-1;
            if(records!=null)for(int i=0;i<records.length();i++){
                JSONObject r=records.getJSONObject(i);String k=r.getString("key");long rev=r.getLong("revision");
                if(!SyncRecord.valid(k)||rev<=0)throw new IOException();
                if(rev>lastRev){lastRev=rev;last=k;}seen.add(k);
                long known=restoring?journal.getLong("known:"+k,0):0;
                if(known>0){
                    e.remove("known:"+k);
                    if(rev<known){e.putLong("rev:"+k,rev);force.add(k);continue;}   // the server's copy is older than this TV's: upload this TV's again
                    if(rev==known){e.putLong("rev:"+k,rev);continue;}
                }
                String pending=journal.getString("pending:"+k,"");
                if(!pending.isEmpty()){if(restoring)e.putLong("rev:"+k,rev).putString("pending:"+k,new JSONObject(pending).put("base",rev).toString());continue;}
                if(rev>journal.getLong("rev:"+k,0))e.putLong("rev:"+k,rev).putString("remote:"+k,r.toString());
            }
            long after=reply.getLong("after");e.putLong("cursor",after);
            // The newest record read is this TV's anchor: if the server later no longer has it at this revision, it was restored.
            if(read&&lastRev>0){if(lastRev==after)e.putString("anchor",last);else e.remove("anchor");}
            if(restoring&&read&&!reply.optBoolean("more")){
                // The journal does not include this page's edits yet: records on this page were handled above.
                for(Map.Entry<String,?> item:journal.getAll().entrySet()){
                    String k=item.getKey();if(seen.contains(k.substring(k.indexOf(':')+1)))continue;
                    if(k.startsWith("known:")){String key=k.substring(6);e.remove(k);if(SyncRecord.valid(key)){e.remove("rev:"+key);force.add(key);}}   // the server lost it
                    else if(k.startsWith("pending:")&&!journal.contains("rev:"+k.substring(8)))e.putString(k,new JSONObject((String)item.getValue()).put("base",0).toString());
                }
                e.putBoolean("restoring",false);
            }
            e.commit();
            if(!force.isEmpty()){writePending(generation,force,local.getAll(),force);resend=true;}
            return true;
        }catch(Exception ignored){return false;}
    }
    private static final class Import {final String key;final JSONObject value;final long revision;Import(String k,JSONObject v,long r){key=k;value=v;revision=r;}}
    /** Main: changes from other TVs, only at Home and never while video plays. Parsed and checked on WORK, written here in slices. */
    private void applyDeferred(){
        if(!home||playing||account==null||importScheduled)return;
        importScheduled=true;final int g=generation;
        WORK.execute(()->{
            List<Import> list=new ArrayList<>();List<String> drop=new ArrayList<>();
            if(g==generation)for(Map.Entry<String,?> entry:journal.getAll().entrySet())if(entry.getKey().startsWith("remote:")){
                String k=entry.getKey().substring(7);if(journal.contains("pending:"+k))continue;
                try{JSONObject r=new JSONObject((String)entry.getValue()),v=r.getJSONObject("value");
                    if(SyncRecord.validRemote(k,v))list.add(new Import(k,v,r.getLong("revision")));else drop.add(entry.getKey());}
                catch(Exception malformed){drop.add(entry.getKey());}
            }
            if(!drop.isEmpty()){SharedPreferences.Editor e=journal.edit();for(String d:drop)e.remove(d);e.commit();}   // never written to this TV
            Collections.sort(list,(a,b)->Long.compare(a.revision,b.revision));
            MAIN.post(()->commitImports(g,list,0,false));
        });
    }
    private void commitImports(int g,List<Import> list,int from,boolean changedBefore){
        if(g!=generation||!home||playing||account==null){importScheduled=false;return;}   // left in the journal for later
        long start=SystemClock.uptimeMillis();final int end=Math.min(list.size(),from+IMPORT_SLICE);final List<String> done=new ArrayList<>();
        if(from<end){
            Set<String> favorites=SyncRecord.favorites(local.getString("favorites",""));
            List<String> ids=new ArrayList<>();for(String id:local.getString("continue_ids","").split(","))if(!id.isEmpty()&&!ids.contains(id))ids.add(id);
            boolean favoritesTouched=false,idsTouched=false;SharedPreferences.Editor edit=local.edit();
            for(int i=from;i<end;i++){
                Import m=list.get(i);String k=m.key;if(dirty.contains(k)||journal.contains("pending:"+k))continue;   // a newer change on this TV wins
                String media=k.substring(k.indexOf(':')+1);boolean present=m.value.optBoolean("present");
                if(k.startsWith("favorite:")){if(present)favorites.add(media);else favorites.remove(media);favoritesTouched=true;}
                else if(k.startsWith("watched:"))edit.putBoolean("watched_"+media,present);
                else{
                    for(String field:SyncRecord.RESUME)edit.remove("resume_"+field+"_"+media);
                    edit.remove("resume_art_"+media);   // never import artwork addresses
                    JSONObject v=m.value;
                    if(present)edit.putLong("resume_position_"+media,v.optLong("position")).putLong("resume_duration_"+media,v.optLong("duration"))
                        .putString("resume_episode_"+media,v.optString("episode")).putString("resume_extension_"+media,v.optString("extension"))
                        .putString("resume_next_"+media,v.optString("next")).putString("resume_next_ext_"+media,v.optString("next_ext"))
                        .putString("resume_label_"+media,v.optString("label")).putBoolean("resume_done_"+media,v.optBoolean("done"));
                    ids.remove(media);if(present)ids.add(0,media);idsTouched=true;   // ascending revisions: the newest ends up first
                }
                done.add(k);
            }
            if(favoritesTouched){StringBuilder b=new StringBuilder();for(String f:favorites)b.append('|').append(f).append('|');edit.putString("favorites",b.toString());}
            if(idsTouched){StringBuilder b=new StringBuilder();for(String id:ids){if(b.length()>500)break;if(b.length()>0)b.append(',');b.append(id);}edit.putString("continue_ids",b.toString());}
            if(!done.isEmpty()){importing=true;try{edit.apply();}finally{importing=false;}favoriteSnapshot=local.getString("favorites","");}
        }
        maxMainSliceMs=Math.max(maxMainSliceMs,SystemClock.uptimeMillis()-start);
        if(!done.isEmpty())WORK.execute(()->{
            if(g!=generation)return;Map<String,?> all=local.getAll();SharedPreferences.Editor e=journal.edit();
            for(String k:done){e.remove("remote:"+k);try{e.putString("observed:"+k,SyncRecord.capture(all,k).toString());}catch(Exception ignored){}}
            e.commit();
        });
        final boolean changed=changedBefore||!done.isEmpty();
        if(end<list.size()){MAIN.post(()->commitImports(g,list,end,changed));return;}
        importScheduled=false;
        if(changed&&onChanged!=null)onChanged.run();
    }
    // In-process instrumentation fixtures only. Unreachable in Preview/production (DEMO is false).
    static AccountSync fixture(Context c,Transport transport){return fixture(c,transport,"fixture");}
    static AccountSync fixture(Context c,Transport transport,String user){
        if(!BuildConfig.DEMO)throw new IllegalStateException();
        AccountSync s=new AccountSync(c);s.transport=transport;s.owner=SignOut.ownerId(user);
        s.local.edit().putString(SignOut.OWNER,s.owner).apply();s.account=new String[]{user,"fixture-password"};
        s.endpoint="https://fixture.invalid/sync";s.home=true;s.favoriteSnapshot=s.local.getString("favorites","");
        instance=s;final int g=s.generation;WORK.execute(()->s.attach(g));return s;
    }
    /** Waits until the sync worker has finished everything queued so far (startup scan, captures, journal writes). */
    static void fixtureAwaitWork()throws Exception{if(!BuildConfig.DEMO)throw new IllegalStateException();WORK.submit(()->{}).get(10,TimeUnit.SECONDS);}
    void fixtureTick(boolean playing){if(!BuildConfig.DEMO)throw new IllegalStateException();this.playing=playing;lastPull=0;nextAttempt=0;cycle(true);}
    void fixtureDetach(){if(!BuildConfig.DEMO)throw new IllegalStateException();generation++;account=null;MAIN.removeCallbacks(capture);local.unregisterOnSharedPreferenceChangeListener(listener);if(instance==this)instance=null;}
    void fixtureClose(){if(!BuildConfig.DEMO)throw new IllegalStateException();reset(null);local.unregisterOnSharedPreferenceChangeListener(listener);if(instance==this)instance=null;}
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
