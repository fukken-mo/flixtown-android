package com.flixtown.tv;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Rect;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Shared dialog design on the TV: the real exit and sign-out confirmations on Home, the profile delete
 * confirmation, long and multi-line messages, three actions, the update notice, a picker and the
 * progress dialog. Real D-pad and Back key events; every card and button inside the screen; no accent
 * bars; safe defaults and wording unchanged. Screenshots: files/profiles-ui/dialog-*.png. Offline only.
 */
public class DialogsDesignDeviceTest extends InstrumentationTestCase {
 private Context c;private Activity activity;
 private final List<Instrumentation.ActivityMonitor> monitors=new ArrayList<>();
 private void main(Runnable r){final Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{r.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
 private void waitFor(String what,Callable<Boolean> p)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(p.call())return;Thread.sleep(80);}fail(what+" timed out");}
 private void key(int code){getInstrumentation().sendKeyDownUpSync(code);getInstrumentation().waitForIdleSync();}
 private int dp(int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
 @Override protected void setUp()throws Exception{super.setUp();c=getInstrumentation().getTargetContext();getInstrumentation().setInTouchMode(false);
  main(()->{AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();Ui.lastShown=null;
   Profiles.raw(c).edit().clear().putString(SignOut.OWNER,SignOut.ownerId("fixture")).putString("server","https://demo.flixtown.invalid").putString("progress_sync_api_url","https://fixture.invalid/api/tv-account-sync.php").commit();
   try{AccountStore.save(c,"fixture","fixture-password","https://demo.flixtown.invalid");}catch(Exception e){throw new IllegalStateException(e);}
   ProfileCatalog profiles=new ProfileCatalog(Arrays.asList(new ProfileCatalog.Profile("default","My profile","avatar_01",false),
    new ProfileCatalog.Profile("12345678-1234-4234-8234-123456789012","Mo","avatar_28",false)),"default");
   Profiles.save(c,Profiles.scope(c),profiles,1);
   Profiles.fixture((url,b)->{
    if(b.optString("action").equals("session"))return new JSONObject().put("ok",true).put("session","fixture");
    JSONArray a=new JSONArray();for(ProfileCatalog.Profile p:profiles.profiles)a.put(p.json());return new JSONObject().put("ok",true).put("profiles_version",1).put("revision",2).put("profiles",a);
   });
  });}
 private Activity home()throws Exception{
  main(()->Profiles.returningHome(c));
  activity=getInstrumentation().startActivitySync(new Intent(c,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  final Activity h=activity;waitFor("Home",()->h.findViewById(R.id.home_rows)!=null&&h.hasWindowFocus());Thread.sleep(1500);return h;
 }
 // ---- dialog helpers ----
 private Dialog shown(Dialog before)throws Exception{
  long end=System.currentTimeMillis()+15000;
  while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();Dialog d=Ui.lastShown;
   final boolean[] ready={false};if(d!=null&&d!=before)main(()->ready[0]=d.isShowing()&&d.getCurrentFocus() instanceof Button&&d.getWindow().getDecorView().hasWindowFocus());
   if(ready[0]){Thread.sleep(250);getInstrumentation().waitForIdleSync();return d;}Thread.sleep(100);}
  StringBuilder w=new StringBuilder();for(String line:shell("dumpsys window windows").split("\n"))if(line.contains("mCurrentFocus")||line.contains("mFocusedApp"))w.append(line.trim()).append(" ; ");
  fail("dialog not focused; "+w);return null;
 }
 private String shell(String cmd)throws Exception{
  try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(cmd);
      java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){
   java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toString("UTF-8");}
 }
 private View root(Dialog d){return d.getWindow().getDecorView();}
 private View text(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=text(((ViewGroup)v).getChildAt(i),s);if(f!=null)return f;}return null;}
 private List<Button> buttons(View v,List<Button> out){if(v instanceof Button&&v.getVisibility()==View.VISIBLE)out.add((Button)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)buttons(((ViewGroup)v).getChildAt(i),out);return out;}
 private List<String> labels(Dialog d){List<String> l=new ArrayList<>();for(Button b:buttons(root(d),new ArrayList<>()))l.add(b.getText().toString());return l;}
 private String focused(Dialog d){final String[] f={null};main(()->{View v=d.getCurrentFocus();f[0]=v instanceof TextView?((TextView)v).getText().toString():String.valueOf(v);});return f[0];}
 private Rect screen(View v){int[] p=new int[2];v.getLocationOnScreen(p);return new Rect(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());}
 /** Card and buttons inside the screen (buttons also at their focused scale), no accent bar, title shown. */
 private void checkLayout(Dialog d,String title){
  main(()->{int w=c.getResources().getDisplayMetrics().widthPixels,h=c.getResources().getDisplayMetrics().heightPixels;
   View card=((ViewGroup)d.findViewById(android.R.id.content)).getChildAt(0);Rect r=screen(card);
   assertTrue(title+": card inside the screen "+r,r.left>=0&&r.top>=0&&r.right<=w&&r.bottom<=h);
   assertNotNull(title+": title shown",text(root(d),title));
   for(Button b:buttons(root(d),new ArrayList<>())){Rect br=screen(b);float g=br.width()*0.02f;
    assertTrue(b.getText()+" inside the card",br.left-g>=r.left&&br.right+g<=r.right&&br.top>=r.top&&br.bottom<=r.bottom);
    assertEquals(b.getText()+" one line",1,b.getLineCount());}
   assertFalse(title+": no accent bar or divider",hasBar(card));
  });
 }
 private boolean hasBar(View v){if(v.getClass()==View.class&&!(v.getParent() instanceof FrameLayout)&&v.getVisibility()==View.VISIBLE&&v.getHeight()>0&&v.getHeight()<=dp(6))return true;   // a progress bar's fill (inside its track) is not decoration
  if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(hasBar(((ViewGroup)v).getChildAt(i)))return true;return false;}
 private void backDismisses(Dialog d)throws Exception{key(KeyEvent.KEYCODE_BACK);waitFor("Back dismisses",()->!d.isShowing());}

