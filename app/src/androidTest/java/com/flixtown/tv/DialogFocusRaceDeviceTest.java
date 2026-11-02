package com.flixtown.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.*;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Rapid close/reopen regression: dialogs that open in the same moment another closes must still take the
 * remote. These are the app's real chains, without any pause:
 *   exit:      Back opens "Exit Flix Town?", OK on Stay, Back again at once (and Back-to-close, Back-to-reopen)
 *   notices:   "Got it" on one notice posts the next notice (CustomerNotices)
 *   report:    the report picker's row opens "Send report?" from its click; Send swaps in "Sending report…",
 *              whose reply replaces it in one step (PlayerActivity)
 *   update:    "Update now" closes the offer and opens the download progress (AppUpdates)
 * A dialog is responsive when its window has focus and a remote key reaches it. Every attempt is recorded in
 * files/profiles-ui/race-results.txt; the test fails if any attempt left a dialog unresponsive.
 */
public class DialogFocusRaceDeviceTest extends InstrumentationTestCase {
 private Context c;private Activity activity;private final List<String> results=new ArrayList<>();private int failures;
 private static final int ATTEMPTS=6;
 private void main(Runnable r){final Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{r.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
 private void waitFor(String what,Callable<Boolean> p)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(p.call())return;Thread.sleep(50);}fail(what+" timed out");}
 private void key(int code){getInstrumentation().sendKeyDownUpSync(code);getInstrumentation().waitForIdleSync();}
 @Override protected void setUp()throws Exception{super.setUp();c=getInstrumentation().getTargetContext();getInstrumentation().setInTouchMode(false);
  main(()->{AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();Ui.lastShown=null;
   Profiles.raw(c).edit().clear().putString(SignOut.OWNER,SignOut.ownerId("fixture")).putString("server","https://demo.flixtown.invalid").putString("progress_sync_api_url","").commit();
   try{AccountStore.save(c,"fixture","fixture-password","https://demo.flixtown.invalid");}catch(Exception e){throw new IllegalStateException(e);}
   Profiles.save(c,Profiles.scope(c),new ProfileCatalog(Collections.singletonList(new ProfileCatalog.Profile("default","My profile","avatar_01",false)),"default"),1);});
  main(()->Profiles.returningHome(c));
  activity=getInstrumentation().startActivitySync(new Intent(c,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  waitFor("Home",()->activity.findViewById(R.id.home_rows)!=null&&activity.hasWindowFocus());Thread.sleep(1500);
 }
 // ---- helpers ----
 private String label(View v){return v instanceof TextView?((TextView)v).getText().toString():String.valueOf(v);}
 private Dialog next(Dialog before)throws Exception{long end=System.currentTimeMillis()+5000;
  while(System.currentTimeMillis()<end){Dialog d=Ui.lastShown;final boolean[] ok={false};if(d!=null&&d!=before)main(()->ok[0]=d.isShowing());if(ok[0])return d;Thread.sleep(20);}return null;}
 /** Window focus within 2 s, then a key that must reach the dialog: RIGHT moves focus (two actions) or OK runs the only action. */
 private String responsive(Dialog d,boolean twoActions)throws Exception{
  if(d==null)return "no dialog opened";
  long end=System.currentTimeMillis()+2000;boolean[] focus={false};
  while(System.currentTimeMillis()<end){main(()->focus[0]=d.getWindow().getDecorView().hasWindowFocus());if(focus[0])break;Thread.sleep(25);}
  final String[] before={""};main(()->before[0]=label(d.getCurrentFocus()));
  if(twoActions){key(KeyEvent.KEYCODE_DPAD_RIGHT);final String[] after={""};main(()->after[0]=label(d.getCurrentFocus()));
   if(after[0].equals(before[0]))return "RIGHT did not reach the dialog (focus stayed on "+before[0]+", window focus "+focus[0]+")";
   key(KeyEvent.KEYCODE_DPAD_LEFT);return focus[0]?"":"keys reached it, but window focus came late";}
  key(KeyEvent.KEYCODE_DPAD_CENTER);Thread.sleep(150);final boolean[] open={true};main(()->open[0]=d.isShowing());
  return open[0]?"OK did not reach the dialog (window focus "+focus[0]+")":"";
 }
 private int recoveriesBefore;
 private void record(String scenario,int attempt,String problem){
  boolean failed=!problem.isEmpty()&&!problem.startsWith("keys reached it");if(failed)failures++;
  int recovered=Ui.focusRecoveries-recoveriesBefore;recoveriesBefore=Ui.focusRecoveries;
  final boolean[] home={false};if(failed)main(()->home[0]=activity.hasWindowFocus());
  results.add((failed?"FAIL ":"ok   ")+scenario+" #"+attempt+(problem.isEmpty()?"":"  "+problem)+(failed?" (Home window focus "+home[0]+")":"")
   +(recovered>0?"  [lost focus notification recovered x"+recovered+"]":""));
 }
 private void closeAll()throws Exception{main(()->{Dialog d=Ui.lastShown;if(d!=null&&d.isShowing())d.dismiss();});Thread.sleep(700);getInstrumentation().waitForIdleSync();}
 /** The dialog the rapid Back opened; if Home first glided to the hero (existing behaviour), Back again. */
 private Dialog reopen(Dialog before)throws Exception{Dialog d=next(before);for(int i=0;i<2&&d==null;i++){key(KeyEvent.KEYCODE_BACK);d=next(before);}return d;}
 private Dialog openExit(Dialog before)throws Exception{for(int i=0;i<3;i++){key(KeyEvent.KEYCODE_BACK);Dialog d=next(before);if(d!=null)return d;}return null;}

 public void testRapidCloseAndReopen()throws Exception{
  recoveriesBefore=Ui.focusRecoveries;
  for(int a=1;a<=ATTEMPTS;a++){
   // Exit: OK on Stay, then Back at once (no pause)
   Dialog first=openExit(null);if(first==null){record("exit Stay→Back",a,"exit dialog did not open");closeAll();continue;}
   Thread.sleep(400);key(KeyEvent.KEYCODE_DPAD_CENTER);key(KeyEvent.KEYCODE_BACK);
   Dialog again=reopen(first);record("exit Stay→Back",a,responsive(again,true));closeAll();
   // Exit: Back closes, Back reopens at once
   Dialog shown=openExit(again);Thread.sleep(400);key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);
   Dialog reopened=reopen(shown);record("exit Back→Back",a,responsive(reopened,true));closeAll();
   // Customer notices: "Got it" posts the next notice
   final Dialog[] n1={null};
   main(()->n1[0]=Ui.dialog(activity,"Resolved one","Your reported issue has been marked resolved.",new String[]{"Watch","Got it"},0,i->{
    if(i==1)activity.getWindow().getDecorView().post(()->Ui.dialog(activity,"Resolved two","Your reported issue has been marked resolved.",new String[]{"Watch","Got it"},0,j->{},null));},null));
   Thread.sleep(400);key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);
   record("notices Got it→next",a,responsive(next(n1[0]),true));closeAll();
   // Report: picker row → "Send report?" from the click; Send → "Sending report…" → reply in one step
   final Dialog[] sending={null};
   main(()->Ui.picker(activity,"Report a problem",new String[]{"Video problem","Audio problem","Subtitle problem"},-1,issue->
    Ui.dialog(activity,"Send report?","Audio problem\nTitle, playback time and basic device details will be included.",new String[]{"Send report","Cancel"},0,i->{
     if(i==0){Dialog w=Ui.message(activity,"Sending report…","Please wait.");sending[0]=w;
      activity.getWindow().getDecorView().postDelayed(()->{w.dismiss();Ui.dialog(activity,"Report not sent","Reports are temporarily unavailable.",new String[]{"Try again","Back to video"},0,k->{},null);},300);}},null)));
   Dialog picker=next(null);Thread.sleep(400);key(KeyEvent.KEYCODE_DPAD_CENTER);
   Dialog confirm=next(picker);record("report picker→Send report?",a,responsive(confirm,true));
   if(confirm!=null&&confirm.isShowing()){key(KeyEvent.KEYCODE_DPAD_CENTER);
    Dialog waiting=next(confirm);Dialog reply=null;long end=System.currentTimeMillis()+4000;
    while(System.currentTimeMillis()<end){Dialog d=Ui.lastShown;if(d!=null&&d!=waiting&&d!=confirm&&d.isShowing()){reply=d;break;}Thread.sleep(20);}
    record("report Sending→reply",a,responsive(reply,true));}
   closeAll();
   // Update: "Update now" closes the offer and opens the download progress
   UpdateInfo u=UpdateInfo.parse(new JSONObject().put("ok",true).put("package","fixture").put("update_version_code",10041).put("update_apk_url","https://updates.invalid/f.apk")
    .put("update_sha256","0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").put("update_notes","- Faster start"),"fixture",10040,false);
   final boolean[] hidden={false};
   main(()->AppUpdates.offerPrompt(activity,u,i->{if(i==0)Ui.progress(activity,"Downloading update","Hide",()->hidden[0]=true);},null));
   Dialog offer=next(null);Thread.sleep(400);key(KeyEvent.KEYCODE_DPAD_CENTER);
   Dialog progress=next(offer);String p=responsive(progress,false);if(p.isEmpty()&&!hidden[0])p="Hide did not run";
   record("update Update now→progress",a,p);closeAll();
  }
  java.io.File dir=new java.io.File(c.getExternalFilesDir(null),"profiles-ui");dir.mkdirs();
  try(java.io.FileWriter out=new java.io.FileWriter(new java.io.File(dir,"race-results.txt"))){out.write("API "+android.os.Build.VERSION.SDK_INT+", focus recoveries in total: "+Ui.focusRecoveries+"\n");for(String r:results)out.write(r+"\n");}
  assertEquals("dialogs left unresponsive:\n"+String.join("\n",results),0,failures);
 }
 @Override protected void tearDown()throws Exception{
  main(()->{Dialog d=Ui.lastShown;if(d!=null&&d.isShowing())d.dismiss();Ui.lastShown=null;if(activity!=null)activity.finishAffinity();AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();});
  getInstrumentation().waitForIdleSync();AccountStore.clear(c);super.tearDown();
 }
}
