package com.flixtown.tv;

import androidx.media3.common.text.Cue;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitlePlacementTest {
    @Test public void insetsAreFivePercentSidesAndTopThreePointFiveBottom(){
        assertArrayEquals(new int[]{96,54,96,38},SubtitlePlacement.insets(1920,1080));
        assertArrayEquals(new int[]{64,36,64,25},SubtitlePlacement.insets(1280,720));
    }
    @Test public void ordinaryCuesAreNotPositioned(){
        assertFalse(SubtitlePlacement.positioned(Cue.DIMEN_UNSET,Cue.DIMEN_UNSET,false,false));
    }
    @Test public void cuesWithTheirOwnPlacementKeepTheVideoFrame(){
        assertTrue(SubtitlePlacement.positioned(0.9f,Cue.DIMEN_UNSET,false,false));   // line set (ASS \\pos, CEA-608 row)
        assertTrue(SubtitlePlacement.positioned(Cue.DIMEN_UNSET,0.5f,false,false));   // position set
        assertTrue(SubtitlePlacement.positioned(Cue.DIMEN_UNSET,Cue.DIMEN_UNSET,true,false));   // image subtitles
        assertTrue(SubtitlePlacement.positioned(Cue.DIMEN_UNSET,Cue.DIMEN_UNSET,false,true));   // vertical text
    }
    @Test public void controlsUpRaiseTheBottomInsetAboveTheBar(){
        // 1080p screen, control bar from y = 843: text ends 22 px (2 %) above it
        int lift=SubtitlePlacement.lift(1080,843);
        assertEquals(1080-843+22,lift);
        assertArrayEquals(new int[]{96,54,96,259},SubtitlePlacement.insets(1920,1080,lift));
        // controls hidden: back to the normal 3.5 % bottom inset
        assertArrayEquals(SubtitlePlacement.insets(1920,1080),SubtitlePlacement.insets(1920,1080,0));
    }
    @Test public void aBarBelowTheLayerNeedsNoLift(){
        assertEquals(0,SubtitlePlacement.lift(803,900));   // letterboxed picture ends above the bar
        assertEquals(0,SubtitlePlacement.lift(0,500));
        assertEquals(0,SubtitlePlacement.lift(803,-20));   // bar over the whole layer: no squeeze to nothing
        assertEquals(0,SubtitlePlacement.lift(1080,300));
    }
    @Test public void aSmallLiftNeverLowersTheNormalInset(){
        assertEquals(38,SubtitlePlacement.insets(1920,1080,10)[3]);
    }
}
