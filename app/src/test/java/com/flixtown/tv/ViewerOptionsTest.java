package com.flixtown.tv;
import org.junit.Test;
import static org.junit.Assert.*;
public class ViewerOptionsTest {
 @Test public void speedOnlyAllowsSupportedValues(){for(float v:ViewerOptions.SPEEDS)assertEquals(v,ViewerOptions.speed(v),0);assertEquals(1f,ViewerOptions.speed(0),0);assertEquals(1f,ViewerOptions.speed(Float.NaN),0);assertEquals(1f,ViewerOptions.speed(100),0);}
 @Test public void sizeOnlyAllowsSupportedValues(){for(float v:ViewerOptions.SIZES)assertEquals(v,ViewerOptions.size(v),0);assertEquals(1f,ViewerOptions.size(Float.POSITIVE_INFINITY),0);assertEquals(1f,ViewerOptions.size(-1),0);}
 @Test public void approvalIsNotReady(){assertFalse(ViewerOptions.ready("approved"));assertFalse(ViewerOptions.ready("pending"));assertFalse(ViewerOptions.ready("partial"));assertFalse(ViewerOptions.ready("declined"));assertTrue(ViewerOptions.ready("available"));assertTrue(ViewerOptions.ready("local"));}
 @Test public void noticeIsOncePerAvailabilityTransition(){assertTrue(ViewerOptions.newlyReady("pending","available"));assertTrue(ViewerOptions.newlyReady("approved","local"));assertTrue(ViewerOptions.newlyReady("","available"));assertFalse(ViewerOptions.newlyReady("available","local"));assertFalse(ViewerOptions.newlyReady("local","available"));assertFalse(ViewerOptions.newlyReady("pending","approved"));}
 @Test public void watchedEpisodesCannotCollideAcrossShowsOrMovies(){assertEquals("watched_movie:10",ViewerOptions.watchedKey("movie","10",null));assertEquals("watched_series:10:20",ViewerOptions.watchedKey("series","10","20"));assertNotEquals(ViewerOptions.watchedKey("series","10","20"),ViewerOptions.watchedKey("series","11","20"));}
 @Test(expected=IllegalArgumentException.class) public void episodeRequiresId(){ViewerOptions.watchedKey("series","10","");}
 @Test(expected=IllegalArgumentException.class) public void watchedTypeValidated(){ViewerOptions.watchedKey("unknown","10",null);}
 @Test public void personalDataClearedAcrossAccounts(){assertTrue(SignOut.isPersonalKey("watched_movie:10"));assertTrue(SignOut.isPersonalKey("request_seen_movie:10"));assertFalse(SignOut.isPersonalKey(ViewerOptions.STYLE));assertFalse(SignOut.isPersonalKey(ViewerOptions.SPEED));}
 @Test public void selectingFromEpisodeListKeepsNeighbors(){EpisodeNavigation n=new EpisodeNavigation(new String[]{"a","b","c"},null,null,0,true);assertTrue(n.select(2));assertEquals("c",n.current().id);assertEquals("b",n.adjacent(-1).id);assertNull(n.adjacent(1));assertFalse(n.select(-1));assertFalse(n.select(3));assertEquals(2,n.index());}
}
