package com.flixtown.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class EpisodeEndRecoveryTest {
    @Test public void recoversWhenAPlayingEpisodeLosesItsStreamAtTheEnd() {
        assertTrue(EpisodeEndRecovery.shouldAdvance(600_000, 593_000, 590_000));
    }

    @Test public void keepsRealPlaybackErrorsVisible() {
        assertFalse(EpisodeEndRecovery.shouldAdvance(600_000, 580_000, 575_000));
        assertFalse(EpisodeEndRecovery.shouldAdvance(600_000, 0, 0));
        assertFalse(EpisodeEndRecovery.shouldAdvance(600_000, 598_000, 200_000));
        assertFalse(EpisodeEndRecovery.shouldAdvance(-1, 598_000, 595_000));
    }
}
