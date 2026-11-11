package com.nimertv.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import java.io.EOFException
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.URLEncoder

data class Category(val id: String, val name: String)

data class Channel(
    val id: Int,
    val number: Int,
    val name: String,
    val logo: String,
    val categoryId: String,
    val hasArchive: Boolean,
)

data class Movie(
    val id: Int,
    val name: String,
    val poster: String,
    val categoryId: String,
    val added: Long,
    val rating: Double,
    val ext: String,
    val year: String,
)

data class Series(
    val id: Int,
    val name: String,
    val cover: String,
    val categoryId: String,
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val rating: Double,
    val backdrop: String,
    val lastModified: Long,
)

data class MovieInfo(
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val duration: String,
    val rating: Double,
    val backdrop: String,
    val poster: String,
    val ext: String,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val image: String,
    val duration: String,
)

data class SeriesInfo(val series: Series, val seasons: List<Int>, val episodes: Map<Int, List<Episode>>)

data class EpgEntry(val title: String, val description: String, val startMs: Long, val endMs: Long)

data class Session(
    val server: String,
    val user: String,
    val pass: String,
    val status: String,
    val expiresAtSec: Long,
    val maxConnections: String,
    val isTrial: Boolean,
    val formats: List<String>,
) {
    /** HLS recovers best from network hiccups; MPEG-TS is the universal fallback. */
    val liveFormats: List<String>
        get() {
            val allowed = formats.map { it.lowercase() }.filter { it == "m3u8" || it == "ts" }
            return when {
                allowed.isEmpty() -> listOf("m3u8", "ts")
                "m3u8" in allowed -> listOf("m3u8") + allowed.filter { it != "m3u8" }
                else -> allowed
            }
        }

    fun liveUrl(id: Int, format: String) = "$server/live/${enc(user)}/${enc(pass)}/$id.$format"
    fun movieUrl(id: String, ext: String) = "$server/movie/${enc(user)}/${enc(pass)}/$id.${safeExt(ext)}"
    fun episodeUrl(id: String, ext: String) = "$server/series/${enc(user)}/${enc(pass)}/$id.${safeExt(ext)}"

    private fun safeExt(ext: String) = ext.filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

sealed class AuthResult {
    data class Success(val session: Session) : AuthResult()
    /** The provider answered and rejected the username/password. */
    data object InvalidCredentials : AuthResult()
    /** The provider answered: the account exists but may not be used (Expired, Banned, Disabled…). */
    data class AccountBlocked(val status: String) : AuthResult()
    /** No configured server could be reached. */
    data class Unreachable(val detail: String) : AuthResult()
}

class ProviderException(message: String) : IOException(message)

/** Tolerant parsing of Xtream Codes responses; panels differ in types and field names. */
object XtreamParser {
    fun JsonObject.s(key: String): String {
        val e = get(key) ?: return ""
        return if (e.isJsonPrimitive) e.asString.trim() else ""
    }

    fun JsonObject.first(vararg keys: String): String {
        for (k in keys) s(k).let { if (it.isNotEmpty()) return it }
        return ""
    }

    private fun JsonObject.long(key: String): Long = s(key).toDoubleOrNull()?.toLong() ?: 0
    private fun JsonObject.int(key: String): Int = long(key).toInt()
    /** Ten-point provider rating, or 0 when the provider supplies none. Never invented. */
    private fun JsonObject.rating(): Double {
        val ten = s("rating").toDoubleOrNull()?.takeIf { it > 0 }
        val five = s("rating_5based").toDoubleOrNull()?.takeIf { it > 0 }
        return (ten ?: five?.times(2) ?: 0.0).takeIf { it in 0.1..10.0 } ?: 0.0
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    /** `backdrop_path` is an array on most panels and a plain string on others. */
    private fun JsonObject.backdrop(): String {
        val e = get("backdrop_path") ?: return ""
        return when {
            e.isJsonArray -> e.asJsonArray.firstOrNull { it.isJsonPrimitive && it.asString.isNotBlank() }?.asString?.trim().orEmpty()
            e.isJsonPrimitive -> e.asString.trim()
            else -> ""
        }
    }

    fun auth(body: String, server: String, user: String, pass: String): AuthResult {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "[]" || trimmed == "{}") return AuthResult.InvalidCredentials
        val root = try {
            JsonParser.parseString(trimmed)
        } catch (e: Exception) {
            return AuthResult.Unreachable("The TV service sent an unexpected response.")
        }
        val info = (root as? JsonObject)?.obj("user_info")
            ?: return AuthResult.Unreachable("The TV service sent an unexpected response.")
        val auth = info.s("auth")
        if (auth == "0" || auth.equals("false", true)) return AuthResult.InvalidCredentials
        val status = info.s("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) return AuthResult.AccountBlocked(status)
        if (auth.isEmpty() && status.isEmpty()) return AuthResult.InvalidCredentials
        val formats = info.get("allowed_output_formats")
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.orEmpty()
        return AuthResult.Success(
            Session(
                server = server,
                user = user,
                pass = pass,
                status = status.ifEmpty { "Active" },
                expiresAtSec = info.long("exp_date"),
                maxConnections = info.s("max_connections"),
                isTrial = info.s("is_trial") == "1",
                formats = formats,
            ),
        )
    }

    /**
     * Streams a JSON array without holding the whole document in memory; catalogues with
     * tens of thousands of entries are common.
     */
    fun <T> list(reader: Reader, map: (JsonObject) -> T?): List<T> {
        val out = ArrayList<T>()
        try {
            val jr = JsonReader(reader)
            jr.isLenient = true
            when (jr.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        val el = JsonParser.parseReader(jr)
                        if (el.isJsonObject) map(el.asJsonObject)?.let(out::add)
                    }
                }
                JsonToken.BEGIN_OBJECT -> {
                    // Some panels answer with an object keyed by index; an auth object means "rejected".
                    val root = JsonParser.parseReader(jr).asJsonObject
                    if (root.has("user_info")) throw ProviderException("The TV service rejected this account.")
                    for ((_, v) in root.entrySet()) if (v.isJsonObject) map(v.asJsonObject)?.let(out::add)
                }
                else -> Unit
            }
        } catch (e: EOFException) {
            // Empty body: the provider has nothing in this section.
        } catch (e: ProviderException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException("The TV service sent an unexpected response.")
        }
        return out
    }

    fun category(o: JsonObject): Category? {
        val id = o.s("category_id")
        val name = o.s("category_name")
        return if (id.isEmpty() || name.isEmpty()) null else Category(id, name)
    }

    fun channel(o: JsonObject): Channel? {
        val id = o.int("stream_id")
        if (id <= 0) return null
        return Channel(
            id = id,
            number = o.int("num"),
            name = o.s("name").ifEmpty { "Channel $id" },
            logo = o.s("stream_icon"),
            categoryId = o.s("category_id"),
            hasArchive = o.s("tv_archive") == "1",
        )
    }

    fun movie(o: JsonObject): Movie? {
        val id = o.int("stream_id")
        if (id <= 0) return null
        return Movie(
            id = id,
            name = o.s("name").ifEmpty { "Untitled" },
            poster = o.first("stream_icon", "cover"),
            categoryId = o.s("category_id"),
            added = o.long("added"),
            rating = o.rating(),
            ext = o.s("container_extension").ifEmpty { "mp4" },
            year = o.s("year").take(4),
        )
    }

    fun series(o: JsonObject, fallbackId: Int = 0): Series? {
        val id = o.int("series_id").takeIf { it > 0 } ?: fallbackId
        if (id <= 0) return null
        return Series(
            id = id,
            name = o.s("name").ifEmpty { "Untitled" },
            cover = o.first("cover", "stream_icon"),
            categoryId = o.s("category_id"),
            plot = o.first("plot", "description"),
            cast = o.first("cast", "actors"),
            director = o.s("director"),
            genre = o.s("genre"),
            releaseDate = o.first("releaseDate", "release_date", "releasedate"),
            rating = o.rating(),
            backdrop = o.backdrop(),
            lastModified = o.long("last_modified"),
        )
    }

    fun movieInfo(body: String, fallback: Movie): MovieInfo {
        val root = parseObject(body)
        val info = root?.obj("info") ?: JsonObject()
        val data = root?.obj("movie_data") ?: JsonObject()
        return MovieInfo(
            plot = info.first("plot", "description"),
            cast = info.first("cast", "actors"),
            director = info.s("director"),
            genre = info.s("genre"),
            releaseDate = info.first("releasedate", "release_date", "releaseDate"),
            duration = formatDuration(info.long("duration_secs"), info.s("duration")),
            rating = info.rating().takeIf { it > 0 } ?: fallback.rating,
            backdrop = info.backdrop(),
            poster = info.first("movie_image", "cover_big", "cover").ifEmpty { fallback.poster },
            ext = data.s("container_extension").ifEmpty { fallback.ext },
        )
    }

    fun seriesInfo(body: String, fallback: Series): SeriesInfo {
        val root = parseObject(body) ?: throw ProviderException("The TV service sent an unexpected response.")
        val info = root.obj("info")?.let { series(it, fallback.id) }
        val merged = if (info == null) fallback else fallback.copy(
            plot = info.plot.ifEmpty { fallback.plot },
            cast = info.cast.ifEmpty { fallback.cast },
            director = info.director.ifEmpty { fallback.director },
            genre = info.genre.ifEmpty { fallback.genre },
            releaseDate = info.releaseDate.ifEmpty { fallback.releaseDate },
            rating = if (info.rating > 0) info.rating else fallback.rating,
            backdrop = info.backdrop.ifEmpty { fallback.backdrop },
            cover = fallback.cover.ifEmpty { info.cover },
        )

        val bySeason = sortedMapOf<Int, MutableList<Episode>>()
        fun add(el: JsonElement, seasonKey: Int?) {
            if (!el.isJsonObject) return
            val o = el.asJsonObject
            val id = o.s("id")
            if (id.isEmpty()) return
            val epInfo = o.obj("info") ?: JsonObject()
            val season = o.int("season").takeIf { it > 0 || seasonKey == null } ?: seasonKey ?: 0
            val number = o.int("episode_num")
            bySeason.getOrPut(season) { ArrayList() }.add(
                Episode(
                    id = id,
                    season = season,
                    number = number,
                    title = o.s("title").ifEmpty { "Episode $number" },
                    ext = o.s("container_extension").ifEmpty { "mp4" },
                    plot = epInfo.first("plot", "overview"),
                    image = epInfo.first("movie_image", "cover_big"),
                    duration = formatDuration(epInfo.long("duration_secs"), epInfo.s("duration")),
                ),
            )
        }
        val episodes = root.get("episodes")
        when {
            episodes == null -> Unit
            // Usual form: {"1": [...], "2": [...]}
            episodes.isJsonObject -> for ((key, list) in episodes.asJsonObject.entrySet()) {
                if (list.isJsonArray) list.asJsonArray.forEach { add(it, key.toIntOrNull()) }
            }
            // Some panels: [[...], [...]] or a flat list
            episodes.isJsonArray -> episodes.asJsonArray.forEachIndexed { index, list ->
                if (list.isJsonArray) list.asJsonArray.forEach { add(it, index + 1) } else add(list, null)
            }
        }
        val sorted = bySeason.mapValues { (_, list) -> list.distinctBy { it.id }.sortedBy { it.number } }
        return SeriesInfo(merged, sorted.keys.toList(), sorted)
    }

    fun epg(body: String): List<EpgEntry> {
        val listings = parseObject(body)?.get("epg_listings")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return listings.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val start = o.long("start_timestamp") * 1000
            val end = o.long("stop_timestamp") * 1000
            val title = decode(o.s("title"))
            if (title.isEmpty() || end <= start) null else EpgEntry(title, decode(o.s("description")), start, end)
        }.sortedBy { it.startMs }
    }

    /** EPG text is Base64-encoded UTF-8 on Xtream panels; fall back to the raw text otherwise. */
    private fun decode(s: String): String {
        if (s.isEmpty()) return ""
        return s.decodeBase64()?.utf8()?.takeIf { !it.contains('�') }?.trim() ?: s
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseReader(JsonReader(StringReader(body)).apply { isLenient = true }) as? JsonObject
    } catch (e: Exception) {
        null
    }

    fun formatDuration(seconds: Long, text: String): String {
        if (seconds > 0) {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }
        return text.takeIf { it.isNotEmpty() && it != "00:00:00" }.orEmpty()
    }
}

