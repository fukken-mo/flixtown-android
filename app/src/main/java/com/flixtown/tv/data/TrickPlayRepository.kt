package com.flixtown.tv.data

import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val TAG = "TrickPlayRepository"

/**
 * Base URL for the trick-play assets branch, served through jsDelivr's free
 * GitHub CDN. Thumbnails are generated once, offline, by the
 * generate-trickplay.yml GitHub Actions workflow (which runs FFmpeg on a
 * GitHub-hosted runner — see SEEK-PREVIEW-FUTURE.md for why: this app's own
 * backend host's FFmpeg/shell availability isn't something this session can
 * verify, but GitHub Actions is infrastructure already proven to work here)
 * and committed to the `trickplay-assets` branch, not this app's own
 * default branch.
 */
private const val TRICKPLAY_BASE_URL = "https://cdn.jsdelivr.net/gh/fukken-mo/flixtown-android@trickplay-assets/trickplay"

/** A single playable item's resolved trick-play preview data. */
data class TrickPlayManifest(
    val intervalMs: Long,
    val durationMs: Long,
    // Keyed by the bucket timestamp in ms (0, intervalMs, 2*intervalMs, ...).
    val frames: Map<Long, String>
)

private data class TrickPlayManifestDto(
    val interval: Int?,
    val durationMs: Long?,
    val frames: Map<String, String>?
)

/**
 * Fetches the (optional) trick-play manifest for a movie/episode. A missing
 * manifest — 404 (not generated yet), malformed JSON, or any network
 * failure — resolves to null rather than throwing, so callers always have a
 * simple "real previews available or not" signal and seeking itself never
 * depends on this succeeding.
 */
class TrickPlayRepository {

    suspend fun fetchManifest(contentId: String): TrickPlayManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$TRICKPLAY_BASE_URL/$contentId/manifest.json").build()
            NetworkModule.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string()
                if (body.isNullOrBlank()) return@withContext null
                val dto = NetworkModule.gson.fromJson(body, TrickPlayManifestDto::class.java)
                val intervalSeconds = dto.interval ?: return@withContext null
                val durationMs = dto.durationMs ?: return@withContext null
                val frames = dto.frames
                    ?.mapNotNull { (key, url) -> key.toLongOrNull()?.let { seconds -> (seconds * 1000L) to url } }
                    ?.toMap()
                    ?: return@withContext null
                if (frames.isEmpty()) return@withContext null
                TrickPlayManifest(intervalMs = intervalSeconds * 1000L, durationMs = durationMs, frames = frames)
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Trick-play manifest fetch failed for $contentId", e)
            null
        }
    }
}
