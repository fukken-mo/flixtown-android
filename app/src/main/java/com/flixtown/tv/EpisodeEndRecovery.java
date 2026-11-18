package com.flixtown.tv;

final class EpisodeEndRecovery {
    private static final long END_WINDOW_MS = 8_000;
    private static final long RECENT_PLAYBACK_MS = 10_000;

    private EpisodeEndRecovery() {}

    static boolean shouldAdvance(long durationMs, long positionMs, long lastPlayingPositionMs) {
        // The progress sampler only records positions while video is playing.
        // An error opening the next episode or seeking straight to the end cannot qualify.
        return durationMs > 0 && lastPlayingPositionMs > 0
            && positionMs >= lastPlayingPositionMs
            && positionMs - lastPlayingPositionMs <= RECENT_PLAYBACK_MS
            && positionMs <= durationMs && durationMs - positionMs <= END_WINDOW_MS;
    }
}
