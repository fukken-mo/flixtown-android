package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class EpisodeNavigationTest {
    private EpisodeNavigation show(int start,boolean complete){
        return new EpisodeNavigation(new String[]{"9101","9103","9201","9202"},
            new String[]{"mkv","mp4","mkv","mp4"},new String[]{"S1 E1","S1 E3","S2 E1","S2 E2"},start,complete);
    }
    @Test public void previousAndNextUseRealNeighborsEvenWhenEpisodeNumbersHaveGaps(){
        EpisodeNavigation n=show(1,true);
        assertEquals("9101",n.adjacent(-1).id);assertEquals("9201",n.adjacent(1).id);
        assertTrue(n.move(-1));assertEquals("9101",n.current().id);assertTrue(n.move(1));assertEquals("9103",n.current().id);
    }
    @Test public void forwardThenBackwardAcrossSeasonBoundaryDoesNotSkipOrDuplicate(){
        EpisodeNavigation n=show(1,true);
        assertTrue(n.move(1));assertEquals("S2 E1",n.current().label);
        assertTrue(n.move(-1));assertEquals("S1 E3",n.current().label);
        assertTrue(n.move(1));assertTrue(n.move(1));assertEquals("9202",n.current().id);
    }
    @Test public void firstAndLastBoundariesNeverMoveOrWrap(){
        EpisodeNavigation n=show(0,true);
        assertNull(n.adjacent(-1));assertFalse(n.move(-1));assertEquals(0,n.index());
        n.move(1);n.move(1);n.move(1);
        assertNull(n.adjacent(1));assertFalse(n.move(1));assertEquals("9202",n.current().id);
        assertFalse(n.move(2));assertFalse(n.move(0));
    }
    @Test public void returningToAnEarlierEpisodeDoesNotLoseItsFutureQueue(){
        EpisodeNavigation n=show(2,true);n.move(-1);n.move(-1);
        assertArrayEquals(new String[]{"9101","9103","9201","9202"},n.ids());
        n.move(1);n.move(1);n.move(1);assertEquals("9202",n.current().id);
    }
    @Test public void restoresCurrentEpisodeAndQueueAfterActivityRecreation(){
        EpisodeNavigation n=show(1,false);n.move(1);
        EpisodeNavigation restored=new EpisodeNavigation(n.ids(),n.extensions(),n.labels(),n.index(),n.completeAfter);
        assertEquals("9201",restored.current().id);assertEquals("mkv",restored.current().extension);
        assertEquals("9103",restored.adjacent(-1).id);assertEquals("9202",restored.adjacent(1).id);
        assertFalse(restored.completeAfter);
    }
    @Test public void incompleteQueueNeverBecomesKnownEndAfterMovingBackAndForward(){
        EpisodeNavigation n=show(3,false);assertFalse(n.completeAfter);assertNull(n.adjacent(1));
        n.move(-1);n.move(1);assertFalse(n.completeAfter);
    }
    @Test public void combinesDetailsHistoryCurrentNextAndLaterWithTheirOwnFormats(){
        EpisodeNavigation n=EpisodeNavigation.from(new String[]{"1"},new String[]{"avi"},null,
            "2","mkv","current","3","mp4","next",new String[]{"4"},null,null,false);
        assertArrayEquals(new String[]{"1","2","3","4"},n.ids());assertEquals(1,n.index());
        assertEquals("avi",n.adjacent(-1).extension);assertEquals("mp4",n.adjacent(1).extension);
        assertTrue(n.completeAfter);n.move(1);n.move(1);assertEquals("mp4",n.current().extension);
    }
    @Test public void missingListIsNotMistakenForSeriesCompletion(){
        EpisodeNavigation n=EpisodeNavigation.from(null,null,null,"4","mkv","","","","",null,null,null,false);
        assertFalse(n.completeAfter);assertNull(n.adjacent(-1));assertNull(n.adjacent(1));
    }
    @Test public void singleEpisodeHasNoNavigationTargets(){
        EpisodeNavigation n=EpisodeNavigation.from(null,null,null,"4","mkv","","","","",new String[0],null,null,false);
        assertTrue(n.completeAfter);assertNull(n.adjacent(-1));assertNull(n.adjacent(1));
    }
}
