package com.flixtown.tv;

import android.app.Activity;
import android.content.*;
import android.graphics.Rect;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Profile picker presentation on the TV: real D-pad key events through the window, layout inside the
 * screen (also at the focused scale), one balanced row for one to six profiles, long names, the editor,
 * and the loading, offline and error states. Screenshots go to files/profiles-ui/design-*.png.
 * Offline fixtures only; no account or panel is contacted.
 */
public class ProfilesDesignDeviceTest extends InstrumentationTestCase {
 private Activity activity;private Context c;
 private static final String[] IDS={"12345678-1234-4234-8234-123456789012","22345678-1234-4234-8234-123456789012","32345678-1234-4234-8234-123456789012",
  "42345678-1234-4234-8234-123456789012","52345678-1234-4234-8234-123456789012"};
 private static final String[] NAMES={"Alexandria Montgomery-Li","Movie night","Saturday Morning Cartoon","Guest","Weekend watchers club"};
 private static final String[] AVATARS={"avatar_09","avatar_14","avatar_22","avatar_28","avatar_05"};
 private volatile CountDownLatch hold;private volatile boolean fail;
 private void main(Runnable r){final Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{r.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
 private void waitFor(Callable<Boolean> p)throws Exception{long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(p.call())return;Thread.sleep(80);}fail("design test timed out");}
 private ProfileCatalog catalog(int count){
  List<ProfileCatalog.Profile> list=new ArrayList<>();list.add(new ProfileCatalog.Profile("default","My profile","avatar_01",false));
  for(int i=0;i<count-1;i++)list.add(new ProfileCatalog.Profile(IDS[i],NAMES[i],AVATARS[i],false));
  return new ProfileCatalog(list,"default");
 }
 @Override protected void setUp()throws Exception{super.setUp();c=getInstrumentation().getTargetContext();getInstrumentation().setInTouchMode(false);
  main(()->{AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();});}
 private void open(int count,boolean cached,boolean offline)throws Exception{
  final ProfileCatalog data=catalog(count);
  main(()->{Profiles.raw(c).edit().clear().putString(SignOut.OWNER,SignOut.ownerId("fixture")).putString("server","https://demo.invalid").putString("progress_sync_api_url","https://fixture.invalid/api/tv-account-sync.php").commit();
   try{AccountStore.save(c,"fixture","fixture-password","https://demo.invalid");}catch(Exception e){throw new IllegalStateException(e);}
   Profiles.save(c,Profiles.scope(c),data,1);
   Profiles.fixture((url,b)->{
    if(b.optString("action").equals("session"))return new JSONObject().put("ok",true).put("session","fixture");
    CountDownLatch h=hold;if(h!=null)h.await(20,TimeUnit.SECONDS);
    if(fail)return new JSONObject().put("ok",false).put("error","unavailable");
    JSONArray a=new JSONArray();for(ProfileCatalog.Profile p:data.profiles)a.put(p.json());return new JSONObject().put("ok",true).put("profiles_version",1).put("revision",2).put("profiles",a);
   });});
  Intent intent=new Intent(c,ProfilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(cached)intent.putExtra("cached",true).putExtra("offline",offline);
  activity=getInstrumentation().startActivitySync(intent);getInstrumentation().waitForIdleSync();
 }
 private boolean busy(){final boolean[] b={true};main(()->{try{java.lang.reflect.Field f=ProfilesActivity.class.getDeclaredField("busy");f.setAccessible(true);b[0]=f.getBoolean(activity);}catch(Exception e){throw new IllegalStateException(e);}});return b[0];}
 private View tree(){return activity.findViewById(android.R.id.content);}
 private View text(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=text(((ViewGroup)v).getChildAt(i),s);if(f!=null)return f;}return null;}
 private View described(View v,String d){if(v.getContentDescription()!=null&&d.contentEquals(v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=described(((ViewGroup)v).getChildAt(i),d);if(f!=null)return f;}return null;}
 private View tile(String name){View t=text(tree(),name);assertNotNull(name,t);return (View)t.getParent();}
 private View focus(){final View[] f={null};main(()->f[0]=activity.getCurrentFocus());return f[0];}
 private void key(int code){getInstrumentation().sendKeyDownUpSync(code);getInstrumentation().waitForIdleSync();}
 private Rect screen(View v){int[] p=new int[2];v.getLocationOnScreen(p);return new Rect(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());}
 private int width(){return c.getResources().getDisplayMetrics().widthPixels;}
 private int height(){return c.getResources().getDisplayMetrics().heightPixels;}
 /** Inside the screen, also when scaled around its centre by {@code scale} (the focused size). */
 private void onScreen(String what,View v,float scale){
  Rect r=screen(v);assertTrue(what+" laid out",r.width()>0&&r.height()>0);
  float gx=r.width()*(scale-1)/2f,gy=r.height()*(scale-1)/2f;
  assertTrue(what+" inside the screen "+r,r.left-gx>=0&&r.top-gy>=0&&r.right+gx<=width()&&r.bottom+gy<=height());
 }
 private List<String> names(int count){List<String> l=new ArrayList<>();l.add("My profile");for(int i=0;i<count-1;i++)l.add(NAMES[i]);return l;}
 /** Every tile in one row, inside the screen at the focused scale, the row centred, avatars not overlapping. */
 private Map<String,Rect> checkRow(int count){
  final Map<String,Rect> out=new LinkedHashMap<>();
  main(()->{List<String> n=names(count);Rect prev=null;int top=-1;
   for(String name:n){View t=tile(name);View frame=((ViewGroup)t).getChildAt(0);onScreen(name+" tile",t,1f);onScreen(name+" avatar",frame,1.06f);
    Rect r=screen(t);out.put(name,r);if(top<0)top=r.top;assertEquals(name+" in the same row",top,r.top);
    Rect f=screen(frame);float grow=f.width()*0.03f;if(prev!=null)assertTrue(name+" scaled avatar clear of its neighbour",f.left-grow>=prev.right);prev=new Rect(f.left,f.top,Math.round(f.right+grow),f.bottom);
    TextView label=(TextView)text(tree(),name);assertTrue(name+" fits two lines",label.getLineCount()<=2);}
   Rect first=out.get(n.get(0)),last=out.get(n.get(n.size()-1));int centre=(first.left+last.right)/2;
   assertTrue("row centred ("+centre+" vs "+width()/2+")",Math.abs(centre-width()/2)<=c.getResources().getDisplayMetrics().density*4);
  });return out;
 }
 public void testSixProfilesLongNamesDpad()throws Exception{
  open(6,true,false);List<String> n=names(6);
  assertSame("the current profile has focus",tile("My profile"),focus());
  Map<String,Rect> before=checkRow(6);screenshot("picker-6-first");
  for(int i=1;i<6;i++){key(KeyEvent.KEYCODE_DPAD_RIGHT);assertSame("RIGHT reaches "+n.get(i),tile(n.get(i)),focus());}
  key(KeyEvent.KEYCODE_DPAD_RIGHT);assertSame("RIGHT at the end stays on the last profile",tile(n.get(5)),focus());
  Thread.sleep(300);screenshot("picker-6-last-long-name");
  main(()->{for(String name:n)assertEquals(name+" did not move when focus moved",before.get(name),screen(tile(name)));});
  for(int i=4;i>=0;i--){key(KeyEvent.KEYCODE_DPAD_LEFT);assertSame("LEFT reaches "+n.get(i),tile(n.get(i)),focus());}
  key(KeyEvent.KEYCODE_DPAD_DOWN);main(()->assertSame("DOWN reaches Manage profiles (no Add at six)",text(tree(),"Manage profiles"),activity.getCurrentFocus()));
  main(()->assertNull("no Add profile at six profiles",text(tree(),"+  Add profile")));
  screenshot("picker-6-manage-focused");
  key(KeyEvent.KEYCODE_DPAD_UP);main(()->assertTrue("UP returns to a profile",n.contains(String.valueOf(((TextView)((ViewGroup)activity.getCurrentFocus()).getChildAt(1)).getText()))));
  key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);
  main(()->{assertNotNull(text(tree(),"Manage profiles"));assertNotNull(text(tree(),"Done"));});
  checkRow(6);Thread.sleep(300);screenshot("manage-6");
  key(KeyEvent.KEYCODE_BACK);main(()->{assertNotNull("Back leaves Manage profiles",text(tree(),"Who’s watching?"));assertNull(text(tree(),"Done"));});
 }
 public void testOneToFiveProfilesBalanced()throws Exception{
  for(int count:new int[]{1,2,3,4,5}){
   open(count,true,false);checkRow(count);
   main(()->{for(String s:new String[]{"Developer","Preview","Kids"})assertFalse(s+" not mentioned",screenText(tree()).contains(s));});
   Thread.sleep(300);screenshot("picker-"+count);
   key(KeyEvent.KEYCODE_DPAD_DOWN);
   main(()->{View add=text(tree(),"+  Add profile"),manage=text(tree(),"Manage profiles");assertNotNull(add);assertNotNull(manage);
    assertTrue("DOWN reaches a secondary action",activity.getCurrentFocus()==add||activity.getCurrentFocus()==manage);onScreen("Add profile",add,1f);onScreen("Manage profiles",manage,1f);
    assertTrue("secondary actions are smaller than profile names",((TextView)add).getTextSize()<((TextView)text(tree(),"My profile")).getTextSize());});
   key(KeyEvent.KEYCODE_DPAD_LEFT);main(()->assertSame("LEFT reaches Add profile",text(tree(),"+  Add profile"),activity.getCurrentFocus()));
   key(KeyEvent.KEYCODE_DPAD_RIGHT);main(()->assertSame("RIGHT reaches Manage profiles",text(tree(),"Manage profiles"),activity.getCurrentFocus()));
   key(KeyEvent.KEYCODE_DPAD_UP);main(()->assertTrue("UP returns to a profile",activity.getCurrentFocus()!=null&&activity.getCurrentFocus().getParent() instanceof LinearLayout&&((ViewGroup)activity.getCurrentFocus()).getChildCount()==2));
   if(count==4){Thread.sleep(300);screenshot("picker-4-manage-button-focused");}
   main(()->activity.finish());getInstrumentation().waitForIdleSync();
  }
 }
 public void testEditorDpadAndLayout()throws Exception{
  open(6,true,false);
  key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);
  main(()->assertSame("Manage profiles focuses the current profile",tile("My profile"),activity.getCurrentFocus()));
  key(KeyEvent.KEYCODE_DPAD_RIGHT);main(()->assertSame(tile(NAMES[0]),activity.getCurrentFocus()));
  key(KeyEvent.KEYCODE_DPAD_CENTER);
  waitFor(()->{final boolean[] e={false};main(()->e[0]=activity.getCurrentFocus() instanceof EditText);return e[0];});
  main(()->{EditText name=(EditText)activity.getCurrentFocus();assertFalse(name.getText().toString().isEmpty());
   for(int i=1;i<=28;i++)onScreen("Avatar "+i,described(tree(),"Avatar "+i),1.1f);
   onScreen("Save",text(tree(),"Save"),1.04f);onScreen("Cancel",text(tree(),"Cancel"),1.04f);onScreen("name field",name,1f);
   for(String s:new String[]{"Kids","Preview"})assertFalse(s+" not mentioned",screenText(tree()).contains(s));});
  screenshot("editor-name-focused");
  key(KeyEvent.KEYCODE_DPAD_RIGHT);
  main(()->{View f=activity.getCurrentFocus();assertNotNull(f);assertTrue("RIGHT from the name goes to the pictures",String.valueOf(f.getContentDescription()).startsWith("Avatar "));});
  key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_DOWN);
  final String[] chosen={null};main(()->chosen[0]=String.valueOf(activity.getCurrentFocus().getContentDescription()));
  key(KeyEvent.KEYCODE_DPAD_CENTER);
  main(()->{try{java.lang.reflect.Field f=ProfilesActivity.class.getDeclaredField("avatar");f.setAccessible(true);
   assertEquals("DPAD_CENTER selects the focused picture",String.format(Locale.ROOT,"avatar_%02d",Integer.parseInt(chosen[0].substring(7))),f.get(activity));}catch(Exception e){throw new IllegalStateException(e);}});
  Thread.sleep(300);screenshot("editor-picture-focused");
  for(int i=0;i<4;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
  main(()->{View f=activity.getCurrentFocus();assertTrue("DOWN from the pictures reaches the actions",f instanceof TextView&&Arrays.asList("Save","Cancel","Delete profile").contains(String.valueOf(((TextView)f).getText())));});
  Thread.sleep(300);screenshot("editor-actions");
  key(KeyEvent.KEYCODE_BACK);main(()->assertNotNull("Back returns to Manage profiles",text(tree(),"Done")));
 }
 public void testLoadingThenReady()throws Exception{
  hold=new CountDownLatch(1);
  try{open(3,false,false);
   main(()->{assertNotNull(text(tree(),"Loading profiles…"));assertNotNull(text(tree(),"Who’s watching?"));});
   Thread.sleep(400);screenshot("loading");
  }finally{hold.countDown();}
  waitFor(()->!busy());
  main(()->{assertNull("loading message cleared",text(tree(),"Loading profiles…"));assertNotNull(text(tree(),"Manage profiles"));assertEquals(1f,tile("My profile").getAlpha());});
 }
 public void testOfflineAndError()throws Exception{
  open(4,true,true);checkRow(4);
  main(()->{assertNotNull(text(tree(),"Retry"));assertNull(text(tree(),"Manage profiles"));assertNull(text(tree(),"+  Add profile"));
   assertTrue("offline explained",screenText(tree()).contains("offline"));});
  key(KeyEvent.KEYCODE_DPAD_DOWN);main(()->assertSame("DOWN reaches Retry",text(tree(),"Retry"),activity.getCurrentFocus()));
  Thread.sleep(300);screenshot("offline");
  main(()->activity.finish());getInstrumentation().waitForIdleSync();
  fail=true;open(4,false,false);waitFor(()->!busy());
  main(()->{assertNotNull(text(tree(),"Retry"));assertTrue("error explained",screenText(tree()).contains("temporarily unavailable"));});
  Thread.sleep(300);screenshot("error");
  fail=false;
 }
 private String screenText(View v){StringBuilder b=new StringBuilder();collect(v,b);return b.toString();}
 private void collect(View v,StringBuilder b){if(v.getVisibility()!=View.VISIBLE)return;if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),b);}
 private void screenshot(String name)throws Exception{
  Thread.sleep(400);getInstrumentation().waitForIdleSync();
  android.graphics.Bitmap image=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
  java.io.File dir=new java.io.File(c.getExternalFilesDir(null),"profiles-ui");assertTrue(dir.isDirectory()||dir.mkdirs());
  try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"design-"+name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
 }
 @Override protected void tearDown()throws Exception{
  if(hold!=null)hold.countDown();
  main(()->{if(activity!=null)activity.finish();Profiles.fixture(null);Profiles.signOut();});getInstrumentation().waitForIdleSync();AccountStore.clear(c);super.tearDown();
 }
}
