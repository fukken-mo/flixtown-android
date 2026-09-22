package com.flixtown.tv.data

import android.content.Context
import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import com.google.gson.reflect.TypeToken

data class ContinueWatchingEntry(
    val streamId: Int,
    val mediaType: String, // "movie" | "episode"
    val seriesId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMillis: Long,
    val completed: Boolean = false,
    // Structured display data for episodes, populated straight from the
    // catalog at save time (never string-parsed) so a raw Xtream episode
    // title that already embeds "Series - S02E01 - Episode Name" doesn't
    // get concatenated with the series name a second time. Null on older
    // cached entries saved before these fields existed and on movies (which
    // don't need them — `title` alone is already the movie's name); the
    // Continue Watching UI falls back to parsing `title` only when these
    // are absent.
    val seriesName: String? = null,
    val episodeTitle: String? = null
)

/**
 * Local playback-progress store. Nothing in this milestone writes to it yet
 * (there is no player), but the Home screen's Continue Watching row reads
 * from it now so the real player milestone only has to call [save] — no
 * screen wiring changes needed later.
 */
class ContinueWatchingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // getAll() is called once per Continue Watching card on Home, once per
    // Details screen's own resume state, AND once per episode card in a
    // season list — each of those, independently, used to re-read
    // SharedPreferences and re-parse+re-sort the full JSON blob. Cached in
    // memory instead, invalidated only by save() (the only writer), so a
    // season with 20 episodes does one parse instead of 20.
    @Volatile private var cachedAll: List<ContinueWatchingEntry>? = null

    fun getAll(): List<ContinueWatchingEntry> {
        cachedAll?.let { return it }
        val result = parseAll().filterNot { it.completed }.sortedByDescending { it.updatedAtMillis }
        cachedAll = result
        return result
    }

    fun save(entry: ContinueWatchingEntry) {
        val existing = parseAll().filterNot { it.streamId == entry.streamId && it.mediaType == entry.mediaType }
        val updated = (existing + entry).sortedByDescending { it.updatedAtMillis }.take(MAX_ENTRIES)
        prefs.edit().putString(KEY_ENTRIES, NetworkModule.gson.toJson(updated)).apply()
        cachedAll = null
    }

    private fun parseAll(): List<ContinueWatchingEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val type = TypeToken.getParameterized(List::class.java, ContinueWatchingEntry::class.java).type
            NetworkModule.gson.fromJson<List<ContinueWatchingEntry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            SafeLog.w(TAG, "Continue-watching cache was corrupt, ignoring", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ContinueWatchingStore"
        private const val PREFS_NAME = "flixtown_continue_watching"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 50
    }
}
