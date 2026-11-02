package com.flixtown.tv;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;

/**
 * Input isolation while a dialog recovers a lost focus notification (FocusRecovery). Each case forces a recovery
 * and holds the dialog unfocusable long enough to inject D-pad, OK and Back while it is unfocusable, then checks
 * that none of them reached the screen behind: a key spy sits on the screen's window under the recovery guard,
 * any new activity is blocked and counted, and the screen must not finish or move its focus. Two variants:
 * "full" (screen made unfocusable too, plus the key guard) and "guard only" (screen left focusable, so the window
 * manager really hands it focus and only the key guard stands between the keys and Home or the player).
 * Also: pending recovery is cancelled when the dialog closes or the activity pauses or is destroyed, and recovery
 * never starts while the keyboard or a newer window owns focus. Results go to files/profiles-ui/isolation-results.txt.
 */
public class DialogFocusIsolationDeviceTest extends InstrumentationTestCase {
 private Context c;private Activity home;private static final List<String> results=Collections.synchronizedList(new ArrayList<>());
 private static final long HOLD=1500;
 private void main(Runnable r){final Throwable[] error={null};getInstrumentation().runOnMainSync(()->{try{r.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
 private <T> T get(Callable<T> f){final Object[] out={null};main(()->{try{out[0]=f.call();}catch(Exception e){throw new RuntimeException(e);}});@SuppressWarnings("unchecked") T t=(T)out[0];return t;}
 private int num(Callable<Integer> f){Integer n=get(f);return n==null?0:n;}
 private boolean is(Callable<Boolean> f){Boolean b=get(f);return b!=null&&b;}
 private void waitFor(String what,long ms,Callable<Boolean> p)throws Exception{long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){if(get(p))return;Thread.sleep(25);}fail(what+" timed out");}
 private boolean within(long ms,Callable<Boolean> p)throws Exception{long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){if(get(p))return true;Thread.sleep(25);}return false;}
 @Override protected void setUp()throws Exception{super.setUp();c=getInstrumentation().getTargetContext();getInstrumentation().setInTouchMode(false);
  main(()->{AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();Ui.lastShown=null;
   Profiles.raw(c).edit().clear().putString(SignOut.OWNER,SignOut.ownerId("fixture")).putString("server","https://demo.flixtown.invalid").putString("progress_sync_api_url","").commit();
   try{AccountStore.save(c,"fixture","fixture-password","https://demo.flixtown.invalid");}catch(Exception e){throw new IllegalStateException(e);}
   Profiles.save(c,Profiles.scope(c),new ProfileCatalog(Collections.singletonList(new ProfileCatalog.Profile("default","My profile","avatar_01",false)),"default"),1);});
  main(()->Profiles.returningHome(c));
  home=getInstrumentation().startActivitySync(new Intent(c,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  waitFor("Home",15000,()->home.findViewById(R.id.home_rows)!=null&&home.hasWindowFocus());Thread.sleep(1500);
 }

 // ---- helpers ----
 /** Records every key that reaches a window's callback, then passes it on. */
 private static final class Spy {
  final Window window;final Window.Callback original,proxy;final List<String> keys=Collections.synchronizedList(new ArrayList<>());
  Spy(Window w){window=w;original=w.getCallback();
   proxy=(Window.Callback)Proxy.newProxyInstance(Spy.class.getClassLoader(),new Class<?>[]{Window.Callback.class},(self,m,args)->{
    if(args!=null&&args.length==1&&args[0] instanceof KeyEvent&&m.getName().startsWith("dispatchKey")){KeyEvent e=(KeyEvent)args[0];keys.add(KeyEvent.keyCodeToString(e.getKeyCode())+(e.getAction()==0?"↓":"↑"));}
    if("equals".equals(m.getName()))return self==args[0];if("hashCode".equals(m.getName()))return System.identityHashCode(self);
    try{return m.invoke(original,args);}catch(java.lang.reflect.InvocationTargetException x){throw x.getCause();}});
   w.setCallback(proxy);}
  void remove(){if(window.getCallback()==proxy)window.setCallback(original);}
 }
 /** A non-cancelable dialog whose two actions only record a click, so every key stays with the dialog. */
 private Dialog recordingDialog(Activity a,List<String> heard){
  Dialog d=Ui.newDialog(a);LinearLayout body=Ui.sheet(a,"Input isolation","Keys pressed while this dialog recovers focus must stay here.");
  LinearLayout row=Ui.row(a);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);rp.topMargin=Ui.dp(a,26);body.addView(row,rp);
  for(String label:new String[]{"First","Second"}){Button b=Ui.dialogButton(a,label);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,Ui.dp(a,50));
   if(row.getChildCount()>0)p.leftMargin=Ui.dp(a,12);row.addView(b,p);b.setOnClickListener(v->heard.add("click "+label));}
  d.setContentView(body);d.setCancelable(false);
  d.setOnKeyListener((x,code,e)->{heard.add(KeyEvent.keyCodeToString(code)+(e.getAction()==0?"↓":"↑"));return false;});
  Ui.showWindow(a,d,540,false,0);row.getChildAt(0).post(row.getChildAt(0)::requestFocus);return d;
 }
 private void inject(int code){UiAutomationHolder.inject(getInstrumentation(),code);}
 private static final class UiAutomationHolder{static void inject(Instrumentation in,int code){long t=SystemClock.uptimeMillis();
  for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}){KeyEvent e=new KeyEvent(t,SystemClock.uptimeMillis(),action,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
   if(!in.getUiAutomation().injectInputEvent(e,false))throw new AssertionError("could not inject "+code);}}}
 /** A window FocusRecovery does not know about. */
 private static Dialog plain(Activity a){Dialog f=new Dialog(a);f.requestWindowFeature(Window.FEATURE_NO_TITLE);Button b=new Button(a);b.setText("Plain window");
  b.setOnClickListener(v->b.setText("clicked"));f.setContentView(b);f.show();b.requestFocus();return f;}
 private boolean notFocusable(Window w){return (w.getAttributes().flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0;}
 private String focusName(Activity a){View f=a.getCurrentFocus();return f==null?"none":f.getClass().getSimpleName()+"@"+Integer.toHexString(System.identityHashCode(f));}

 /** Forces a recovery on a recording dialog over {@code screen}, injects D-pad, OK and Back while it is unfocusable, and checks isolation. */
 private void isolate(Activity screen,String where,boolean blockScreen)throws Exception{isolate(screen,where,blockScreen,null,null);}
 /** As above; with {@code lower} (an older dialog still open under the one recovering), it must hear nothing either. */
 private void isolate(Activity screen,String where,boolean blockScreen,Dialog lower,List<String> lowerHeard)throws Exception{
  List<String> heard=Collections.synchronizedList(new ArrayList<>());
  Dialog d=get(()->recordingDialog(screen,heard));
  waitFor(where+" dialog focus",5000,()->d.getWindow().getDecorView().hasWindowFocus()&&d.getCurrentFocus() instanceof Button);Thread.sleep(400);
  Spy spy=get(()->new Spy(screen.getWindow()));
  Spy lowerSpy=lower==null?null:get(()->new Spy(lower.getWindow()));
  int lowerFlags=lower==null?0:num(()->lower.getWindow().getAttributes().flags),screenFlags=num(()->screen.getWindow().getAttributes().flags);
  if(lowerHeard!=null)lowerHeard.clear();boolean[] lowerBlockedDuringHold={false};
  Instrumentation.ActivityMonitor monitor=getInstrumentation().addMonitor((IntentFilter)null,null,true);
  String focusBefore=get(()->focusName(screen));int blockedBefore=FocusRecovery.keysBlocked;heard.clear();
  boolean[] screenFocusDuringHold={false};
  try{
   assertTrue(where+": recovery could not be forced",is(()->FocusRecovery.forceForTest(d,HOLD,blockScreen)));
   waitFor(where+" hold",2000,()->FocusRecovery.holdingForTest()&&notFocusable(d.getWindow()));
   // Guard only: wait until the window manager has really handed focus to the screen behind.
   // With an older dialog open, that dialog is the topmost window left and is the one the window manager picks.
   Callable<Boolean> behindFocused=lower!=null?()->lower.getWindow().getDecorView().hasWindowFocus():screen::hasWindowFocus;
   if(!blockScreen)waitFor(where+" focus behind during hold",1200,behindFocused);
   screenFocusDuringHold[0]=is(behindFocused);
   if(lower!=null)lowerBlockedDuringHold[0]=is(()->notFocusable(lower.getWindow())&&notFocusable(screen.getWindow()));
   if(lower!=null&&blockScreen)assertTrue(where+": the lower dialog and the screen must be unfocusable during the hold",lowerBlockedDuringHold[0]);
   inject(KeyEvent.KEYCODE_DPAD_RIGHT);inject(KeyEvent.KEYCODE_DPAD_DOWN);inject(KeyEvent.KEYCODE_DPAD_CENTER);inject(KeyEvent.KEYCODE_BACK);
   boolean stillHolding=is(()->FocusRecovery.holdingForTest()&&notFocusable(d.getWindow()));
   assertTrue(where+": the keys must be injected while the dialog is unfocusable",stillHolding);
   waitFor(where+" recovery end",HOLD+4000,()->FocusRecovery.activeForTest(d)==0&&d.getWindow().getDecorView().hasWindowFocus());
   Thread.sleep(800);getInstrumentation().waitForIdleSync();
   int blocked=FocusRecovery.keysBlocked-blockedBefore;
   String line=where+(blockScreen?" [full]":" [guard only]")+": injected RIGHT, DOWN, OK, BACK while unfocusable; screen behind heard "+spy.keys
    +(lower==null?"":"; lower dialog heard "+lowerSpy.keys+" / "+lowerHeard+", lower dialog unfocusable during hold "+lowerBlockedDuringHold[0])
    +"; guard blocked "+blocked+"; dialog heard "+heard+"; new activities "+monitor.getHits()+"; "+(lower==null?"screen":"lower dialog")+" had window focus during hold: "+screenFocusDuringHold[0];
   results.add(line);
   assertTrue(line,spy.keys.isEmpty());
   if(lower!=null){
    assertTrue(line,lowerSpy.keys.isEmpty());assertTrue(line,lowerHeard.isEmpty());assertTrue(where+": lower dialog must stay open",is(lower::isShowing));
    assertEquals(where+": lower dialog flags must be restored exactly",lowerFlags,num(()->lower.getWindow().getAttributes().flags));
    assertTrue(where+": lower dialog's key guard must be removed",is(()->lower.getWindow().getCallback()==lowerSpy.proxy));
   }
   assertEquals(where+": screen flags must be restored exactly",screenFlags,num(()->screen.getWindow().getAttributes().flags));
   assertEquals(line,0,monitor.getHits());
   assertFalse(line,is(screen::isFinishing));
   String focusAfter=get(()->focusName(screen));assertEquals(line,focusBefore,focusAfter);
   assertTrue(where+": the dialog must still be open (Back stayed with it)",is(d::isShowing));
   if(!blockScreen)assertTrue(where+": in the guard-only variant the keys reached the screen window and must have been blocked there ("+line+")",blocked>0);
   assertFalse(where+": screen must be focusable again",is(()->notFocusable(screen.getWindow())));
   assertFalse(where+": dialog must be focusable again",is(()->notFocusable(d.getWindow())));
   assertTrue(where+": the key guard must be removed",is(()->screen.getWindow().getCallback()==spy.proxy));
   // And the dialog takes the remote normally afterwards.
   String before=get(()->((TextView)d.getCurrentFocus()).getText().toString());
   getInstrumentation().sendKeyDownUpSync("Second".equals(before)?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT);getInstrumentation().waitForIdleSync();
   String after=get(()->d.getCurrentFocus() instanceof TextView?((TextView)d.getCurrentFocus()).getText().toString():"none");
   assertFalse(where+": the dialog must take the remote after recovery ("+before+" → "+after+")",before.equals(after));
  }finally{getInstrumentation().removeMonitor(monitor);main(()->{spy.remove();if(lowerSpy!=null)lowerSpy.remove();if(d.isShowing())d.dismiss();});Thread.sleep(700);}
 }

 public void testKeysDuringRecoveryNeverReachHome()throws Exception{
  isolate(home,"Home",true);
  isolate(home,"Home",false);
 }

 public void testStackedDialogsTopRecoveryNeverReachesLowerDialogOrHome()throws Exception{
  List<String> lowerHeard=Collections.synchronizedList(new ArrayList<>());
  Dialog lower=get(()->recordingDialog(home,lowerHeard));
  waitFor("lower dialog focus",5000,()->lower.getWindow().getDecorView().hasWindowFocus());Thread.sleep(300);
  try{
   isolate(home,"Stacked over Home",true,lower,lowerHeard);
   waitFor("lower dialog focus again",5000,()->lower.getWindow().getDecorView().hasWindowFocus());Thread.sleep(300);
   isolate(home,"Stacked over Home",false,lower,lowerHeard);
  }finally{main(()->{if(lower.isShowing())lower.dismiss();});Thread.sleep(600);}
 }

 /** Cleanup while the top dialog is held: the lower dialog closes, or the top one does. */
 public void testStackedDialogsCleanupWhenEitherCloses()throws Exception{
  Window.Callback screenCallback=get(home.getWindow()::getCallback);int screenFlags=num(()->home.getWindow().getAttributes().flags);
  // 1. The lower dialog closes in the middle of the top dialog's recovery.
  List<String> h1=new ArrayList<>(),h2=new ArrayList<>();
  Dialog lower=get(()->recordingDialog(home,h1));waitFor("lower focus",5000,()->lower.getWindow().getDecorView().hasWindowFocus());
  Dialog top=get(()->recordingDialog(home,h2));waitFor("top focus",5000,()->top.getWindow().getDecorView().hasWindowFocus());Thread.sleep(300);
  assertTrue(is(()->FocusRecovery.forceForTest(top,3000,true)));
  waitFor("hold",2000,()->FocusRecovery.holdingForTest()&&notFocusable(lower.getWindow())&&notFocusable(home.getWindow()));
  main(lower::dismiss);
  String lowerGone="lower dialog closed during the top dialog's hold: top recovery still pending "+(num(()->FocusRecovery.activeForTest(top))>0)
   +", lower callback restored "+is(()->lower.getWindow().getCallback()==lower);
  assertTrue(lowerGone,is(()->lower.getWindow().getCallback()==lower));
  waitFor("top recovery end",8000,()->FocusRecovery.activeForTest(top)==0&&top.getWindow().getDecorView().hasWindowFocus());
  lowerGone+=", then screen flags restored "+is(()->home.getWindow().getAttributes().flags==screenFlags)+", screen callback restored "+is(()->home.getWindow().getCallback()==screenCallback)
   +", top focusable and focused "+is(()->!notFocusable(top.getWindow())&&top.getWindow().getDecorView().hasWindowFocus());
  results.add(lowerGone);
  assertEquals(lowerGone,screenFlags,num(()->home.getWindow().getAttributes().flags));
  assertTrue(lowerGone,is(()->home.getWindow().getCallback()==screenCallback));
  assertFalse(lowerGone,is(()->notFocusable(top.getWindow())));
  main(top::dismiss);Thread.sleep(700);
  // 2. The top dialog closes in the middle of its own recovery: the lower dialog comes back as it was.
  Dialog lower2=get(()->recordingDialog(home,h1));waitFor("lower focus",5000,()->lower2.getWindow().getDecorView().hasWindowFocus());
  int lowerFlags=num(()->lower2.getWindow().getAttributes().flags);
  Dialog top2=get(()->recordingDialog(home,h2));waitFor("top focus",5000,()->top2.getWindow().getDecorView().hasWindowFocus());Thread.sleep(300);
  assertTrue(is(()->FocusRecovery.forceForTest(top2,3000,true)));
  waitFor("hold",2000,()->FocusRecovery.holdingForTest()&&notFocusable(lower2.getWindow()));
  int tries=FocusRecovery.recoveries;
  main(top2::dismiss);
  String topGone="top dialog closed during its hold: pending for it "+num(()->FocusRecovery.activeForTest(top2))+", lower flags restored "+is(()->lower2.getWindow().getAttributes().flags==lowerFlags)
   +", lower callback restored "+is(()->lower2.getWindow().getCallback()==lower2)+", screen flags restored "+is(()->home.getWindow().getAttributes().flags==screenFlags)
   +", screen callback restored "+is(()->home.getWindow().getCallback()==screenCallback);
  assertEquals(topGone,0,num(()->FocusRecovery.activeForTest(top2)));
  assertEquals(topGone,lowerFlags,num(()->lower2.getWindow().getAttributes().flags));
  assertTrue(topGone,is(()->lower2.getWindow().getCallback()==lower2));
  assertEquals(topGone,screenFlags,num(()->home.getWindow().getAttributes().flags));
  assertTrue(topGone,is(()->home.getWindow().getCallback()==screenCallback));
  waitFor("lower dialog focus after the top closed",5000,()->lower2.getWindow().getDecorView().hasWindowFocus());
  // The lower dialog takes the remote again.
  String before=get(()->((TextView)lower2.getCurrentFocus()).getText().toString());
  getInstrumentation().sendKeyDownUpSync("Second".equals(before)?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT);getInstrumentation().waitForIdleSync();
  String after=get(()->((TextView)lower2.getCurrentFocus()).getText().toString());
  topGone+=", lower dialog takes the remote ("+before+" → "+after+")";results.add(topGone);
  assertFalse(topGone,before.equals(after));
  main(lower2::dismiss);Thread.sleep(600);
 }

 public void testKeysDuringRecoveryNeverReachPlayer()throws Exception{
  Intent i=new Intent(c,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   .putExtra("url","http://127.0.0.1:9/fixture.mp4").putExtra("title","Isolation fixture");
  Activity player=getInstrumentation().startActivitySync(i);
  try{
   waitFor("player",10000,player::hasWindowFocus);
   // Let the unreachable stream settle into its error card, so the player's own state is steady.
   within(20000,()->{View card=player.findViewById(R.id.player_card);return card!=null&&card.getVisibility()==View.VISIBLE&&player.hasWindowFocus();});
   Thread.sleep(800);
   isolate(player,"Player",true);
   isolate(player,"Player",false);
  }finally{main(player::finish);Thread.sleep(800);}
 }

 public void testRecoveryCancelledWhenDialogCloses()throws Exception{
  List<String> heard=new ArrayList<>();Dialog d=get(()->recordingDialog(home,heard));
  waitFor("dialog focus",5000,()->d.getWindow().getDecorView().hasWindowFocus());Thread.sleep(300);
  Window.Callback screenCallback=get(home.getWindow()::getCallback);
  assertTrue(is(()->FocusRecovery.forceForTest(d,4000,true)));
  waitFor("hold",2000,FocusRecovery::holdingForTest);
  assertTrue("screen unfocusable during hold",is(()->notFocusable(home.getWindow())));
  int tries=FocusRecovery.recoveries;
  main(d::dismiss);
  String line="dismiss during hold: pending for dialog "+get(()->FocusRecovery.activeForTest(d))+", screen focusable "+!is(()->notFocusable(home.getWindow()))
   +", guard removed "+get(()->home.getWindow().getCallback()==screenCallback);
  Integer pending=get(()->FocusRecovery.activeForTest(d));assertEquals(line,0,pending.intValue());
  assertFalse(line,is(()->notFocusable(home.getWindow())));
  assertTrue(line,is(()->home.getWindow().getCallback()==screenCallback));
  assertFalse(line,is(FocusRecovery::holdingForTest));
  // The released hold would have ended at 4 s and retried; nothing of it may run now.
  Thread.sleep(4500);
  assertEquals("no recovery work may run for a closed dialog",tries,FocusRecovery.recoveries);
  waitFor("Home focus after close",3000,home::hasWindowFocus);
  results.add(line+"; tries after close "+(FocusRecovery.recoveries-tries)+"; Home has focus again");
 }

 public void testRecoveryCancelledWhenActivityPausesOrIsDestroyed()throws Exception{
  List<String> heard=new ArrayList<>();Dialog d=get(()->recordingDialog(home,heard));
  waitFor("dialog focus",5000,()->d.getWindow().getDecorView().hasWindowFocus());Thread.sleep(300);
  Window.Callback screenCallback=get(home.getWindow()::getCallback);
  assertTrue(is(()->FocusRecovery.forceForTest(d,4000,true)));
  waitFor("hold",2000,FocusRecovery::holdingForTest);
  Instrumentation.ActivityMonitor guide=getInstrumentation().addMonitor(RemoteGuideActivity.class.getName(),null,false);
  c.startActivity(new Intent(c,RemoteGuideActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  Activity over=getInstrumentation().waitForMonitorWithTimeout(guide,8000);getInstrumentation().removeMonitor(guide);
  assertNotNull("second screen opened",over);
  int tries=FocusRecovery.recoveries;
  waitFor("pause cancels recovery",3000,()->FocusRecovery.activeForTest(d)==0&&FocusRecovery.activeForTest(home)==0);
  String pausedLine="activity paused during hold: pending 0, screen focusable "+!is(()->notFocusable(home.getWindow()))+", dialog focusable "+!is(()->notFocusable(d.getWindow()))
   +", guard removed "+get(()->home.getWindow().getCallback()==screenCallback);
  assertFalse(pausedLine,is(()->notFocusable(home.getWindow())));
  assertFalse(pausedLine,is(()->notFocusable(d.getWindow())));
  assertTrue(pausedLine,is(()->home.getWindow().getCallback()==screenCallback));
  Thread.sleep(4500);
  assertEquals("no recovery work may run while paused",tries,FocusRecovery.recoveries);
  main(over::finish);
  waitFor("back to the dialog",8000,()->d.getWindow().getDecorView().hasWindowFocus());Thread.sleep(400);
  // Destroyed: Home finishes in the middle of a hold.
  assertTrue(is(()->FocusRecovery.forceForTest(d,4000,true)));
  waitFor("hold",2000,FocusRecovery::holdingForTest);
  main(home::finish);
  waitFor("destroy cancels recovery",5000,()->FocusRecovery.activeForTest(null)==0&&!FocusRecovery.holdingForTest());
  int afterFinish=FocusRecovery.recoveries;Thread.sleep(4500);
  assertEquals("no recovery work may run after the activity is gone",afterFinish,FocusRecovery.recoveries);
  results.add(pausedLine+"; activity finished during hold: pending 0, no later tries");
  home=null;
 }

 public void testNoRecoveryWhileKeyboardOrAnotherWindowOwnsFocus()throws Exception{
  // A newer dialog on top owns focus: the one below is never recovered.
  Dialog below=get(()->Ui.dialog(home,"Below","Covered by a newer dialog.",new String[]{"One","Two"},0,i->{},null));
  waitFor("below focus",5000,()->below.getWindow().getDecorView().hasWindowFocus());
  Dialog above=get(()->Ui.dialog(home,"Above","The newest dialog owns focus.",new String[]{"One","Two"},0,i->{},null));
  waitFor("above focus",5000,()->above.getWindow().getDecorView().hasWindowFocus());
  int tries=FocusRecovery.recoveries;
  assertFalse("a covered dialog must not be recovered",is(()->FocusRecovery.forceForTest(below,HOLD,true)));
  // A window FocusRecovery does not know about (a plain dialog) takes focus from a dialog that had it.
  Dialog foreign=get(()->plain(home));
  waitFor("foreign focus",5000,()->foreign.getWindow().getDecorView().hasWindowFocus());
  Thread.sleep(2500);
  String windowLine="newer dialog on top: covered dialog not recovered; plain window over a dialog: tries "+(FocusRecovery.recoveries-tries)
   +", plain window keeps focus "+get(()->foreign.getWindow().getDecorView().hasWindowFocus());
  assertEquals(windowLine,tries,FocusRecovery.recoveries);
  assertTrue(windowLine,is(()->foreign.getWindow().getDecorView().hasWindowFocus()));
  main(()->{foreign.dismiss();above.dismiss();below.dismiss();});Thread.sleep(800);
  // A plain window opens in the same moment as a dialog, so the dialog may never hear focus: at most one try,
  // and the plain window keeps focus and the remote.
  int t0=FocusRecovery.recoveries;
  Dialog[] pair=get(()->new Dialog[]{Ui.dialog(home,"Same moment","Opened with another window.",new String[]{"One","Two"},0,i->{},null),plain(home)});
  waitFor("plain window focus",5000,()->pair[1].getWindow().getDecorView().hasWindowFocus());Thread.sleep(2500);
  int sameMoment=FocusRecovery.recoveries-t0;boolean keeps=is(()->pair[1].getWindow().getDecorView().hasWindowFocus());
  getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);getInstrumentation().waitForIdleSync();
  boolean plainHeard=is(()->"clicked".equals(((Button)((ViewGroup)pair[1].findViewById(android.R.id.content)).getChildAt(0)).getText().toString()));
  String sameLine="plain window opened with a dialog: tries "+sameMoment+", plain window keeps focus "+keeps+", OK reached the plain window "+plainHeard;
  results.add(sameLine);
  assertTrue(sameLine,sameMoment<=1);assertTrue(sameLine,keeps);assertTrue(sameLine,plainHeard);
  main(()->{pair[1].dismiss();pair[0].dismiss();});Thread.sleep(800);
  // The on-screen keyboard is up for a text field in a dialog: recovery stays away.
  final EditText[] field={null};
  Dialog typing=get(()->{Dialog t=Ui.newDialog(home);LinearLayout body=Ui.sheet(home,"Type a name",null);EditText e=new EditText(home);e.setSingleLine(true);e.setTextColor(0xFFFFFFFF);
   body.addView(e,new LinearLayout.LayoutParams(-1,Ui.dp(home,54)));t.setContentView(body);Ui.showWindow(home,t,540,false,0);field[0]=e;return t;});
  waitFor("typing focus",5000,()->typing.getWindow().getDecorView().hasWindowFocus());
  main(()->{field[0].requestFocus();((InputMethodManager)home.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field[0],InputMethodManager.SHOW_FORCED);});
  boolean keyboard=within(8000,()->{if(!FocusRecovery.keyboardShown(home)){((InputMethodManager)home.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field[0],0);return false;}return true;});
  String keyboardLine;
  if(keyboard){
   int before=FocusRecovery.recoveries;
   boolean started=is(()->FocusRecovery.forceForTest(typing,HOLD,true));
   keyboardLine="keyboard up for a dialog text field: recovery started "+started+", tries "+(FocusRecovery.recoveries-before)+", dialog focusable "+!is(()->notFocusable(typing.getWindow()));
   assertFalse(keyboardLine,started);
   assertEquals(keyboardLine,before,FocusRecovery.recoveries);
  }else keyboardLine="keyboard: this emulator image showed no on-screen keyboard within 8 s, so the keyboard rule was not exercised here";
  results.add(windowLine);results.add(keyboardLine);
  main(()->{((InputMethodManager)home.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field[0].getWindowToken(),0);typing.dismiss();});Thread.sleep(600);
 }

 @Override protected void tearDown()throws Exception{
  main(()->{Dialog d=Ui.lastShown;
   // A dialog left over a screen that the test already finished went with that screen's window.
   try{if(d!=null&&d.isShowing())d.dismiss();}catch(IllegalArgumentException gone){}
   Ui.lastShown=null;if(home!=null)home.finishAffinity();AccountSync.signOut();StartupRefresh.reset();Profiles.signOut();});
  getInstrumentation().waitForIdleSync();AccountStore.clear(c);
  java.io.File dir=new java.io.File(c.getExternalFilesDir(null),"profiles-ui");dir.mkdirs();
  try(java.io.FileWriter out=new java.io.FileWriter(new java.io.File(dir,"isolation-results.txt"))){out.write("API "+android.os.Build.VERSION.SDK_INT+"\n");synchronized(results){for(String r:results)out.write(r+"\n");}}
  super.tearDown();
 }
}
