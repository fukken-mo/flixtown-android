package com.flixtown.tv;

import android.content.*;
import android.os.*;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Independent TV stores share only the fake server. No live account, media or endpoint is used. */
public class AccountSyncDeviceTest extends InstrumentationTestCase {
 private final List<AccountSync> clients=new ArrayList<>();
 private Context context(String name){return new ContextWrapper(getInstrumentation().getTargetContext()){
  @Override public Context getApplicationContext(){return this;}
  @Override public SharedPreferences getSharedPreferences(String n,int m){return super.getSharedPreferences("sync_test_"+name+"_"+n,m);}
 };}
 private void main(Runnable r){getInstrumentation().runOnMainSync(r);}
 private static SharedPreferences journal(Context c){return c.getSharedPreferences("account_sync_v1",0);}
 interface Fill { void fill(SharedPreferences.Editor e); }
 private AccountSync client(Context c,Fake f){return client(c,f,"fixture",null);}
 /** A TV with an empty sync journal; {@code fill} is the history already on it before sync starts. */
 private AccountSync client(Context c,Fake f,String user,Fill fill){final AccountSync[] out=new AccountSync[1];main(()->{
  SharedPreferences.Editor e=Api.prefs(c).edit().clear();if(fill!=null)fill.fill(e);e.commit();
  journal(c).edit().clear().commit();c.getSharedPreferences("account_sync_initial_v1",0).edit().clear().commit();
  out[0]=AccountSync.fixture(c,f,user);clients.add(out[0]);});return out[0];}
 private void tick(AccountSync c,boolean playing){main(()->c.fixtureTick(playing));}
 private void waitFor(Callable<Boolean> predicate)throws Exception {waitFor(predicate,8000);}
 private void waitFor(Callable<Boolean> predicate,long ms)throws Exception {long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(predicate.call())return;Thread.sleep(50);}fail("sync timed out");}
 @Override protected void tearDown()throws Exception {main(()->{for(AccountSync c:clients)c.fixtureClose();});super.tearDown();}
 /** Measures how late a main-thread task runs while sync works: any long main-thread step shows up as a stall. */
 static final class Stalls extends Thread {
  final Handler main=new Handler(Looper.getMainLooper());volatile boolean done;volatile long max;
  @Override public void run(){while(!done){final long posted=SystemClock.uptimeMillis();final CountDownLatch ran=new CountDownLatch(1);
   main.post(()->{max=Math.max(max,SystemClock.uptimeMillis()-posted);ran.countDown();});
   try{ran.await(10,TimeUnit.SECONDS);Thread.sleep(16);}catch(InterruptedException e){return;}}}
  long finish()throws InterruptedException{done=true;join();return max;}
 }
 /** The server's behaviour: per-account records, compare-and-swap, 100-record pages, revisions never reused, restore detection. */
 static class Fake implements AccountSync.Transport {
  final Map<String,Map<String,JSONObject>> accounts=new HashMap<>();final Map<String,Long> heads=new HashMap<>();long clock=0;
  Map<String,Map<String,JSONObject>> savedAccounts;Map<String,Long> savedHeads;
  volatile int calls=0;volatile boolean fail=false;volatile CountDownLatch entered,release;
  final Set<String> refuse=ConcurrentHashMap.newKeySet();final List<String> uploads=Collections.synchronizedList(new ArrayList<>());
  private Map<String,JSONObject> rows(String u){Map<String,JSONObject> r=accounts.get(u);if(r==null){r=new LinkedHashMap<>();accounts.put(u,r);}return r;}
  @Override public JSONObject post(String url,JSONObject body)throws Exception{
   if(Looper.myLooper()==Looper.getMainLooper())throw new AssertionError("network on main");
   calls++;if(fail)throw new java.io.IOException();
   if(body.optString("action").equals("session"))return new JSONObject().put("ok",true).put("session","token:"+body.getString("username")).put("ttl",900);
   CountDownLatch e=entered,r=release;if(e!=null){entered=null;e.countDown();if(!r.await(5,TimeUnit.SECONDS))throw new java.io.IOException();}
   synchronized(this){
    String user=body.getString("session").substring(6);Map<String,JSONObject> rows=rows(user);long head=heads.containsKey(user)?heads.get(user):0;
    long after=body.optLong("after"),through=body.optLong("through",0);String anchor=body.optString("anchor","");
    JSONObject reset=new JSONObject().put("ok",false).put("error","reset");
    if(after>head||through>head)return reset;
    if(after>0&&!anchor.isEmpty()){JSONObject a=rows.get(anchor);if(a==null||a.getLong("revision")<after)return reset;}
    JSONArray acks=new JSONArray(),ops=body.optJSONArray("operations");
    if(ops!=null)for(int i=0;i<ops.length();i++){
     JSONObject op=ops.getJSONObject(i);String k=op.getString("key");
     if(refuse.contains(k)){acks.put(new JSONObject().put("operation",op.getString("operation")).put("key",k).put("accepted",false).put("error","invalid").put("record",JSONObject.NULL));continue;}
     JSONObject old=rows.get(k);boolean accepted=(old==null?0:old.getLong("revision"))==op.getLong("base");
     if(accepted){old=new JSONObject().put("key",k).put("revision",++clock).put("value",op.getJSONObject("value"));rows.put(k,old);head=clock;uploads.add(user+" "+k);}
     acks.put(new JSONObject().put("operation",op.getString("operation")).put("key",k).put("accepted",accepted).put("record",old!=null?old:new JSONObject().put("key",k).put("revision",0).put("value",new JSONObject().put("present",false))));
    }
    heads.put(user,head);
    if(!body.optBoolean("pull",true))return new JSONObject().put("ok",true).put("acks",acks).put("records",new JSONArray()).put("after",after).put("through",0).put("more",false);
    long ceiling=through>0?through:head;List<JSONObject> list=new ArrayList<>();
    for(JSONObject row:rows.values())if(row.getLong("revision")>after&&row.getLong("revision")<=ceiling)list.add(row);
    Collections.sort(list,(x,y)->Long.compare(x.optLong("revision"),y.optLong("revision")));
    boolean more=list.size()>100;if(more)list=list.subList(0,100);JSONArray records=new JSONArray();for(JSONObject x:list)records.put(x);
    return new JSONObject().put("ok",true).put("acks",acks).put("records",records).put("through",ceiling).put("after",more?list.get(99).getLong("revision"):ceiling).put("more",more);
   }
  }
  synchronized long position(String k){return position("fixture",k);}
  synchronized long position(String u,String k){JSONObject r=rows(u).get(k);return r==null?-1:r.optJSONObject("value").optLong("position",-1);}
  synchronized boolean present(String u,String k){JSONObject r=rows(u).get(k);return r!=null&&r.optJSONObject("value").optBoolean("present");}
  synchronized int count(String u){return rows(u).size();}
  /** Another TV's write, or (raw) a record no real server would accept. */
  synchronized void put(String u,String k,JSONObject value)throws JSONException{rows(u).put(k,new JSONObject().put("key",k).put("revision",++clock).put("value",value));heads.put(u,clock);}
  synchronized void snapshot()throws JSONException{savedAccounts=new HashMap<>();for(Map.Entry<String,Map<String,JSONObject>> a:accounts.entrySet()){Map<String,JSONObject> copy=new LinkedHashMap<>();for(Map.Entry<String,JSONObject> r:a.getValue().entrySet())copy.put(r.getKey(),new JSONObject(r.getValue().toString()));savedAccounts.put(a.getKey(),copy);}savedHeads=new HashMap<>(heads);}
  /** The database goes back to the snapshot; the revision clock does not (revisions are never reused). */
  synchronized void restore(){accounts.clear();accounts.putAll(savedAccounts);heads.clear();heads.putAll(savedHeads);}
 }
 private static JSONObject present(boolean p)throws JSONException{return new JSONObject().put("present",p);}

 public void testTwoTvsResumeMyListWatchedAndRemoval()throws Exception{
  Fake server=new Fake();Context a=context("a"),b=context("b");AccountSync one=client(a,server),two=client(b,server);
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:1",120000).putLong("resume_duration_movie:1",600000).putString("favorites","|movie:1|").putBoolean("watched_series:2:3",true).apply());
  tick(one,false);waitFor(()->server.position("resume:movie:1")==120000);
  tick(two,false);waitFor(()->Api.prefs(b).getLong("resume_position_movie:1",0)==120000);
  assertTrue(Api.prefs(b).getString("favorites","").contains("|movie:1|"));assertTrue(Api.prefs(b).getBoolean("watched_series:2:3",false));
  main(()->Api.prefs(a).edit().remove("resume_position_movie:1").remove("resume_duration_movie:1").apply());tick(one,false);waitFor(()->server.position("resume:movie:1")==-1);
  tick(two,false);waitFor(()->!Api.prefs(b).contains("resume_position_movie:1"));assertTrue(Api.prefs(b).getString("favorites","").contains("|movie:1|"));
 }
 public void testExistingLocalProgressMergesWithoutOverwritingCloud()throws Exception{
  Fake server=new Fake();Context a=context("seed_server"),b=context("seed_local");AccountSync one=client(a,server);
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:1",100000).apply());tick(one,false);waitFor(()->server.position("resume:movie:1")==100000);
  AccountSync target=client(b,server,"fixture",e->e.putLong("resume_position_movie:1",20000).putLong("resume_position_movie:2",30000));
  tick(target,false);waitFor(()->Api.prefs(b).getLong("resume_position_movie:1",0)==100000);
  assertEquals(30000,server.position("resume:movie:2"));assertEquals(30000,Api.prefs(b).getLong("resume_position_movie:2",0));
 }
 public void testServerDownKeepsLocalPlaybackProgress()throws Exception{
  Fake server=new Fake();server.fail=true;Context c=context("offline");AccountSync s=client(c,server);
  main(()->Api.prefs(c).edit().putLong("resume_position_movie:4",22000).apply());tick(s,true);waitFor(()->server.calls>0);
  assertEquals(22000,Api.prefs(c).getLong("resume_position_movie:4",0));server.fail=false;Thread.sleep(300);tick(s,true);waitFor(()->server.position("resume:movie:4")==22000);
 }
 public void testRemoteDoesNotChangeActivePlayback()throws Exception{
  Fake server=new Fake();Context a=context("playing_a"),b=context("playing_b");AccountSync one=client(a,server),two=client(b,server);
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:5",33000).apply());tick(one,false);waitFor(()->server.position("resume:movie:5")==33000);
  server.entered=new CountDownLatch(1);CountDownLatch gate=server.entered;server.release=new CountDownLatch(1);tick(two,false);
  assertTrue(gate.await(5,TimeUnit.SECONDS));tick(two,true);server.release.countDown();waitFor(()->journal(b).getLong("rev:resume:movie:5",0)==1);assertFalse(Api.prefs(b).contains("resume_position_movie:5"));
  tick(two,false);waitFor(()->Api.prefs(b).getLong("resume_position_movie:5",0)==33000);
 }
 public void testDeferredRemoteCannotOverwriteSubsequentLocalPlayback()throws Exception{
  Fake server=new Fake();Context a=context("deferred_a"),b=context("deferred_b");AccountSync one=client(a,server),two=client(b,server);
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:5",33000).apply());tick(one,false);waitFor(()->server.position("resume:movie:5")==33000);
  server.entered=new CountDownLatch(1);CountDownLatch gate=server.entered;server.release=new CountDownLatch(1);tick(two,false);
  assertTrue(gate.await(5,TimeUnit.SECONDS));tick(two,true);server.release.countDown();waitFor(()->journal(b).getLong("rev:resume:movie:5",0)==1);
  main(()->Api.prefs(b).edit().putLong("resume_position_movie:5",55000).apply());tick(two,true);waitFor(()->server.position("resume:movie:5")==55000);
  Thread.sleep(200);tick(two,false);Thread.sleep(300);getInstrumentation().waitForIdleSync();assertEquals(55000,Api.prefs(b).getLong("resume_position_movie:5",0));
 }
 public void testEditWhileRequestInFlightSurvivesAcknowledgement()throws Exception{
  Fake server=new Fake();Context c=context("race");AccountSync s=client(c,server);
  main(()->Api.prefs(c).edit().putLong("resume_position_movie:6",20000).apply());server.entered=new CountDownLatch(1);CountDownLatch entered=server.entered;server.release=new CountDownLatch(1);tick(s,false);
  assertTrue(entered.await(5,TimeUnit.SECONDS));main(()->Api.prefs(c).edit().putLong("resume_position_movie:6",45000).apply());Thread.sleep(350);server.release.countDown();waitFor(()->server.position("resume:movie:6")==20000);
  Thread.sleep(300);tick(s,false);waitFor(()->server.position("resume:movie:6")==45000);
 }
 public void testReattachFindsLocalSaveMissedByObserver()throws Exception{
  Fake server=new Fake();Context c=context("reattach");AccountSync old=client(c,server);
  main(()->Api.prefs(c).edit().putLong("resume_position_movie:8",20000).apply());tick(old,false);
  waitFor(()->journal(c).getLong("rev:resume:movie:8",0)==1);
  final AccountSync[] replacement=new AccountSync[1];main(()->{
   old.fixtureDetach();Api.prefs(c).edit().putLong("resume_position_movie:8",45000).commit();
   replacement[0]=AccountSync.fixture(c,server);clients.add(replacement[0]);
  });
  tick(replacement[0],false);waitFor(()->server.position("resume:movie:8")==45000);assertEquals(45000,Api.prefs(c).getLong("resume_position_movie:8",0));
 }
 public void testSignOutRejectsLateResponse()throws Exception{
  Fake server=new Fake();Context c=context("signout");AccountSync s=client(c,server);server.entered=new CountDownLatch(1);CountDownLatch entered=server.entered;server.release=new CountDownLatch(1);tick(s,false);
  assertTrue(entered.await(5,TimeUnit.SECONDS));main(()->{s.fixtureClose();Api.prefs(c).edit().clear().apply();});server.release.countDown();Thread.sleep(400);getInstrumentation().waitForIdleSync();assertTrue(Api.prefs(c).getAll().isEmpty());
 }
 /** 3,000 records: uploaded from one TV's existing history and imported on another, with no long main-thread step. */
 public void testLargeHistoryMovesWithoutMainThreadStalls()throws Exception{
  Fake server=new Fake();Context a=context("large_a"),b=context("large_b");
  AccountSync one=client(a,server,"fixture",e->{StringBuilder fav=new StringBuilder();
   for(int i=1;i<=1000;i++)e.putLong("resume_position_movie:"+i,20000+i).putLong("resume_duration_movie:"+i,600000);
   for(int i=1;i<=1500;i++)e.putBoolean("watched_series:7:"+i,true);
   for(int i=1;i<=500;i++)fav.append("|series:").append(i).append('|');e.putString("favorites",fav.toString());});
  Stalls seeding=new Stalls();seeding.start();   // after the test's own 3,000-key setup write
  long seedStart=SystemClock.uptimeMillis();
  for(int round=0;round<40&&server.count("fixture")<3000;round++){tick(one,false);long before=server.count("fixture");
   long end=System.currentTimeMillis()+3000;while(System.currentTimeMillis()<end&&server.count("fixture")==before)Thread.sleep(50);}
  assertEquals(3000,server.count("fixture"));long seedMs=SystemClock.uptimeMillis()-seedStart,seedStall=seeding.finish();
  AccountSync.maxMainSliceMs=0;Stalls importing=new Stalls();importing.start();long importStart=SystemClock.uptimeMillis();
  AccountSync two=client(b,server);tick(two,false);
  waitFor(()->Api.prefs(b).getLong("resume_position_movie:1000",0)==21000&&Api.prefs(b).getBoolean("watched_series:7:1500",false)
   &&SyncRecord.favorites(Api.prefs(b).getString("favorites","")).size()==500,60000);
  long importMs=SystemClock.uptimeMillis()-importStart,importStall=importing.finish(),slice=AccountSync.maxMainSliceMs;
  android.util.Log.i("FlixTownQA","sync_large upload_ms="+seedMs+" upload_max_main_stall_ms="+seedStall+" import_ms="+importMs+" import_max_main_stall_ms="+importStall+" max_import_slice_ms="+slice);
  int watched=0;for(String k:Api.prefs(b).getAll().keySet())if(k.startsWith("watched_series:7:"))watched++;
  assertEquals(1500,watched);
  assertTrue("main-thread import slice "+slice+" ms",slice<250);
  assertTrue("main thread stalled "+importStall+" ms while importing",importStall<500);
  assertTrue("main thread stalled "+seedStall+" ms while uploading",seedStall<500);
 }
 /** While video plays, each save (every few seconds) is not captured or written; stopping uploads the latest. */
 public void testPlaybackSavesAreCollectedNotCapturedPerSave()throws Exception{
  Fake server=new Fake();Context c=context("per_save");AccountSync s=client(c,server);
  AccountSync.fixtureAwaitWork();   // this TV's one-time startup scan is done before playback starts
  main(()->AccountSync.player(true));
  for(int i=0;i<12;i++){final long p=20000+i*5000;main(()->Api.prefs(c).edit().putLong("resume_position_movie:11",p).putLong("resume_duration_movie:11",600000).apply());Thread.sleep(100);}
  Thread.sleep(800);getInstrumentation().waitForIdleSync();AccountSync.fixtureAwaitWork();   // anything a save had queued is written by now
  assertFalse("not captured per save while playing",journal(c).contains("pending:resume:movie:11"));assertEquals(0,server.calls);
  main(()->AccountSync.player(false));waitFor(()->server.position("resume:movie:11")==75000);
 }
 /** The server's database is restored from a backup: this TV's newer copies are uploaded again, never replaced by older ones. */
 public void testRestoredServerKeepsThisTvsNewerProgress()throws Exception{
  Fake server=new Fake();Context a=context("restore_a");AccountSync one=client(a,server);
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:21",20000).putBoolean("watched_movie:22",true).apply());
  tick(one,false);waitFor(()->server.position("resume:movie:21")==20000&&!journal(a).getString("anchor","").isEmpty());
  server.snapshot();
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:21",40000).putLong("resume_position_movie:23",30000).apply());
  tick(one,false);waitFor(()->server.position("resume:movie:23")==30000&&journal(a).getString("anchor","").equals("resume:movie:23"));
  server.restore();                                           // movie 21 back at 20000, movie 23 gone
  server.put("fixture","favorite:movie:24",present(true));     // another TV writes after the restore
  tick(one,false);
  waitFor(()->server.position("resume:movie:21")==40000&&server.position("resume:movie:23")==30000,15000);
  waitFor(()->Api.prefs(a).getString("favorites","").contains("|movie:24|"));
  assertEquals("never replaced by the restored older copy",40000,Api.prefs(a).getLong("resume_position_movie:21",0));
  assertTrue(Api.prefs(a).getBoolean("watched_movie:22",false));assertFalse(journal(a).getBoolean("restoring",false));
  Context b=context("restore_b");AccountSync two=client(b,server);tick(two,false);
  waitFor(()->Api.prefs(b).getLong("resume_position_movie:21",0)==40000&&Api.prefs(b).getLong("resume_position_movie:23",0)==30000);
 }
 /** A different account signs in on this TV: the previous account's data is neither deleted on the server nor given to the new one. */
 public void testAccountSwitchUploadsNothingFromThePreviousAccount()throws Exception{
  Fake server=new Fake();Context c=context("switch");AccountSync alice=client(c,server,"alice",null);
  main(()->Api.prefs(c).edit().putLong("resume_position_movie:31",50000).putString("favorites","|movie:32|").apply());
  tick(alice,false);waitFor(()->server.position("alice","resume:movie:31")==50000&&server.present("alice","favorite:movie:32"));
  int uploads=server.uploads.size();
  main(()->SignOut.beforeSignIn(c,"bob"));
  Thread.sleep(1500);getInstrumentation().waitForIdleSync();
  assertEquals("removing alice's data from this TV is not uploaded as removals",uploads,server.uploads.size());
  assertEquals(50000,server.position("alice","resume:movie:31"));assertTrue(server.present("alice","favorite:movie:32"));
  assertFalse(Api.prefs(c).contains("resume_position_movie:31"));waitFor(()->journal(c).getAll().isEmpty());
  final AccountSync[] bob=new AccountSync[1];main(()->{bob[0]=AccountSync.fixture(c,server,"bob");clients.add(bob[0]);});
  main(()->Api.prefs(c).edit().putBoolean("watched_movie:33",true).apply());tick(bob[0],false);
  waitFor(()->server.present("bob","watched:movie:33"));
  assertEquals("bob's account holds only bob's record",1,server.count("bob"));
  assertFalse(Api.prefs(c).contains("resume_position_movie:31"));assertFalse(Api.prefs(c).getString("favorites","").contains("movie:32"));
  for(String u:server.uploads)if(u.startsWith("bob "))assertEquals("bob watched:movie:33",u);
 }
 /** Malformed records from the server are never written to the TV; a record the server refuses does not stall later changes. */
 public void testInvalidRecordsAreDroppedAndSyncContinues()throws Exception{
  Fake server=new Fake();Context c=context("invalid");AccountSync s=client(c,server);
  JSONObject resume=new JSONObject().put("present",true).put("position",20000).put("duration",90000).put("next","").put("next_ext","mp4").put("label","S1").put("done",false);
  server.put("fixture","resume:series:41",new JSONObject(resume.toString()).put("episode","../x").put("extension","mkv"));
  server.put("fixture","resume:movie:42",new JSONObject(resume.toString()).put("episode","").put("extension","mp4?x=1"));
  server.put("fixture","watched:movie:43",new JSONObject().put("present","yes"));
  server.put("fixture","watched:movie:44",present(true));
  server.refuse.add("favorite:movie:45");
  main(()->Api.prefs(c).edit().putString("favorites","|movie:45||movie:46|").apply());Thread.sleep(400);
  tick(s,false);
  waitFor(()->Api.prefs(c).getBoolean("watched_movie:44",false)&&server.present("fixture","favorite:movie:46"));
  assertFalse(Api.prefs(c).contains("resume_episode_series:41"));assertFalse(Api.prefs(c).contains("resume_extension_movie:42"));assertFalse(Api.prefs(c).contains("watched_movie:43"));
  waitFor(()->!journal(c).contains("pending:favorite:movie:45")&&!journal(c).contains("remote:resume:series:41"));
  assertTrue("this TV keeps its My List entry",Api.prefs(c).getString("favorites","").contains("|movie:45|"));
  main(()->Api.prefs(c).edit().putBoolean("watched_movie:47",true).apply());Thread.sleep(400);tick(s,false);waitFor(()->server.present("fixture","watched:movie:47"));
 }
}
