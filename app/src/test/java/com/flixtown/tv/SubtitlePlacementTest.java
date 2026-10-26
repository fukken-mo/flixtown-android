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
}
