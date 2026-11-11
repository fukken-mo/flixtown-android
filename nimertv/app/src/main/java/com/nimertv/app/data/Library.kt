package com.nimertv.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject

/** Saved playback position for a movie or an episode. */
data class Progress(
    val key: String,
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val poster: String,
    val ext: String,
    val seriesId: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = ProgressRules.isFinished(positionMs, durationMs)
    val resumable: Boolean get() = ProgressRules.isResumable(positionMs, durationMs)

    companion object {
        const val MOVIE = "movie"
        const val EPISODE = "episode"
        fun movieKey(id: Int) = "$MOVIE:$id"
        fun episodeKey(id: String) = "$EPISODE:$id"
    }
}

/** Pure resume rules; unit tested. */
object ProgressRules {
    private const val MIN_POSITION_MS = 20_000L

    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && (positionMs >= durationMs * 0.95 || durationMs - positionMs < 30_000)

    fun isResumable(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= MIN_POSITION_MS && !isFinished(positionMs, durationMs)

    /** Latest unfinished title per movie / per series, newest first. */
    fun continueWatching(all: Collection<Progress>, limit: Int = 20): List<Progress> =
        all.sortedByDescending { it.updatedAt }
            .distinctBy { if (it.kind == Progress.EPISODE) "series:${it.seriesId}" else it.key }
            .filter { it.resumable }
            .take(limit)
}

/**
 * Favorites, recent channels and resume positions for one customer account.
 * Backed by Compose snapshot state so screens update immediately. Main thread only.
 */
class Library(context: Context, user: String) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("library_" + sha256Hex(user.toByteArray()).take(16), Context.MODE_PRIVATE)

    val favoriteChannels = mutableStateListOf<Int>()
    val favoriteMovies = mutableStateListOf<Int>()
    val favoriteSeries = mutableStateListOf<Int>()
    val recentChannels = mutableStateListOf<Int>()
    val progress = mutableStateMapOf<String, Progress>()

    init {
        favoriteChannels.addAll(readInts("fav_channels"))
        favoriteMovies.addAll(readInts("fav_movies"))
        favoriteSeries.addAll(readInts("fav_series"))
        recentChannels.addAll(readInts("recent_channels"))
        try {
            val arr = JSONArray(prefs.getString("progress", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = Progress(
                    key = o.optString("key"), kind = o.optString("kind"), id = o.optString("id"),
                    title = o.optString("title"), subtitle = o.optString("subtitle"), poster = o.optString("poster"),
                    ext = o.optString("ext"), seriesId = o.optInt("seriesId"),
                    positionMs = o.optLong("pos"), durationMs = o.optLong("dur"), updatedAt = o.optLong("at"),
                )
                if (p.key.isNotEmpty()) progress[p.key] = p
            }
        } catch (e: Exception) {
            // Corrupt data is discarded rather than crashing the app.
        }
    }

    private fun readInts(key: String): List<Int> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getInt(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeInts(key: String, list: List<Int>) = prefs.edit().putString(key, JSONArray(list).toString()).apply()

    private fun toggle(list: MutableList<Int>, key: String, id: Int): Boolean {
        val added = if (list.remove(id)) false else {
            list.add(0, id)
            true
        }
        writeInts(key, list)
        return added
    }

    fun toggleChannel(id: Int) = toggle(favoriteChannels, "fav_channels", id)
    fun toggleMovie(id: Int) = toggle(favoriteMovies, "fav_movies", id)
    fun toggleSeries(id: Int) = toggle(favoriteSeries, "fav_series", id)

    fun addRecentChannel(id: Int) {
        if (recentChannels.firstOrNull() == id) return
        recentChannels.remove(id)
        recentChannels.add(0, id)
        while (recentChannels.size > 30) recentChannels.removeAt(recentChannels.lastIndex)
        writeInts("recent_channels", recentChannels)
    }

    fun saveProgress(p: Progress) {
        progress[p.key] = p
        if (progress.size > MAX_PROGRESS) {
            progress.values.sortedBy { it.updatedAt }.take(progress.size - MAX_PROGRESS).forEach { progress.remove(it.key) }
        }
        persistProgress()
    }

    fun clearProgress(key: String) {
        if (progress.remove(key) != null) persistProgress()
    }

    private fun persistProgress() {
        val arr = JSONArray()
        progress.values.forEach { p ->
            arr.put(
                JSONObject().put("key", p.key).put("kind", p.kind).put("id", p.id).put("title", p.title)
                    .put("subtitle", p.subtitle).put("poster", p.poster).put("ext", p.ext)
                    .put("seriesId", p.seriesId).put("pos", p.positionMs).put("dur", p.durationMs).put("at", p.updatedAt),
            )
        }
        prefs.edit().putString("progress", arr.toString()).apply()
    }

    private companion object {
        const val MAX_PROGRESS = 300
    }
}

/** Customer credentials. Kept in app-private storage (backups are disabled in the manifest). */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val user: String get() = prefs.getString("user", "").orEmpty()
    val pass: String get() = prefs.getString("pass", "").orEmpty()
    val lastServer: String? get() = prefs.getString("last_server", null)
    val hasLogin: Boolean get() = user.isNotEmpty() && pass.isNotEmpty()

    fun save(session: Session) {
        prefs.edit()
            .putString("user", session.user).putString("pass", session.pass).putString("last_server", session.server)
            .putString("status", session.status).putLong("expires", session.expiresAtSec)
            .putString("max_connections", session.maxConnections).putBoolean("trial", session.isTrial)
            .putString("formats", session.formats.joinToString(","))
            .apply()
    }

    /** The last known session, used when the provider cannot be reached at startup. */
    fun restore(servers: List<String>): Session? {
        if (!hasLogin) return null
        val server = lastServer?.takeIf { it in servers } ?: servers.firstOrNull() ?: return null
        return Session(
            server = server, user = user, pass = pass,
            status = prefs.getString("status", "Active").orEmpty(),
            expiresAtSec = prefs.getLong("expires", 0),
            maxConnections = prefs.getString("max_connections", "").orEmpty(),
            isTrial = prefs.getBoolean("trial", false),
            formats = prefs.getString("formats", "").orEmpty().split(',').filter { it.isNotEmpty() },
        )
    }

    fun clear() = prefs.edit().clear().apply()

    // Announcements the customer has dismissed.
    private val misc = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
    fun isDismissed(key: String) = misc.getStringSet("dismissed", emptySet())!!.contains(key)
    fun dismiss(key: String) {
        val set = HashSet(misc.getStringSet("dismissed", emptySet())!!)
        set.add(key)
        misc.edit().putStringSet("dismissed", set).apply()
    }
}
