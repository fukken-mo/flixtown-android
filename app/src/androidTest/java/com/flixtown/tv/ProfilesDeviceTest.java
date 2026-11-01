package com.flixtown.tv;

import android.app.Activity;
import android.content.*;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline tests: independent TV preference files, fake authenticated services, no client data. */
public class ProfilesDeviceTest extends InstrumentationTestCase {
 private Activity activity;
 private final List<AccountSync> clients=new ArrayList<>();
 private Context context(String name){return new ContextWrapper(getInstrumentation().getTargetContext()){
  @Override public Context getApplicationContext(){return this;}
  @Override public SharedPreferences getSharedPreferences(String n,int m){return super.getSharedPreferences("profiles_test_"+name+"_"+n,m);}
 };}
 private void main(Runnable r){getInstrumentation().runOnMainSync(r);}
 private void waitFor(Callable<Boolean> p)throws Exception{long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(p.call())return;Thread.sleep(80);}fail("profile test timed out");}
 private ProfileCatalog profiles;
 private final String first="12345678-1234-4234-8234-123456789012",second="87654321-4321-4321-8321-210987654321";
 @Override protected void setUp()throws Exception{super.setUp();profiles=new ProfileCatalog(Arrays.asList(new ProfileCatalog.Profile("default","My profile","avatar_01",false),new ProfileCatalog.Profile(first,"Mo","avatar_02",false),new ProfileCatalog.Profile(second,"Guest","avatar_03",false)),"default");}
 private void seed(Context c,String selected){
  Profiles.raw(c).edit().clear().putString(SignOut.OWNER,SignOut.ownerId("fixture")).putString("server","https://demo.invalid").putString("progress_sync_api_url","https://fixture.invalid/api/tv-account-sync.php").commit();
  Profiles.save(c,Profiles.scope(c),profiles.select(selected),1);c.getSharedPreferences("profile_history_"+Profiles.scope(c)+"_"+selected,0).edit().clear().commit();
  c.getSharedPreferences("account_sync_v1"+Profiles.journalSuffix(c),0).edit().clear().commit();c.getSharedPreferences("account_sync_initial_v1"+Profiles.journalSuffix(c),0).edit().clear().commit();
 }
 private AccountSync client(Context c,Fake f,String id){final AccountSync[] out={null};main(()->{seed(c,id);out[0]=AccountSync.fixture(c,f);clients.add(out[0]);});return out[0];}
 private void tick(AccountSync s){main(()->s.fixtureTick(false));}
 private static final class Fake implements AccountSync.Transport {
  final Map<String,Map<String,JSONObject>> data=new HashMap<>();long clock;
  CountDownLatch gate,release;boolean block;
  @Override public JSONObject post(String url,JSONObject b)throws Exception{
   if(b.optString("action").equals("session"))return new JSONObject().put("ok",true).put("session",b.getString("username")).put("ttl",900);
   if(block){block=false;gate.countDown();release.await(10,TimeUnit.SECONDS);}
   synchronized(this){String scope=b.getString("session")+"|"+b.optString("profile","default");Map<String,JSONObject> rows=data.get(scope);if(rows==null){rows=new LinkedHashMap<>();data.put(scope,rows);}
    JSONArray acks=new JSONArray(),records=new JSONArray();JSONArray ops=b.optJSONArray("operations");if(ops!=null)for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);String k=op.getString("key");JSONObject old=rows.get(k);boolean accepted=op.optLong("base")== (old==null?0:old.getLong("revision"));JSONObject row=old;
     if(accepted){row=new JSONObject().put("key",k).put("revision",++clock).put("value",op.getJSONObject("value"));rows.put(k,row);}if(row==null)row=new JSONObject().put("key",k).put("revision",0).put("value",new JSONObject().put("present",false));acks.put(new JSONObject().put("operation",op.getString("operation")).put("accepted",accepted).put("record",row));}
    long after=b.optLong("after"),last=after;for(JSONObject row:rows.values())last=Math.max(last,row.getLong("revision"));if(b.optBoolean("pull",true))for(JSONObject row:rows.values())if(row.getLong("revision")>after)records.put(row);
    JSONObject r=new JSONObject().put("ok",true).put("acks",acks).put("records",records).put("after",b.optBoolean("pull",true)?last:after).put("through",last).put("more",false);
    if(b.has("profile"))r.put("profile",b.getString("profile"));return r;
   }
  }
 }
 public void testNamedProfilesSyncToMatchingProfileOnly()throws Exception{
  Fake f=new Fake();Context a=context("one"),b=context("two"),c=context("guest"),d=context("legacy");
  AccountSync one=client(a,f,first),two=client(b,f,first),guest=client(c,f,second),legacy=client(d,f,"default");
  main(()->Api.prefs(a).edit().putLong("resume_position_movie:1",120000).putLong("resume_duration_movie:1",600000).putString("favorites","|movie:1|").putBoolean("watched_series:2:3",true).apply());
  tick(one);waitFor(()->{synchronized(f){return f.data.containsKey("fixture|"+first)&&f.data.get("fixture|"+first).size()>=3;}});
  tick(two);waitFor(()->Api.prefs(b).getLong("resume_position_movie:1",0)==120000);
  tick(guest);tick(legacy);AccountSync.fixtureAwaitWork();Thread.sleep(200);
  assertFalse(Api.prefs(c).contains("resume_position_movie:1"));assertFalse(Api.prefs(d).contains("resume_position_movie:1"));assertTrue(Api.prefs(b).getBoolean("watched_series:2:3",false));assertTrue(Api.prefs(b).getString("favorites","").contains("movie:1"));
 }
 public void testProfileSwitchKeepsDefaultAndRenamesKeepHistory()throws Exception{
  Context c=context("migration");main(()->{seed(c,"default");Api.prefs(c).edit().putLong("resume_position_movie:8",90000).putString("favorites","|movie:8|").apply();Profiles.select(c,first);assertFalse(Api.prefs(c).contains("resume_position_movie:8"));Api.prefs(c).edit().putLong("resume_position_movie:8",20000).apply();Profiles.save(c,Profiles.scope(c),Profiles.catalog(c).edit(first,"Family","avatar_28",false),2);assertEquals(20000,Api.prefs(c).getLong("resume_position_movie:8",0));Profiles.select(c,"default");assertEquals(90000,Api.prefs(c).getLong("resume_position_movie:8",0));assertTrue(Api.prefs(c).getString("favorites","").contains("movie:8"));Profiles.select(c,first);assertEquals(20000,Api.prefs(c).getLong("resume_position_movie:8",0));});
 }
 public void testLateReplyCannotImportIntoNextProfile()throws Exception{
  Fake f=new Fake();Context a=context("late_source"),b=context("late_target");AccountSync source=client(a,f,first);main(()->Api.prefs(a).edit().putLong("resume_position_movie:7",100000).apply());tick(source);waitFor(()->{synchronized(f){return f.data.containsKey("fixture|"+first)&&f.data.get("fixture|"+first).size()>0;}});
  AccountSync target=client(b,f,first);AccountSync.fixtureAwaitWork();Thread.sleep(200);f.gate=new CountDownLatch(1);f.release=new CountDownLatch(1);f.block=true;tick(target);assertTrue(f.gate.await(8,TimeUnit.SECONDS));main(()->{AccountSync.detachProfile();Profiles.select(b,second);});f.release.countDown();Thread.sleep(400);AccountSync.fixtureAwaitWork();assertFalse(Api.prefs(b).contains("resume_position_movie:7"));
 }
 private View findText(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View got=findText(g.getChildAt(i),text);if(got!=null)return got;}}return null;}
 private View tree(){return activity.findViewById(android.R.id.content);}
 public void testRealNameAvatarEditorAndRemoteFocus()throws Exception{
  Context c=getInstrumentation().getTargetContext();main(()->{seed(c,"default");try{AccountStore.save(c,"fixture","fixture-password","https://demo.invalid");}catch(Exception e){throw new IllegalStateException(e);}Profiles.fixture((url,b)->{
   if(b.optString("action").equals("session"))return new JSONObject().put("ok",true).put("session","fixture");
   if(b.optString("mutation").equals("update")){JSONObject p=b.getJSONObject("profile");profiles=profiles.edit(p.getString("id"),p.getString("name"),p.getString("avatar"),false);}
   JSONArray a=new JSONArray();for(ProfileCatalog.Profile p:profiles.profiles)a.put(p.json());return new JSONObject().put("ok",true).put("profiles_version",1).put("revision",2).put("profiles",a);
  });});
  activity=getInstrumentation().startActivitySync(new Intent(c,ProfilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("cached",true));
  getInstrumentation().waitForIdleSync();final View[] before={null};main(()->before[0]=activity.getCurrentFocus());getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);getInstrumentation().waitForIdleSync();main(()->assertNotSame(before[0],activity.getCurrentFocus()));
  main(()->{findText(tree(),"Manage profiles").performClick();View text=findText(tree(),"Mo");assertNotNull(text);((View)text.getParent()).performClick();EditText input=findEdit(tree());assertNotNull(input);input.setText("Family");View avatar=findDescription(tree(),"Avatar 28");assertNotNull(avatar);assertTrue(avatar.isFocusable());assertTrue(avatar.getBottom()>0);avatar.performClick();findText(tree(),"Save").performClick();});
  waitFor(()->Profiles.catalog(c).profiles.stream().anyMatch(p->p.id.equals(first)&&p.name.equals("Family")&&p.avatar.equals("avatar_28")));
  main(()->{assertNotNull(findText(tree(),"Family"));for(View v:new View[]{findText(tree(),"Done"),findText(tree(),"Family")}){assertNotNull(v);int[] pos=new int[2];v.getLocationOnScreen(pos);assertTrue(pos[1]>=0);assertTrue(pos[1]+v.getHeight()<=getInstrumentation().getTargetContext().getResources().getDisplayMetrics().heightPixels);}});
 }
 private EditText findEdit(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=findEdit(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
 private View findDescription(View v,String d){if(d.equals(v.getContentDescription()==null?null:v.getContentDescription().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View e=findDescription(((ViewGroup)v).getChildAt(i),d);if(e!=null)return e;}return null;}
 @Override protected void tearDown()throws Exception{
  main(()->{for(AccountSync s:clients)s.fixtureClose();if(activity!=null)activity.finish();Profiles.fixture(null);Profiles.signOut();});getInstrumentation().waitForIdleSync();if(activity!=null)AccountStore.clear(getInstrumentation().getTargetContext());super.tearDown();
 }
}
