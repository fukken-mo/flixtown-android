package com.flixtown.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;

/** Offline QA catalog only. Checks the real Details card at the emulator's TV resolution. */
public class EpisodeWatchedDeviceTest extends InstrumentationTestCase {
    private Activity activity;
    private TextView firstBadge(){return (TextView)activity.findViewById(android.R.id.content).findViewWithTag("episode-watched-badge");}
    public void testEpisodeBadgeAndFocusRefresh()throws Exception{
        assertTrue(BuildConfig.DEMO);
        Context c=getInstrumentation().getTargetContext();
        AccountStore.save(c,"watched-fixture","fixture-password","https://demo.flixtown.invalid");
        Api.prefs(c).edit().putBoolean("watched_series:5002:9101",true)
            .putString("resume_episode_series:5002","9101").apply();
        Intent intent=new Intent(c,DetailsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("id","5002").putExtra("kind","series").putExtra("title","Fixture series");
        activity=getInstrumentation().startActivitySync(intent);
        long deadline=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<deadline){
            final boolean[] ready={false};getInstrumentation().runOnMainSync(()->ready[0]=firstBadge()!=null&&firstBadge().getWidth()>0);
            if(ready[0])break;Thread.sleep(100);
        }
        final Throwable[] error={null};
        getInstrumentation().runOnMainSync(()->{try{
            TextView badge=firstBadge();assertNotNull("episode card loaded",badge);
            assertEquals("✓ Watched",badge.getText().toString());assertEquals(View.VISIBLE,badge.getVisibility());
            assertEquals(0xFF83E5A7,badge.getCurrentTextColor());assertFalse("badge is not another remote stop",badge.isFocusable());
            ViewGroup card=(ViewGroup)badge.getParent();card.requestFocus();View focus=activity.getCurrentFocus();
            assertTrue(badge.getRight()<=card.getWidth());assertTrue(badge.getBottom()<=card.getHeight());
            for(int i=0;i<card.getChildCount();i++){
                View child=card.getChildAt(i);
                if(child instanceof TextView&&"Continue".contentEquals(((TextView)child).getText()))
                    assertTrue("badges do not overlap",child.getBottom()<=badge.getTop());
            }
            Api.prefs(c).edit().putBoolean("watched_series:5002:9101",false).apply();
            Method update=DetailsActivity.class.getDeclaredMethod("updateWatchedBadges",View.class);update.setAccessible(true);
            update.invoke(activity,card);assertEquals(View.GONE,badge.getVisibility());assertSame(focus,activity.getCurrentFocus());
            Api.prefs(c).edit().putBoolean("watched_series:5002:9101",true).apply();
            update.invoke(activity,card);assertEquals(View.VISIBLE,badge.getVisibility());assertSame(focus,activity.getCurrentFocus());
        }catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    @Override protected void tearDown()throws Exception{
        if(activity!=null)getInstrumentation().runOnMainSync(()->activity.finish());
        getInstrumentation().waitForIdleSync();
        Context c=getInstrumentation().getTargetContext();
        AccountStore.clear(c);Api.prefs(c).edit().remove("watched_series:5002:9101").remove("resume_episode_series:5002").apply();
        super.tearDown();
    }
}
