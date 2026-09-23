package com.flixtown.tv.data

import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val TAG = "TrickPlayRepository"

/** Where one preview frame lives within a sprite sheet. */
data class TrickPlayFrameLocation(val spriteFile: String, val x: Int, val y: Int)

/**
 * A single playable item's resolved trick-play preview data. Frames are
 * grouped into sprite sheets (tens of frames per sheet) rather than one
 * image per timestamp specifically so the app can satisfy an entire 5-card
 * scrub window — and usually many consecutive scrub presses — from a
 * single already-fetched image instead of one network request per frame.
 */
data class TrickPlayManifest(
    val intervalMs: Long,
    val durationMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val baseUrl: String,
    // Keyed by the bucket timestamp in ms (0, intervalMs, 2*intervalMs, ...).
    val frames: Map<Long, TrickPlayFrameLocation>,
    // Distinct sprite filenames in playback-time order — lets the caller
    // preload "the next sprite" without re-deriving order from the frame map.
    val spriteOrder: List<String>
) {
    fun nextSpriteFile(currentSpriteFile: String): String? {
        val index = spriteOrder.indexOf(currentSpriteFile)
        return if (index in 0 until spriteOrder.lastIndex) spriteOrder[index + 1] else null
    }

    fun spriteUrl(spriteFile: String): String = "$baseUrl/$spriteFile"
}

/**
 * Portable manifest schema — independent of whoever is hosting it (cPanel,
 * a VPS, object storage, a CDN, or the GitHub Actions proof-of-concept this
 * app currently points at by default). `base_url` + each frame's `sprite`
 * are combined client-side so a host only needs to serve this one JSON file
 * and plain sprite-sheet image files under it; nothing provider-specific
 * leaks into this DTO. `width`/`height` are the size of ONE frame within a
 * sprite, not the sprite sheet itself — kept at the top level rather than
 * repeated per frame since every frame in a manifest is the same size.
 */
private data class TrickPlayManifestDto(
    @SerializedName("interval_seconds") val intervalSeconds: Int?,
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?,
    @SerializedName("duration_seconds") val durationSeconds: Long?,
    @SerializedName("base_url") val baseUrl: String?,
    @SerializedName("frames") val frames: List<TrickPlayFrameDto>?
)

private data class TrickPlayFrameDto(
    @SerializedName("time") val time: Long?,
    @SerializedName("sprite") val sprite: String?,
    @SerializedName("x") val x: Int?,
    @SerializedName("y") val y: Int?
)

/**
 * Fetches an (optional) trick-play manifest from an arbitrary URL. This
 * class knows nothing about where that URL points — no jsDelivr/GitHub/CDN
 * assumptions here, see [com.flixtown.tv.ui.player.trickPlayManifestUrl] for
 * how the URL itself is resolved (backend-configurable, with a temporary
 * proof-of-concept default). A missing manifest — 404 (not generated yet),
 * malformed JSON, or any network failure — resolves to null rather than
 * throwing, so callers always have a simple "real previews available or
 * not" signal and seeking itself never depends on this succeeding.
 */
class TrickPlayRepository {

    suspend fun fetchManifest(manifestUrl: String): TrickPlayManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(manifestUrl).build()
            NetworkModule.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string()
                if (body.isNullOrBlank()) return@withContext null
                val dto = NetworkModule.gson.fromJson(body, TrickPlayManifestDto::class.java)

                val intervalSeconds = dto.intervalSeconds ?: return@withContext null
                val durationSeconds = dto.durationSeconds ?: return@withContext null
                val frameWidth = dto.width ?: return@withContext null
                val frameHeight = dto.height ?: return@withContext null
                val baseUrl = dto.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return@withContext null
                val frameEntries = dto.frames ?: return@withContext null

                val spriteOrder = mutableListOf<String>()
                val frames = mutableMapOf<Long, TrickPlayFrameLocation>()
                for (frame in frameEntries) {
                    val time = frame.time ?: continue
                    val sprite = frame.sprite?.takeIf { it.isNotBlank() } ?: continue
                    val x = frame.x ?: continue
                    val y = frame.y ?: continue
                    if (spriteOrder.isEmpty() || spriteOrder.last() != sprite) {
                        if (sprite !in spriteOrder) spriteOrder.add(sprite)
                    }
                    frames[time * 1000L] = TrickPlayFrameLocation(sprite, x, y)
                }
                if (frames.isEmpty()) return@withContext null

                TrickPlayManifest(
                    intervalMs = intervalSeconds * 1000L,
                    durationMs = durationSeconds * 1000L,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    baseUrl = baseUrl,
                    frames = frames,
                    spriteOrder = spriteOrder
                )
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Trick-play manifest fetch failed: ${SafeLog.redactUrl(manifestUrl)}", e)
            null
        }
    }
}
