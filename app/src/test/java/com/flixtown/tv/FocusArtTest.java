package com.flixtown.tv;

import static org.junit.Assert.*;

import org.junit.Test;

public class FocusArtTest {
    private static final String POSTER="https://img.example/poster/m1.jpg";

    @Test public void thePosterIsNeverUsedAsBackground(){
        assertNull(FocusArt.landscape(POSTER,"",POSTER,null));
        assertNull("no art at all: branded background",FocusArt.landscape(POSTER,null,"","  "));
    }
    @Test public void firstRealLandscapeArtWins(){
        assertEquals("https://img.example/bd/m1.jpg",FocusArt.landscape(POSTER,"","https://img.example/bd/m1.jpg","https://other/x.jpg"));
        assertEquals("a Continue Watching backdrop comes first","https://cw/x.jpg",FocusArt.landscape(POSTER,"https://cw/x.jpg","https://img.example/bd/m1.jpg"));
        assertNull("not a web address",FocusArt.landscape(POSTER,"file:///sdcard/x.jpg","javascript:alert(1)"));
    }
    @Test public void smallTmdbRenditionsAreAskedForLarge(){
        String w300="https://image.tmdb.org/t/p/w300/abc.jpg";
        assertEquals("https://image.tmdb.org/t/p/original/abc.jpg",FocusArt.sized(w300,1920));
        assertEquals("https://image.tmdb.org/t/p/w1280/abc.jpg",FocusArt.sized(w300,1280));
        assertEquals("https://image.tmdb.org/t/p/w1280/abc.jpg",FocusArt.sized("https://image.tmdb.org/t/p/w780/abc.jpg",1280));
        assertEquals("https://image.tmdb.org/t/p/original/abc.jpg",FocusArt.sized("https://image.tmdb.org/t/p/w1280/abc.jpg",1920));
    }
    @Test public void largeOrOtherAddressesAreLeftAlone(){
        assertEquals("https://image.tmdb.org/t/p/original/abc.jpg",FocusArt.sized("https://image.tmdb.org/t/p/original/abc.jpg",1280));
        assertEquals("https://image.tmdb.org/t/p/w1280/abc.jpg",FocusArt.sized("https://image.tmdb.org/t/p/w1280/abc.jpg",1280));
        assertEquals("https://cdn.example/w300/abc.jpg",FocusArt.sized("https://cdn.example/w300/abc.jpg",1920));
        assertNull(FocusArt.sized(null,1920));
    }
    @Test public void decodeWidthFollowsTheScreen(){
        assertEquals(1920,FocusArt.decodeWidth(1920));
        assertEquals(1280,FocusArt.decodeWidth(1280));
        assertEquals("4K screens: 1920 is enough under the dim layer",1920,FocusArt.decodeWidth(3840));
    }
    @Test public void onlyBigLandscapePicturesAreShown(){
        assertTrue(FocusArt.suitable(1920,1080,1920));
        assertTrue(FocusArt.suitable(1280,720,1920));          // two-thirds of the width: acceptable
        assertTrue(FocusArt.suitable(1280,720,1280));
        assertFalse("thumbnail",FocusArt.suitable(800,450,1920));
        assertFalse("poster",FocusArt.suitable(1000,1500,1920));
        assertFalse("square",FocusArt.suitable(1200,1200,1920));
        assertFalse(FocusArt.suitable(0,0,1920));
    }
}