/**
 * Xtream Codes player API with DNS failover: requests go to the server that last worked
 * and move through the panel's other servers on network errors.
 */
class XtreamClient(private val servers: () -> List<String>) {
    @Volatile
    var session: Session? = null

    /** Tries each server in order. Only network-level failures move on to the next one. */
    fun authenticate(user: String, pass: String, preferred: String?): AuthResult {
        val list = ordered(preferred)
        if (list.isEmpty()) return AuthResult.Unreachable("No TV server is configured yet. Please contact support.")
        var lastError = "The TV service could not be reached."
        for (server in list) {
            try {
                val body = get(Http.providerAuth, server, user, pass, "")
                when (val result = XtreamParser.auth(body, server, user, pass)) {
                    is AuthResult.Unreachable -> lastError = result.detail
                    else -> {
                        if (result is AuthResult.Success) session = result.session
                        return result
                    }
                }
            } catch (e: ProviderException) {
                lastError = e.message ?: lastError
            } catch (e: IOException) {
                lastError = "The TV service could not be reached. Check the internet connection."
            }
        }
        return AuthResult.Unreachable(lastError)
    }

    private fun ordered(preferred: String?): List<String> {
        val all = servers()
        return if (preferred != null && preferred in all) listOf(preferred) + all.filter { it != preferred } else all
    }

