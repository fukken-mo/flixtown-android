package com.flixtown.tv;

/**
 * Was the episode really playing into its closing part when its stream failed? Then autoplay may go on
 * to the next episode instead of stopping on "This title couldn't be played".
 */
final class EpisodeEndRecovery {
    private static final long RECENT_PLAYBACK_MS = 10_000;

    private EpisodeEndRecovery() {}

    /**
     * The closing part of an episode: its last 30 s, or its last 5 % for longer episodes (at most 2 minutes),
     * where credits play. A failure earlier than this is a real interruption and is shown, never skipped.
     */
    static long tailWindowMs(long durationMs) {
        return Math.max(30_000, Math.min(120_000, durationMs / 20));
    }

    static boolean shouldAdvance(long durationMs, long positionMs, long lastPlayingPositionMs) {
        // The progress sampler only records positions while video is playing.
        // An error opening the next episode or seeking straight to the end cannot qualify.
        return durationMs > 0 && lastPlayingPositionMs > 0
            && positionMs >= lastPlayingPositionMs
            && positionMs - lastPlayingPositionMs <= RECENT_PLAYBACK_MS
            && positionMs <= durationMs && durationMs - positionMs <= tailWindowMs(durationMs);
    }
}
