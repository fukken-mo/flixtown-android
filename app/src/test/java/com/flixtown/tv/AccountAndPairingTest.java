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
}
