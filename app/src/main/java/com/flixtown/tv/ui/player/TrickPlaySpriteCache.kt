package com.flixtown.tv.ui.player

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.flixtown.tv.core.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TrickPlaySpriteCache"

// One sprite sheet at the generation workflow's current 160x90 x 10x5 grid
// is roughly 1600x450 px ARGB_8888 (~2.9MB decoded). This cap comfortably
// holds "previous + current + next" transiently (~8.6MB) while staying
// inside the 5-10MB target — decoded sprites, never hundreds of individual
// Bitmaps.
private const val SPRITE_CACHE_BYTES = 9_000_000

/**
 * Decoded-sprite cache for the currently playing item's seek previews. Each
 * sprite sheet covers many consecutive preview frames (see
 * TrickPlayRepository/SEEK-PREVIEW-FUTURE.md), so caching a handful of
 * decoded sheets here — rather than one bitmap per timestamp — is what lets
 * an entire 5-card scrub window, and usually many scrub presses in a row,
 * come from memory instead of the network.
 *
 * Deliberately built on Coil's own [Context.imageLoader]: the raw sprite
 * bytes go through Coil's normal disk+memory cache exactly like every other
 * network image in this app (posters, cast photos), so a sprite evicted
 * from THIS small in-memory decoded cache is typically still a fast local
 * disk-cache hit, not a cold network fetch, when it's needed again. This
 * class only adds a second, much smaller cache on top for the decoded
 * [ImageBitmap] itself (Coil's own cache stores encoded bytes/drawables,
 * not something Compose can crop-draw directly without a decode step).
 */
class TrickPlaySpriteCache(private val context: Context) {

    private val cache = object : LruCache<String, ImageBitmap>(SPRITE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    // Preloading must never compete with or block player setup/decoding —
    // its own scope, cancelled wholesale on release() (item teardown).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Synchronous, thread-safe read of an already-decoded sprite, if any. */
    fun cached(spriteUrl: String): ImageBitmap? = cache.get(spriteUrl)

    /** Fetch (Coil's cache first, then network) + decode, caching the result. Never throws. */
    suspend fun getSprite(spriteUrl: String): ImageBitmap? {
        cache.get(spriteUrl)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(spriteUrl)?.let { return@withContext it }
            try {
                val request = ImageRequest.Builder(context).data(spriteUrl).build()
                val drawable = context.imageLoader.execute(request).drawable
                val bitmap = (drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
                if (bitmap != null) cache.put(spriteUrl, bitmap)
                bitmap
            } catch (e: Exception) {
                SafeLog.w(TAG, "Sprite load failed", e)
                null
            }
        }
    }

    /** Fire-and-forget warm-up — used to stay ahead of playback/scrubbing. */
    fun preload(spriteUrl: String) {
        if (cache.get(spriteUrl) != null) return
        scope.launch { getSprite(spriteUrl) }
    }

    /** Cancels any in-flight preloads and clears decoded sprites. Call on item teardown. */
    fun release() {
        scope.cancel()
        cache.evictAll()
    }
}
