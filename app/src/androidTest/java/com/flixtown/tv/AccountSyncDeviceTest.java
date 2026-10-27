package com.flixtown.tv;

import android.content.*;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Two independent TV stores share only the fake server. No live account, media or endpoint is used. */
public class AccountSyncDeviceTest extends InstrumentationTestCase {
 private final List<AccountSync> clients=new ArrayList<>();
 private Context context(String name){return new ContextWrapper(getInstrumentation().getTargetContext()){
  @Override public Context getApplicationContext(){return this;}
  @Override public SharedPreferences getSharedPreferences(String n,int m){return super.getSharedPreferences("sync_test_"+name+"_"+n,m);}
 };}
 private void main(Runnable r){getInstrumentation().runOnMainSync(r);}
 private AccountSync client(Context c,Fake f){final AccountSync[] out=new AccountSync[1];main(()->{Api.prefs(c).edit().clear().commit();c.getSharedPreferences("account_sync_v1",0).edit().clear().commit();out[0]=AccountSync.fixture(c,f);clients.add(out[0]);});return out[0];}
 private void tick(AccountSync c,boolean playing){main(()->c.fixtureTick(playing));}
 private void waitFor(Callable<Boolean> predicate)throws Exception {long end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(predicate.call())return;Thread.sleep(50);}fail("sync timed out");}
 @Override protected void tearDown()throws Exception {main(()->{for(AccountSync c:clients)c.fixtureClose();});super.tearDown();}
 static class Fake implements AccountSync.Transport {
  final Map<String,JSONObject> rows=new LinkedHashMap<>();long rev=0;volatile int calls=0;volatile boolean fail=false;
  volatile CountDownLatch entered,release;
  @Override public JSONObject post(String url,JSONObject body)throws Exception{
   if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new AssertionError("network on main");
   calls++;if(fail)throw new java.io.IOException();
   if(body.optString("action").equals("session"))return new JSONObject().put("ok",true).put("session","fixture-token").put("ttl",900);
   CountDownLatch e=entered,r=release;if(e!=null){entered=null;e.countDown();if(!r.await(5,TimeUnit.SECONDS))throw new java.io.IOException();}
   synchronized(this){JSONArray acks=new JSONArray(),ops=body.optJSONArray("operations");
    if(ops!=null)for(int i=0;i<ops.length();i++){
     JSONObject op=ops.getJSONObject(i);String k=op.getString("key");JSONObject old=rows.get(k);boolean accepted=(old==null?0:old.getLong("revision"))==op.getLong("base");
     if(accepted){old=new JSONObject().put("key",k).put("revision",++rev).put("value",op.getJSONObject("value"));rows.put(k,old);}
     acks.put(new JSONObject().put("operation",op.getString("operation")).put("accepted",accepted).put("record",old));
    }
    JSONArray records=new JSONArray();for(JSONObject row:rows.values())if(body.optBoolean("pull",true)&&row.getLong("revision")>body.optLong("after"))records.put(row);
    return new JSONObject().put("ok",true).put("acks",acks).put("records",records).put("after",body.optBoolean("pull",true)?rev:body.optLong("after")).put("through",rev).put("more",false);
   }
  }
  synchronized long position(String k){JSONObject r=rows.get(k);return r==null?-1:r.optJSONObject("value").optLong("position",-1);}
 }
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
  final AccountSync[] target=new AccountSync[1];main(()->{
   Api.prefs(b).edit().clear().putLong("resume_position_movie:1",20000).putLong("resume_position_movie:2",30000).apply();
   b.getSharedPreferences("account_sync_v1",0).edit().clear().commit();target[0]=AccountSync.fixture(b,server);clients.add(target[0]);
  });
  tick(target[0],false);waitFor(()->Api.prefs(b).getLong("resume_position_movie:1",0)==100000);
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
  assertTrue(gate.await(5,TimeUnit.SECONDS));tick(two,true);server.release.countDown();waitFor(()->b.getSharedPreferences("account_sync_v1",0).getLong("rev:resume:movie:5",0)==1);assertFalse(Api.prefs(b).contains("resume_position_movie:5"));
  tick(two,false);waitFor(()->Api.prefs(b).getLong("resume_position_movie:5",0)==33000);
 }
 public void testDeferredRemoteCannotOverwriteSubsequentLocalPlayback()throws Exception{
  Fake server=new Fake();Context a=context("deferred_a"),b=context("deferred_b");AccountSync one=client(a,server),two=client(b,server);
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:5",33000).apply());tick(one,false);waitFor(()->server.position("resume:movie:5")==33000);
  server.entered=new CountDownLatch(1);CountDownLatch gate=server.entered;server.release=new CountDownLatch(1);tick(two,false);
  assertTrue(gate.await(5,TimeUnit.SECONDS));tick(two,true);server.release.countDown();waitFor(()->b.getSharedPreferences("account_sync_v1",0).getLong("rev:resume:movie:5",0)==1);
  main(()->Api.prefs(b).edit().putLong("resume_position_movie:5",55000).apply());tick(two,true);waitFor(()->server.position("resume:movie:5")==55000);
  Thread.sleep(200);tick(two,false);Thread.sleep(300);getInstrumentation().waitForIdleSync();assertEquals(55000,Api.prefs(b).getLong("resume_position_movie:5",0));
 }
 public void testEditWhileRequestInFlightSurvivesAcknowledgement()throws Exception{
  Fake server=new Fake();Context c=context("race");AccountSync s=client(c,server);
  main(()->Api.prefs(c).edit().putLong("resume_position_movie:6",20000).apply());server.entered=new CountDownLatch(1);CountDownLatch entered=server.entered;server.release=new CountDownLatch(1);tick(s,true);
  assertTrue(entered.await(5,TimeUnit.SECONDS));main(()->Api.prefs(c).edit().putLong("resume_position_movie:6",45000).apply());Thread.sleep(350);server.release.countDown();waitFor(()->server.position("resume:movie:6")==20000);
  Thread.sleep(300);tick(s,true);waitFor(()->server.position("resume:movie:6")==45000);
 }
 public void testSignOutRejectsLateResponse()throws Exception{
  Fake server=new Fake();Context c=context("signout");AccountSync s=client(c,server);server.entered=new CountDownLatch(1);CountDownLatch entered=server.entered;server.release=new CountDownLatch(1);tick(s,false);
  assertTrue(entered.await(5,TimeUnit.SECONDS));main(()->{s.fixtureClose();Api.prefs(c).edit().clear().apply();});server.release.countDown();Thread.sleep(400);getInstrumentation().waitForIdleSync();assertTrue(Api.prefs(c).getAll().isEmpty());
 }
}
