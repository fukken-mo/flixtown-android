package com.flixtown.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;

/** Actual entry activities and background lifecycle, with cached accounts and the offline QA catalog. */
public class ProfileEntryDeviceTest extends InstrumentationTestCase {
 private Context c;private Activity activity;private final java.util.List<Instrumentation.ActivityMonitor> monitors=new java.util.ArrayList<>();
 private void main(Runnable r){final Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{r.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
 private void waitFor(Callable<Boolean> p)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(p.call())return;Thread.sleep(80);}fail("entry route timed out");}
 private Instrumentation.ActivityMonitor monitor(Class<?> cls,boolean block){Instrumentation.ActivityMonitor m=getInstrumentation().addMonitor(cls.getName(),null,block);monitors.add(m);return m;}
 @Override protected void setUp()throws Exception{super.setUp();c=getInstrumentation().getTargetContext();main(()->{
  AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();
  Profiles.raw(c).edit().clear().putString(SignOut.OWNER,SignOut.ownerId("entry-fixture")).putString("server","https://demo.flixtown.invalid").putString("progress_sync_api_url","").apply();
  try{AccountStore.save(c,"entry-fixture","fixture-password","https://demo.flixtown.invalid");}catch(Exception e){throw new IllegalStateException(e);}
  ProfileCatalog profiles=new ProfileCatalog(Arrays.asList(new ProfileCatalog.Profile("default","My profile","avatar_01",false),new ProfileCatalog.Profile("12345678-1234-4234-8234-123456789012","Mo","avatar_28",false)),"default");
  Profiles.save(c,Profiles.scope(c),profiles,1);
 });}
 private View text(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=text(((ViewGroup)v).getChildAt(i),s);if(found!=null)return found;}return null;}
 private boolean pickerReady(){final boolean[] ready={false};main(()->{try{java.lang.reflect.Field busy=ProfilesActivity.class.getDeclaredField("busy");busy.setAccessible(true);ready[0]=!busy.getBoolean(activity);}catch(Exception e){throw new IllegalStateException(e);}});return ready[0];}
 public void testFreshSplashGoesStraightToPicker()throws Exception{
  Instrumentation.ActivityMonitor homes=monitor(HomeActivity.class,true),pickers=monitor(ProfilesActivity.class,false);
  Activity intro=getInstrumentation().startActivitySync(new Intent(c,IntroActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  main(()->{try{Method next=IntroActivity.class.getDeclaredMethod("openHome");next.setAccessible(true);next.invoke(intro);}catch(Exception e){throw new IllegalStateException(e);}});
  activity=pickers.waitForActivityWithTimeout(10000);assertNotNull("splash opens profile picker",activity);
  assertEquals("Home never starts before selection",0,homes.getHits());
  waitFor(this::pickerReady);
  main(()->assertNotNull(text(activity.findViewById(android.R.id.content),"Who’s watching?")));
  main(()->((View)text(activity.findViewById(android.R.id.content),"My profile").getParent()).performClick());
  assertEquals("Home starts only after selection",1,homes.getHits());
 }
 public void testDirectHomeRouteDoesNotInflateHomepage()throws Exception{
  Instrumentation.ActivityMonitor homes=monitor(HomeActivity.class,false),pickers=monitor(ProfilesActivity.class,false);
  main(()->c.startActivity(new Intent(c,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
  activity=pickers.waitForActivityWithTimeout(10000);assertNotNull(activity);
  Activity deferred=homes.getLastActivity();assertNotNull(deferred);
  main(()->{assertNull("no homepage views before profile choice",deferred.findViewById(com.flixtown.tv.R.id.home_rows));assertTrue(deferred.isFinishing());});
 }
 public void testBackAtOpeningExitsWithoutStartingHome()throws Exception{
  Instrumentation.ActivityMonitor homes=monitor(HomeActivity.class,true);
  activity=getInstrumentation().startActivitySync(new Intent(c,ProfilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ProfilesActivity.EXTRA_ENTRY,true).putExtra("cached",true).putExtra("offline",true));
  main(()->activity.onBackPressed());waitFor(()->activity.isFinishing());
  assertEquals("Back does not bypass profile choice",0,homes.getHits());
 }
 public void testForegroundReturnHidesExistingHomeUntilPicker()throws Exception{
  main(()->Profiles.returningHome(c));
  activity=getInstrumentation().startActivitySync(new Intent(c,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  Activity home=activity;final View[] page={null};main(()->page[0]=home.findViewById(android.R.id.content));
  assertNotNull(home.findViewById(com.flixtown.tv.R.id.home_rows));
  Instrumentation.ActivityMonitor pickers=monitor(ProfilesActivity.class,false);
  try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME")){Thread.sleep(500);}
  waitFor(()->{final boolean[] hidden={false};main(()->hidden[0]=page[0].getVisibility()==View.INVISIBLE);return hidden[0];});
  main(()->c.startActivity(new Intent(c,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
  activity=pickers.waitForActivityWithTimeout(10000);assertNotNull("reopening shows profiles",activity);
  main(()->{assertEquals("old homepage remains hidden",View.INVISIBLE,page[0].getVisibility());});
 }
 @Override protected void tearDown()throws Exception{
  for(Instrumentation.ActivityMonitor m:monitors)getInstrumentation().removeMonitor(m);
  main(()->{if(activity!=null)activity.finishAffinity();AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();AccountStore.clear(c);});
  getInstrumentation().waitForIdleSync();super.tearDown();
 }
}
