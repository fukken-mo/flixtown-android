package com.flixtown.tv;
import android.app.Activity;
import android.content.*;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.Button;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.*;
import java.util.concurrent.Callable;

/** Real Media3 local video, real D-pad/OK; no live panel, credentials or Jellyfin. */
public class IntroSkipDeviceTest extends InstrumentationTestCase {
 private PlayerActivity a;
 private void main(Runnable r){getInstrumentation().runOnMainSync(r);}
 private Object field(String n)throws Exception{Field f=PlayerActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(a);}
 private void set(String n,Object v)throws Exception{Field f=PlayerActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
 private void update()throws Exception{Method m=PlayerActivity.class.getDeclaredMethod("updateIntroButton");m.setAccessible(true);m.invoke(a);}
 private void waitFor(Callable<Boolean> f)throws Exception{long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){getInstrumentation().waitForIdleSync();if(f.call())return;Thread.sleep(50);}fail("Intro test timed out");}
 private ExoPlayer player()throws Exception{return (ExoPlayer)field("player");}
 private Button button()throws Exception{return (Button)field("skipIntro");}
 @Override protected void setUp()throws Exception{super.setUp();getInstrumentation().setInTouchMode(false);
  a=(PlayerActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("url","asset:///intro-test.mp4").putExtra("title","Intro test"));
  waitFor(()->player().getDuration()>0&&a.hasWindowFocus());
 }
 @Override protected void tearDown()throws Exception{if(a!=null)main(a::finish);super.tearDown();}
 private void marker(boolean playing)throws Exception{main(()->{try{player().setPlayWhenReady(playing);player().seekTo(9000);set("introMarker",new IntroMarker(8000,12000,player().getDuration()));update();}catch(Exception e){throw new RuntimeException(e);}});waitFor(()->button().getVisibility()==View.VISIBLE);}
 public void testSkipWithRemoteAndNoFocusSteal()throws Exception{
  marker(true);assertFalse(button().hasFocus());getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);waitFor(()->button().hasFocus());
  getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);waitFor(()->player().getCurrentPosition()>=12000&&button().getVisibility()==View.GONE);
  assertTrue(player().getPlayWhenReady());
 }
 public void testPausedSkipPreservesPauseAndBackReturnsToControls()throws Exception{
  marker(false);getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);waitFor(()->button().hasFocus());
  getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor(()->!button().hasFocus());assertFalse(a.isFinishing());
  getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);waitFor(()->button().hasFocus());
  getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);waitFor(()->player().getCurrentPosition()>=12000);assertFalse(player().getPlayWhenReady());
 }
 public void testMissingMismatchedAndOutsideMarkersStayHidden()throws Exception{
  assertEquals(View.GONE,button().getVisibility());marker(false);
  main(()->{try{set("introMarker",new IntroMarker(8000,12000,player().getDuration()+10000));update();}catch(Exception e){throw new RuntimeException(e);}});
  assertEquals(View.GONE,button().getVisibility());
  main(()->{try{set("introMarker",new IntroMarker(18000,22000,player().getDuration()));update();}catch(Exception e){throw new RuntimeException(e);}});
  assertEquals(View.GONE,button().getVisibility());
 }
}
