package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackRecoveryTest {
    @Test public void aConnectionErrorInTheClosingPartGoesOnToTheNextEpisode() {
        assertEquals(PlaybackRecovery.ADVANCE, PlaybackRecovery.decide(true, 0, true, true));
        assertEquals(PlaybackRecovery.ADVANCE, PlaybackRecovery.decide(true, 4, true, true));
    }

    @Test public void otherConnectionErrorsAreRetriedQuietlyThenShown() {
        for (int i = 0; i < 4; i++) assertEquals(PlaybackRecovery.RETRY, PlaybackRecovery.decide(true, i, false, true));
        assertEquals(PlaybackRecovery.SHOW_ERROR, PlaybackRecovery.decide(true, 4, false, true));
        // the last episode (or autoplay off): the closing part is retried, not skipped
        assertEquals(PlaybackRecovery.RETRY, PlaybackRecovery.decide(true, 0, true, false));
    }

    @Test public void formatErrorsAreShownAtOnce() {
        assertEquals(PlaybackRecovery.SHOW_ERROR, PlaybackRecovery.decide(false, 0, true, true));
        assertEquals(PlaybackRecovery.SHOW_ERROR, PlaybackRecovery.decide(false, 0, false, true));
    }

    @Test public void retriesWaitLongerEachTimeAndCoverAShortRefusal() {
        assertEquals(2_000, PlaybackRecovery.retryDelayMs(0));
        assertEquals(4_000, PlaybackRecovery.retryDelayMs(1));
        assertEquals(8_000, PlaybackRecovery.retryDelayMs(3));
        long total = 0;for (int i = 0; i < 4; i++) total += PlaybackRecovery.retryDelayMs(i);
        assertTrue("about 20 s of patience", total >= 20_000);
    }
}