    private fun get(client: OkHttpClient, server: String, user: String, pass: String, query: String): String =
        open(client, server, user, pass, query) { it.readText() }

    private fun <T> open(client: OkHttpClient, server: String, user: String, pass: String, query: String, read: (Reader) -> T): T {
        val url = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}$query"
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { resp ->
            // Wrong credentials are reported as 401/403 by some panels; let the parser decide on an empty body.
            if (resp.code == 401 || resp.code == 403) return read(StringReader(""))
            if (!resp.isSuccessful) throw ProviderException("The TV service answered with error ${resp.code}.")
            val body = resp.body ?: throw ProviderException("The TV service sent an empty response.")
            return body.charStream().use(read)
        }
    }

    /** Runs a catalogue request with failover and remembers the server that answered. */
    private fun <T> call(query: String, read: (Reader) -> T): T {
        val s = session ?: throw ProviderException("Not signed in.")
        var last: IOException? = null
        for (server in ordered(s.server)) {
            try {
                val result = open(Http.provider, server, s.user, s.pass, query, read)
                if (server != s.server) session = s.copy(server = server)
                return result
            } catch (e: ProviderException) {
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: ProviderException("No TV server is configured.")
    }

    fun categories(action: String): List<Category> = call("&action=$action") { XtreamParser.list(it, XtreamParser::category) }.distinctBy { it.id }
    fun channels(): List<Channel> = call("&action=get_live_streams") { XtreamParser.list(it, XtreamParser::channel) }.distinctBy { it.id }
    fun movies(): List<Movie> = call("&action=get_vod_streams") { XtreamParser.list(it, XtreamParser::movie) }.distinctBy { it.id }
    fun series(): List<Series> = call("&action=get_series") { r -> XtreamParser.list(r) { XtreamParser.series(it) } }.distinctBy { it.id }
    fun movieInfo(movie: Movie): MovieInfo = call("&action=get_vod_info&vod_id=${movie.id}") { XtreamParser.movieInfo(it.readText(), movie) }
    fun seriesInfo(series: Series): SeriesInfo = call("&action=get_series_info&series_id=${series.id}") { XtreamParser.seriesInfo(it.readText(), series) }
    fun epg(channelId: Int): List<EpgEntry> = call("&action=get_short_epg&stream_id=$channelId&limit=6") { XtreamParser.epg(it.readText()) }
}

/** In-memory catalogue with time-based refresh. */
class Catalog(private val client: XtreamClient) {
    private inner class Cached<T>(private val loader: () -> T) {
        private val mutex = Mutex()
        private var value: T? = null
        private var loadedAt = 0L

        suspend fun get(force: Boolean): T = mutex.withLock {
            val current = value
            val age = System.currentTimeMillis() - loadedAt
            if (current != null && !force && age in 0 until TTL_MS) return current
            try {
                withContext(Dispatchers.IO) { loader() }.also {
                    value = it
                    loadedAt = System.currentTimeMillis()
                }
            } catch (e: IOException) {
                // A stale catalogue is better than an empty screen during a short outage.
                if (current != null && !force) current else throw e
            }
        }

        fun peek(): T? = value

        fun expire() {
            loadedAt = 0
        }
    }

    /** Forces the next request of every list to reload from the provider. */
    fun invalidate() = listOf(liveCategories, channels, movieCategories, movies, seriesCategories, series).forEach { it.expire() }

    private val liveCategories: Cached<List<Category>> = Cached { client.categories("get_live_categories") }
    private val channels: Cached<List<Channel>> = Cached { client.channels() }
    private val movieCategories: Cached<List<Category>> = Cached { client.categories("get_vod_categories") }
    private val movies: Cached<List<Movie>> = Cached { client.movies() }
    private val seriesCategories: Cached<List<Category>> = Cached { client.categories("get_series_categories") }
    private val series: Cached<List<Series>> = Cached { client.series() }

    suspend fun liveCategories(force: Boolean = false) = liveCategories.get(force)
    suspend fun channels(force: Boolean = false) = channels.get(force)
    suspend fun movieCategories(force: Boolean = false) = movieCategories.get(force)
    suspend fun movies(force: Boolean = false) = movies.get(force)
    suspend fun seriesCategories(force: Boolean = false) = seriesCategories.get(force)
    suspend fun series(force: Boolean = false) = series.get(force)

    fun cachedChannels(): List<Channel> = channels.peek().orEmpty()

    suspend fun movieInfo(movie: Movie): MovieInfo = withContext(Dispatchers.IO) { client.movieInfo(movie) }
    suspend fun seriesInfo(s: Series): SeriesInfo = withContext(Dispatchers.IO) { client.seriesInfo(s) }
    suspend fun epg(channelId: Int): List<EpgEntry> = withContext(Dispatchers.IO) { client.epg(channelId) }

    private companion object {
        const val TTL_MS = 30L * 60 * 1000
    }
}
