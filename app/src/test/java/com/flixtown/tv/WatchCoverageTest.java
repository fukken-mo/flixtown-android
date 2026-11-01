package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchCoverageTest {
    private WatchCoverage watch(long duration,long from,long to){
        WatchCoverage c=new WatchCoverage(duration,"");play(c,from,to,0);return c;
    }
    private void play(WatchCoverage c,long from,long to,long clock){
        c.discontinuity();c.sample(from,clock,true,1f);
        for(long p=from+500;p<=to;p+=500)c.sample(p,clock+p-from,true,1f);
    }
    @Test public void creditsThresholdRequiresCoverageAndPosition(){
        WatchCoverage c=watch(1800000,0,1620000);
        assertTrue(c.complete(1620000));assertFalse(c.complete(1619999));
        WatchCoverage hour=watch(3600000,0,3240000);
        assertFalse(hour.complete(3240000));play(hour,3240000,3420000,4000000);
        assertTrue(hour.complete(3420000));
    }
    @Test public void seekingNearEndDoesNotComplete(){
        WatchCoverage c=watch(1800000,0,60000);
        c.discontinuity();c.sample(1700000,70000,true,1f);c.sample(1700500,70500,true,1f);
        assertFalse(c.complete(1700500));assertEquals(60500,c.viewed());
    }
    @Test public void missedSeekCallbackIsRejected(){
        WatchCoverage c=watch(1800000,0,1000);c.sample(1700000,1500,true,1f);
        assertEquals(1000,c.viewed());assertFalse(c.complete(1700000));
    }
    @Test public void repeatedSpansAreNotDoubleCounted(){
        WatchCoverage c=watch(1800000,0,60000);play(c,0,60000,100000);
        assertEquals(60000,c.viewed());play(c,30000,90000,200000);assertEquals(90000,c.viewed());
    }
    @Test public void pausesBufferingAndLifecycleGapsDoNotCount(){
        WatchCoverage c=watch(1800000,0,1000);
        c.sample(2000,2000,false,1f);c.sample(12000,12000,true,1f);
        c.sample(13000,13000,true,1f);assertEquals(2000,c.viewed());
        c.sample(18000,18000,true,1f);assertEquals(2000,c.viewed());
    }
    @Test public void playbackSpeedCountsMediaTime(){
        WatchCoverage c=new WatchCoverage(100000,"");c.sample(0,0,true,1.5f);
        c.sample(1500,1000,true,1.5f);assertEquals(1500,c.viewed());
    }
    @Test public void restoredCoverageContinuesWithoutCountingResumeSeek(){
        WatchCoverage a=watch(1800000,0,900000);
        WatchCoverage b=new WatchCoverage(1800000,a.encode());play(b,900000,1620000,0);
        assertTrue(b.complete(1620000));assertEquals(1620000,b.viewed());
    }
    @Test public void corruptOrDifferentDurationIsDiscarded(){
        assertEquals(0,new WatchCoverage(1000,"1000;0:900;500:1000").viewed());
        assertEquals(0,new WatchCoverage(1000,"1000;0:9000").viewed());
        assertEquals(0,new WatchCoverage(1000,"2000;0:900").viewed());
        assertEquals(0,new WatchCoverage(1000,"broken").viewed());
        assertFalse(new WatchCoverage(0,"").complete(0));
    }
    @Test public void excessiveFragmentationStaysBoundedAndConservative(){
        WatchCoverage c=new WatchCoverage(10000000,"");
        for(int i=0;i<300;i++)play(c,i*3000,i*3000+500,i*4000);
        assertTrue(c.encode().length()<14000);assertEquals(256*500,c.viewed());
    }
    @Test public void invalidSpeedOrPositionCannotAddCoverage(){
        WatchCoverage c=new WatchCoverage(1000,"");c.sample(0,0,true,1f);c.sample(500,500,true,Float.NaN);
        assertEquals(0,c.viewed());c.sample(2000,1000,true,1f);assertFalse(c.complete(2000));
    }
}
