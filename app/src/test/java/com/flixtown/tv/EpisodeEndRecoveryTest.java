package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class EpisodeEndRecoveryTest {
    @Test public void recoversWhenAPlayingEpisodeLosesItsStreamAtTheEnd() {
        assertTrue(EpisodeEndRecovery.shouldAdvance(600_000, 593_000, 590_000));
    }

    @Test public void theClosingPartIsTheLast30SecondsOrLast5PercentUpToTwoMinutes() {
        assertEquals(30_000, EpisodeEndRecovery.tailWindowMs(300_000));       // 5 min: 30 s
        assertEquals(66_000, EpisodeEndRecovery.tailWindowMs(1_320_000));     // 22 min: 66 s of credits
        assertEquals(120_000, EpisodeEndRecovery.tailWindowMs(3_600_000));    // 60 min: at most 2 min
        // 10 s before the end of a 2-minute episode (the QA case): advances
        assertTrue(EpisodeEndRecovery.shouldAdvance(120_000, 110_500, 108_000));
        // a failure in the credits of a 22-minute episode, 50 s before the end: advances
        assertTrue(EpisodeEndRecovery.shouldAdvance(1_320_000, 1_270_000, 1_266_000));
    }

    @Test public void keepsRealPlaybackErrorsVisible() {
        assertFalse(EpisodeEndRecovery.shouldAdvance(600_000, 540_000, 535_000));   // a minute before the end
        assertFalse(EpisodeEndRecovery.shouldAdvance(600_000, 0, 0));               // the next episode failing to open
        assertFalse(EpisodeEndRecovery.shouldAdvance(600_000, 598_000, 200_000));   // jumped straight to the end
        assertFalse(EpisodeEndRecovery.shouldAdvance(-1, 598_000, 595_000));        // length unknown
    }
}
