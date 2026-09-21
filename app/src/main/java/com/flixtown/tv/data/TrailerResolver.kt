package com.flixtown.tv.data

sealed interface TrailerSource {
    data class DirectVideo(val url: String) : TrailerSource
    data class YouTube(val videoId: String) : TrailerSource
    data object None : TrailerSource
}

/**
 * Resolves the raw trailer value Xtream returns (`youtube_trailer`) into
 * something the app can actually act on. Xtream panels are inconsistent
 * about this field: sometimes a bare 11-character YouTube video ID,
 * sometimes a full YouTube URL, occasionally a direct playable video URL.
 *
 * ExoPlayer cannot play a youtube.com/youtu.be URL directly — it's an HTML
 * page, not a media container — so YouTube results are meant to be opened
 * via an external intent (the YouTube app/browser), not fed to Media3.
 *
 * TMDB fallback is not implemented this pass (see project notes); this
 * resolver only consumes Xtream metadata, but callers depend on
 * [TrailerSource] rather than the raw string, so adding a fallback source
 * later doesn't require touching call sites.
 */
object TrailerResolver {

    private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE_URL_ID_REGEX = Regex("(?:youtu\\.be/|[?&]v=|/embed/|/shorts/)([A-Za-z0-9_-]{11})")

    fun resolve(rawTrailer: String?): TrailerSource {
        val value = rawTrailer?.trim()
        if (value.isNullOrEmpty()) return TrailerSource.None

        if (YOUTUBE_ID_REGEX.matches(value)) {
            return TrailerSource.YouTube(value)
        }

        if (value.contains("youtube.com", ignoreCase = true) || value.contains("youtu.be", ignoreCase = true)) {
            val id = YOUTUBE_URL_ID_REGEX.find(value)?.groupValues?.get(1)
            return if (id != null) TrailerSource.YouTube(id) else TrailerSource.None
        }

        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return TrailerSource.DirectVideo(value)
        }

        return TrailerSource.None
    }
}