 public void testExitConfirmation()throws Exception{
  Activity h=home();
  Dialog d=exit(h,null);
  checkLayout(d,"Exit Flix Town?");assertEquals(Arrays.asList("Stay","Exit"),labels(d));
  assertEquals("Stay is focused",  "Stay",focused(d));screenshot("exit-stay-focused");
  key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("Exit",focused(d));screenshot("exit-exit-focused");
  key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("focus stays in the dialog","Exit",focused(d));
  key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_UP);assertEquals("Exit",focused(d));
  key(KeyEvent.KEYCODE_DPAD_LEFT);assertEquals("Stay",focused(d));
  backDismisses(d);assertFalse("Back keeps Flix Town open",h.isFinishing());
  Dialog again=exit(h,d);assertEquals("Stay is focused again","Stay",focused(again));
  key(KeyEvent.KEYCODE_DPAD_CENTER);waitFor("Stay closes the dialog",()->!again.isShowing());assertFalse("Stay keeps Flix Town open",h.isFinishing());
  Dialog third=exit(h,again);key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);
  waitFor("Exit closes Flix Town",h::isFinishing);
 }
 /** Back on Home: from a row Home first glides up to the hero (existing behaviour), then asks before leaving. */
 private Dialog exit(Activity h,Dialog before)throws Exception{
  for(int i=0;i<3;i++){key(KeyEvent.KEYCODE_BACK);Thread.sleep(400);getInstrumentation().waitForIdleSync();Dialog s=Ui.lastShown;if(s!=null&&s!=before&&s.isShowing())return shown(before);}
  fail("Back on Home did not ask before leaving");return null;
 }
 public void testSignOutConfirmationIsSafeByDefault()throws Exception{
  Activity h=home();
  main(()->((HomeActivity)h).signOut());Dialog d=shown(null);
  checkLayout(d,"Sign out of Flix Town?");assertEquals(Arrays.asList("Cancel","Sign out"),labels(d));
  main(()->assertNotNull("message kept",text(root(d),"This TV will forget your account. Your settings stay. To watch again, scan the code or sign in with your remote.")));
  assertEquals("Cancel is the safe default","Cancel",focused(d));
  main(()->assertEquals("Sign out is marked as destructive",Ui.DESTRUCTIVE_TEXT,((Button)text(root(d),"Sign out")).getCurrentTextColor()));
  screenshot("signout-cancel-focused");
  key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("Sign out",focused(d));screenshot("signout-signout-focused");
  backDismisses(d);assertNotNull("Back does not sign out",AccountStore.read(c));assertFalse(h.isFinishing());
 }
 public void testProfileDeleteConfirmation()throws Exception{
  activity=getInstrumentation().startActivitySync(new Intent(c,ProfilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("cached",true));getInstrumentation().waitForIdleSync();
  main(()->{text(activity.findViewById(android.R.id.content),"Manage profiles").performClick();((View)text(activity.findViewById(android.R.id.content),"Mo").getParent()).performClick();});
  getInstrumentation().waitForIdleSync();
  main(()->text(activity.findViewById(android.R.id.content),"Delete profile").performClick());Dialog d=shown(null);
  checkLayout(d,"Delete Mo?");assertEquals(Arrays.asList("Delete","Cancel"),labels(d));
  assertEquals("Cancel is the safe default","Cancel",focused(d));screenshot("delete-profile-cancel-focused");
  key(KeyEvent.KEYCODE_DPAD_LEFT);assertEquals("Delete",focused(d));screenshot("delete-profile-delete-focused");
  backDismisses(d);
  assertTrue("Back keeps the profile",Profiles.catalog(c).profiles.stream().anyMatch(p->p.name.equals("Mo")));
  main(()->assertNotNull("the editor is still open",text(activity.findViewById(android.R.id.content),"Delete profile")));
 }
 public void testLongAndMultiLineMessages()throws Exception{
  Activity h=home();final boolean[] cancelled={false};
  String longTitle="The Extraordinary Adventures of the Midnight Lighthouse Keepers";
  String longText="A retired lighthouse keeper and her grandson discover a hidden map inside an old radio and set out along the coast, "
   +"meeting smugglers, storm chasers and a travelling circus. Every chapter reveals another secret about the family, the town and the light "
   +"that has guided ships home for a hundred years. Some episodes include flashing lights and scenes of peril that younger viewers may find intense.";
  main(()->Ui.dialog(h,longTitle,longText,new String[]{"Play","Close"},0,i->{},()->cancelled[0]=true));Dialog d=shown(null);
  checkLayout(d,longTitle);
  main(()->{TextView t=(TextView)text(root(d),longTitle);assertTrue("title wraps to two lines at most",t.getLineCount()<=2);
   TextView m=(TextView)text(root(d),longText);assertNotNull(m);assertTrue("long message capped at six lines",m.getLineCount()<=6||m.getLayout().getEllipsisCount(5)>0);});
  assertEquals("Play",focused(d));screenshot("long-message");
  backDismisses(d);assertTrue("Back runs the cancel action",cancelled[0]);
  String info="Audio tracks:\n  • Dolby Digital Plus 5.1 · English · 48 kHz · playing\n  • AAC stereo · Spanish · 48 kHz · available\n  • DTS 5.1 · French · can't be played on this TV\n"
   +"Decoder: c2.android.aac.decoder\nSound output: pcm16 2ch 48k decoded\nThis TV: HDMI · stereo\nAudio problems: none\nVideo: video/hevc 3840x2160";
  main(()->Ui.dialog(h,"Playback info",info,new String[]{"Close"},0,i->{},null));Dialog i2=shown(d);
  checkLayout(i2,"Playback info");
  main(()->{TextView m=(TextView)text(root(i2),info);assertEquals("information reads from the left",Gravity.LEFT,Gravity.getAbsoluteGravity(m.getGravity(),m.getLayoutDirection())&Gravity.HORIZONTAL_GRAVITY_MASK);
   assertTrue("every line shown ("+m.getLineCount()+")",m.getLineCount()>=9&&m.getLineCount()<=12&&m.getLayout().getEllipsisCount(m.getLineCount()-1)==0);});
  screenshot("multi-line-info");backDismisses(i2);
 }
 public void testThreeActionsNoticePickerAndProgress()throws Exception{
  Activity h=home();
  main(()->Ui.dialog(h,"Resolved fixture","Your reported issue has been marked resolved.",new String[]{"Watch","Dismiss","Later"},0,i->{},null));Dialog n=shown(null);
  checkLayout(n,"Resolved fixture");assertEquals("Watch",focused(n));
  key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("Dismiss",focused(n));key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("Later",focused(n));
  key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("Later",focused(n));screenshot("three-actions");backDismisses(n);
  main(()->Ui.notice(h,"Update available","A new version of Flix Town is ready. Update now for the latest improvements.","Version 3.8.21 · What's new: smoother profiles",
   new String[]{"Update now","Later"},0,true,i->{},null));Dialog u=shown(n);
  checkLayout(u,"Update available");assertEquals("Update now",focused(u));screenshot("update-notice");backDismisses(u);
  main(()->Ui.picker(h,"Sort by",new String[]{"Recently added","Title A–Z","Title Z–A","Release year","Rating"},1,i->{}));Dialog p=shown(u);
  main(()->assertNotNull(text(root(p),"Sort by")));assertEquals("the current choice is focused","✓  Title A–Z",focused(p));
  key(KeyEvent.KEYCODE_DPAD_DOWN);assertEquals("Title Z–A",focused(p));screenshot("picker");backDismisses(p);
  final Ui.Progress[] progress={null};main(()->{progress[0]=Ui.progress(h,"Downloading update","Hide",null);progress[0].set("Downloading… 6.2 of 6.6 MB",64);});Dialog g=shown(p);
  checkLayout(g,"Downloading update");assertEquals("Hide",focused(g));screenshot("progress");main(()->progress[0].dismiss());
 }
 private void screenshot(String name)throws Exception{
  Thread.sleep(400);getInstrumentation().waitForIdleSync();
  android.graphics.Bitmap image=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
  java.io.File dir=new java.io.File(c.getExternalFilesDir(null),"profiles-ui");assertTrue(dir.isDirectory()||dir.mkdirs());
  try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"dialog-"+name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
 }
 @Override protected void tearDown()throws Exception{
  main(()->{Dialog d=Ui.lastShown;if(d!=null&&d.isShowing())d.dismiss();Ui.lastShown=null;if(activity!=null)activity.finishAffinity();Profiles.fixture(null);AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();});
  getInstrumentation().waitForIdleSync();AccountStore.clear(c);super.tearDown();
 }
}
