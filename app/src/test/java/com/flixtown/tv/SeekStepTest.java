package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SeekStepTest {
    @Test public void aPressIsTenSecondsAndHoldingSpeedsUp(){
        assertEquals(10_000,SeekStep.step(0));
        assertEquals(10_000,SeekStep.step(1_499));
        assertEquals(30_000,SeekStep.step(1_500));
        assertEquals(60_000,SeekStep.step(3_000));
        assertEquals(180_000,SeekStep.step(5_000));
        assertEquals(180_000,SeekStep.step(60_000));
    }
    @Test public void targetsStayInsideTheVideoAndShortOfTheEnd(){
        assertEquals(0,SeekStep.clamp(-20_000,7_200_000));
        assertEquals(7_195_000,SeekStep.clamp(9_000_000,7_200_000));   // never within 5 s of the end
        assertEquals(1_000,SeekStep.clamp(1_000,7_200_000));
        assertEquals(0,SeekStep.clamp(3_000,4_000));                    // very short clip
        assertEquals(9_000_000,SeekStep.clamp(9_000_000,0));            // unknown duration: only the start bounds it
    }
    @Test public void reachingTheMiddleOfATwoHourMovieTakesSecondsNotMinutes(){
        // Holding Right from 0: one step per ~150 ms repeat.
        long pos=0,held=0;int steps=0;
        while(pos<3_600_000){pos+=SeekStep.step(held);held+=150;steps++;}
        assertTrue("steps="+steps,held<10_000);
    }
    @Test public void timesAndOffsetsReadNaturally(){
        assertEquals("1:02:30",SeekStep.time(3_750_000));
        assertEquals("4:05",SeekStep.time(245_000));
        assertEquals("+12:00",SeekStep.offset(3_000_000,3_720_000));
        assertEquals("−1:00",SeekStep.offset(120_000,60_000));
        assertEquals(3_750_000,SeekStep.compose(1,2,30));
        assertArrayEquals(new int[]{1,2,30},SeekStep.split(3_750_999));
    }
}
