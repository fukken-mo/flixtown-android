package com.flixtown.tv.ui.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.flixtown.tv.core.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SeekPreviewThumbnails"
private const val THUMB_WIDTH = 240
private const val THUMB_HEIGHT = 135
private const val EXTRACT_TIMEOUT_MS = 2_500L

// ~3MB cap: at THUMB_WIDTH x THUMB_HEIGHT ARGB_8888 (~130KB/frame) that's
// ~23 cached frames — enough for a long back-and-forth scrub session without
// ever holding "hundreds of full-resolution bitmaps" in RAM.
private const val CACHE_BYTES = 3_000_000

/**
 * On-device Netflix-style seek-frame extraction for VOD playback, used only
 * because neither Xtream's standard API nor Flix Town's own backend expose
 * any server-provided trick-play/sprite data (see the 0.9.9 report). One
 * instance is created per playing item and released when that item's
 * composition tears down — never shared, never recreated per frame.
 *
 * Safety design, matching the constraints this feature was built under:
 * - A single [MediaMetadataRetriever] is reused for the whole session instead
 *   of one per frame (retriever construction/setDataSource is itself not
 *   free).
 * - All extraction is serialized onto a single-threaded IO dispatcher view
 *   ([extractDispatcher]), so no two frames are ever decoded concurrently no
 *   matter how fast the user presses LEFT/RIGHT.
 * - Results are cached by timestamp bucket in a byte-sized [LruCache], so the
 *   same 10s bucket is never decoded twice and old frames are evicted before
 *   memory grows unbounded.
 * - A single failed extraction (DRM, non-seekable stream, corrupt source)
 *   permanently latches [unavailable] so the caller's fallback hierarchy
 *   (real frame -> loading -> timestamp-only) settles on the timestamp-only
 *   card for the rest of that item instead of retrying forever.
 */
class SeekPreviewThumbnailProvider(private val streamUrl: String) {

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private val extractDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var retriever: MediaMetadataRetriever? = null
    @Volatile private var unavailable = false
    private var initialized = false

    /** Synchronous, thread-safe read of an already-resolved frame, if any. */
    fun cached(bucketMs: Long): Bitmap? = cache.get(bucketMs)

    /**
     * Resolves the frame at [bucketMs], extracting it if not already cached.
     * Returns null (never throws) when extraction is unavailable, times out,
     * or fails for any reason — callers fall back to a timestamp-only card.
     */
    suspend fun frameAt(bucketMs: Long): Bitmap? {
        cache.get(bucketMs)?.let { return it }
        if (unavailable) return null

        return withContext(extractDispatcher) {
            // Re-check inside the serialized dispatcher: another call may
            // have already resolved this exact bucket while this one was
            // queued behind it.
            cache.get(bucketMs)?.let { return@withContext it }
            if (unavailable) return@withContext null

            val r = ensureRetriever() ?: return@withContext null

            val bitmap = withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                extractFrame(r, bucketMs)
            }
            if (bitmap == null) {
                SafeLog.w(TAG, "Frame extraction failed/timed out at ${bucketMs}ms; disabling further attempts for this item")
                unavailable = true
                releaseRetrieverLocked()
                return@withContext null
            }
            cache.put(bucketMs, bitmap)
            bitmap
        }
    }

    private fun ensureRetriever(): MediaMetadataRetriever? {
        if (unavailable) return null
        retriever?.let { return it }
        if (initialized) return null
        initialized = true
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(streamUrl, emptyMap())
            retriever = r
            r
        } catch (t: Throwable) {
            SafeLog.w(TAG, "Failed to open stream for seek preview extraction", t)
            unavailable = true
            null
        }
    }

    private fun extractFrame(r: MediaMetadataRetriever, bucketMs: Long): Bitmap? {
        val timeUs = bucketMs * 1_000L
        return try {
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_WIDTH, THUMB_HEIGHT)
            } else {
                val frame = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                Bitmap.createScaledBitmap(frame, THUMB_WIDTH, THUMB_HEIGHT, true)
            }
        } catch (t: Throwable) {
            SafeLog.w(TAG, "getFrameAtTime failed at ${bucketMs}ms", t)
            null
        }
    }

    private fun releaseRetrieverLocked() {
        retriever?.let {
            try {
                it.release()
            } catch (t: Throwable) {
                SafeLog.w(TAG, "Retriever release failed", t)
            }
        }
        retriever = null
    }

    /** Releases the retriever and clears the cache. Call on item teardown. */
    fun release() {
        releaseRetrieverLocked()
        cache.evictAll()
    }
}
