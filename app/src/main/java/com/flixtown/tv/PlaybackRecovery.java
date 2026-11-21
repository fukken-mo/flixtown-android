package com.flixtown.tv;

/**
 * What the player does when a stream fails with a connection error (the server dropped the transfer,
 * refused the request or timed out). IPTV servers often refuse a new connection for a few seconds, for
 * example while the previous episode's stream still counts against the account's connection limit, so a
 * connection error is retried quietly a few times before "This title couldn't be played" is shown.
 * In an episode's closing part (see {@link EpisodeEndRecovery}) autoplay simply goes on to the next one.
 * A format error (the TV can't decode the video) is never retried.
 */
final class PlaybackRecovery {
    static final int SHOW_ERROR = 0, RETRY = 1, ADVANCE = 2;
    /** Waits before each quiet retry: about 22 s in all, on top of the player's own short retries. */
    private static final long[] RETRY_DELAYS_MS = {2_000, 4_000, 8_000, 8_000};
    /** Playback that has run this far since the last retry counts as recovered: the next failure starts afresh. */
    static final long RECOVERED_AFTER_MS = 15_000;

    private PlaybackRecovery() {}

    static int decide(boolean connectionError, int retriesSoFar, boolean inEpisodeTail, boolean canAutoplayNext) {
        if (!connectionError) return SHOW_ERROR;
        if (inEpisodeTail && canAutoplayNext) return ADVANCE;
        return retriesSoFar < RETRY_DELAYS_MS.length ? RETRY : SHOW_ERROR;
    }

    static long retryDelayMs(int retriesSoFar) {
        return RETRY_DELAYS_MS[Math.max(0, Math.min(retriesSoFar, RETRY_DELAYS_MS.length - 1))];
    }

    static String name(int action) {
        return action == ADVANCE ? "advance" : action == RETRY ? "retry" : "error";
    }
}
