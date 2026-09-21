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
    val completed: Boolean = false
)

/**
 * Local playback-progress store. Nothing in this milestone writes to it yet
 * (there is no player), but the Home screen's Continue Watching row reads
 * from it now so the real player milestone only has to call [save] — no
 * screen wiring changes needed later.
 */
class ContinueWatchingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<ContinueWatchingEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val type = TypeToken.getParameterized(List::class.java, ContinueWatchingEntry::class.java).type
            val entries = NetworkModule.gson.fromJson<List<ContinueWatchingEntry>>(json, type) ?: emptyList()
            entries.filterNot { it.completed }.sortedByDescending { it.updatedAtMillis }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Continue-watching cache was corrupt, ignoring", e)
            emptyList()
        }
    }

    fun save(entry: ContinueWatchingEntry) {
        val existing = getAllIncludingCompleted().filterNot { it.streamId == entry.streamId && it.mediaType == entry.mediaType }
        val updated = (existing + entry).sortedByDescending { it.updatedAtMillis }.take(MAX_ENTRIES)
        prefs.edit().putString(KEY_ENTRIES, NetworkModule.gson.toJson(updated)).apply()
    }

    private fun getAllIncludingCompleted(): List<ContinueWatchingEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val type = TypeToken.getParameterized(List::class.java, ContinueWatchingEntry::class.java).type
            NetworkModule.gson.fromJson<List<ContinueWatchingEntry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
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
