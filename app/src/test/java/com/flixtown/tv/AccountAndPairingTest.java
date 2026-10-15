package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccountAndPairingTest {
    @Test public void pairingWaitsWhilePendingAndSignsInWhenApproved(){
        assertEquals(Pairing.WAIT,Pairing.next("pending"));
        assertEquals(Pairing.APPROVED,Pairing.next("approved"));
    }
    @Test public void usedOrUnknownCodesGetAFreshCode(){
        // The panel answers "redeemed" after handing the account out once; an expired code is a 404.
        assertEquals(Pairing.NEW_CODE,Pairing.next("redeemed"));
        assertEquals(Pairing.NEW_CODE,Pairing.next(""));
        assertEquals(Pairing.NEW_CODE,Pairing.afterError(404));
        assertEquals(Pairing.NEW_CODE,Pairing.afterError(400));
    }
    @Test public void serverAndRateLimitErrorsKeepTheSameCode(){
        assertEquals(Pairing.WAIT,Pairing.afterError(500));
        assertEquals(Pairing.WAIT,Pairing.afterError(503));
        assertEquals(Pairing.WAIT,Pairing.afterError(429));
    }
    @Test public void personalDataIsMyListProgressAndContinueWatchingOnly(){
        for(String k:new String[]{"favorites","continue_ids","resume_position_movie:1","resume_episode_series:5","resume_label_series:5","resume_art_movie:1"})
            assertTrue(k,SignOut.isPersonalKey(k));
        for(String k:new String[]{"autoplay_next","audio_language","subtitles_on","subtitle_language","login_keyboard_letters","server",
                "account_iv","account_data",SignOut.OWNER,"intro_url","requests_api_url"})
            assertFalse(k,SignOut.isPersonalKey(k));
    }
    @Test public void ownerFingerprintIsStableDistinctAndNotTheUsername(){
        String a=SignOut.ownerId("0048213977"),b=SignOut.ownerId("0048213978");
        assertEquals(a,SignOut.ownerId("0048213977"));
        assertNotEquals(a,b);
        assertEquals(64,a.length());
        assertFalse(a.contains("0048213977"));
    }
    @Test public void errorPagesThatAreNotJsonGiveAPlainMessage(){
        assertEquals("Code expired or invalid",Api.errorMessage(404,"{\"error\":\"Code expired or invalid\"}"));
        assertEquals("Server returned 502",Api.errorMessage(502,"<html><body>Bad gateway</body></html>"));
        assertEquals("Server returned 500",Api.errorMessage(500,"{\"error\":\"\"}"));
    }
    @Test public void logTextKeepsOnlySchemeAndHostOfAddresses(){
        String raw="Unable to connect to http://cdn.example.com:8080/series/0048213977/0000731946205518/9101.mkv?token=abc";
        String safe=Logs.redact(raw);
        assertTrue(safe,safe.contains("http://cdn.example.com:8080/[redacted]"));
        assertFalse(safe,safe.contains("0048213977"));assertFalse(safe,safe.contains("0000731946205518"));assertFalse(safe,safe.contains("abc"));
    }
    @Test public void logTextRemovesCredentialParametersOutsideUrls(){
        String safe=Logs.redact("player_api.php?username=0048213977&password=0000731946205518&action=get_series");
        assertFalse(safe,safe.contains("0048213977"));assertFalse(safe,safe.contains("0000731946205518"));
        assertTrue(safe,safe.contains("username=[redacted]") && safe.contains("password=[redacted]"));
    }
    @Test public void exceptionSummaryIncludesCausesButNoAddresses(){
        Exception e=new java.io.IOException("Source error",new IllegalStateException("GET https://s.example.com/movie/u1/p2/7.mp4 failed"));
        String safe=Logs.safe(e);
        assertTrue(safe,safe.startsWith("IOException: Source error <- IllegalStateException: GET https://s.example.com/[redacted]"));
        assertFalse(safe,safe.contains("p2"));
        assertEquals("",Logs.redact(null));
    }
    @Test public void queueCoversTheRestOfTheShowAcrossSeasons(){
        // 24 episodes (3 seasons of 8): S1E8 playing, next S2E1 at index 8 -> 15 later episodes, complete.
        assertArrayEquals(new int[]{9,15,0},EpisodeQueue.window(24,8,200));
    }
    @Test public void aCutOffQueueIsFlaggedSoTheShowIsNotTreatedAsFinished(){
        assertArrayEquals(new int[]{2,200,1},EpisodeQueue.window(500,1,200));
        assertArrayEquals(new int[]{2,1,1},EpisodeQueue.window(24,1,1));
        // the next episode is the last one: nothing later, and that is complete
        assertArrayEquals(new int[]{24,0,0},EpisodeQueue.window(24,23,200));
    }
}
