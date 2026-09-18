package com.flixtown.tv

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

const val PANEL_BASE = "https://panelsandapps.com/panels/flixtown2027"
const val PANEL_CONFIG_URL = "$PANEL_BASE/api/config.php"
const val PAIR_CREATE_URL = "$PANEL_BASE/api/pairing-create.php"
const val PAIR_STATUS_URL = "$PANEL_BASE/api/pairing-status.php"

data class AppConfig(
    val app_name: String = "Flix Town",
    val server_url: String = "",
    val announcement: String = "",
    val logo_url: String = "",
    val intro_video_url: String = "",
    val maintenance_mode: Boolean = false,
    val tmdb_image_base: String = "https://image.tmdb.org/t/p/w185"
)

data class Credentials(val username: String, val password: String)
data class AccountInfo(val expiration: String = "Unknown", val status: String = "")
data class Category(val category_id: String = "", val category_name: String = "")
enum class ContentType { MOVIE, SERIES }

data class ContentItem(
    val id: Int,
    val title: String,
    val poster: String?,
    val backdrop: String? = null,
    val extension: String = "mp4",
    val type: ContentType,
    val plot: String = "",
    val rating: String = ""
)

data class Episode(
    val id: Int,
    val title: String,
    val episodeNumber: Int,
    val season: Int,
    val extension: String = "mp4",
    val poster: String? = null,
    val plot: String = ""
)

data class CastMember(
    val name: String,
    val image: String? = null,
    val character: String = ""
)

data class SeriesDetails(
    val item: ContentItem,
    val episodes: Map<Int, List<Episode>>,
    val plot: String,
    val backdrop: String?,
    val trailer: String? = null,
    val cast: List<CastMember> = emptyList()
)

data class MovieDetails(
    val item: ContentItem,
    val plot: String,
    val backdrop: String?,
    val trailer: String?,
    val cast: List<CastMember> = emptyList()
)

data class PlayRequest(
    val url: String,
    val title: String,
    val poster: String? = null,
    val contentItem: ContentItem? = null,
    val progressKey: String = "",
    val mediaType: ContentType = ContentType.MOVIE,
    val mediaId: Int = 0,
    val extension: String = "mp4"
)

data class ContinueEntry(
    val progressKey: String,
    val title: String,
    val poster: String?,
    val mediaType: ContentType,
    val mediaId: Int,
    val extension: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long
) {
    val progress: Float get() = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    fun request(config: AppConfig, credentials: Credentials) = PlayRequest(
        streamUrl(config, credentials, mediaType, mediaId, extension),
        title,
        poster,
        progressKey = progressKey,
        mediaType = mediaType,
        mediaId = mediaId,
        extension = extension
    )
}

object Api {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    suspend fun objectFrom(url: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "FlixTown-TV/0.3").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server returned ${response.code}")
            gson.fromJson(response.body?.string() ?: "{}", JsonObject::class.java)
        }
    }

    suspend fun arrayFrom(url: String): JsonArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "FlixTown-TV/0.3").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server returned ${response.code}")
            gson.fromJson(response.body?.string() ?: "[]", JsonArray::class.java)
        }
    }

    suspend fun postObject(url: String, values: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = gson.toJson(values).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).header("User-Agent", "FlixTown-TV/0.3").build()
        client.newCall(request).execute().use { response ->
            val parsed = gson.fromJson(response.body?.string() ?: "{}", JsonObject::class.java)
            if (!response.isSuccessful && parsed.get("status")?.asString != "pending") {
                error(parsed.get("error")?.asString ?: "Server returned ${response.code}")
            }
            parsed
        }
    }

    fun apiUrl(config: AppConfig, credentials: Credentials, action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val username = URLEncoder.encode(credentials.username, "UTF-8")
        val password = URLEncoder.encode(credentials.password, "UTF-8")
        return buildString {
            append(config.server_url.trimEnd('/')).append("/player_api.php?username=").append(username).append("&password=").append(password)
            if (action != null) append("&action=").append(URLEncoder.encode(action, "UTF-8"))
            extra.forEach { (key, value) -> append('&').append(URLEncoder.encode(key, "UTF-8")).append('=').append(URLEncoder.encode(value, "UTF-8")) }
        }
    }
}

suspend fun authenticate(config: AppConfig, credentials: Credentials): Pair<Boolean, AccountInfo> {
    val response = Api.objectFrom(Api.apiUrl(config, credentials))
    val user = response.getAsJsonObject("user_info") ?: return false to AccountInfo()
    val authenticated = user.get("auth")?.asString == "1"
    val expiry = formatExpiration(user.get("exp_date")?.takeUnless { it.isJsonNull }?.asString)
    val status = user.get("status")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    return authenticated to AccountInfo(expiry, status)
}

suspend fun loadCategories(config: AppConfig, credentials: Credentials, type: ContentType): List<Category> {
    val action = if (type == ContentType.MOVIE) "get_vod_categories" else "get_series_categories"
    return Gson().fromJson(Api.arrayFrom(Api.apiUrl(config, credentials, action)), Array<Category>::class.java).toList()
}

object CatalogCache {
    private val categories = ConcurrentHashMap<ContentType, List<Category>>()
    private val content = ConcurrentHashMap<String, List<ContentItem>>()
    private val movies = ConcurrentHashMap<Int, MovieDetails>()
    private val series = ConcurrentHashMap<Int, SeriesDetails>()

    suspend fun categories(config: AppConfig, credentials: Credentials, type: ContentType): List<Category> =
        categories[type] ?: loadCategories(config, credentials, type).also { categories[type] = it }

    suspend fun content(config: AppConfig, credentials: Credentials, type: ContentType, category: String? = null): List<ContentItem> {
        val key = "${type.name}:${category.orEmpty()}"
        return content[key] ?: loadContent(config, credentials, type, category).also { content[key] = it }
    }

    suspend fun movieDetails(config: AppConfig, credentials: Credentials, item: ContentItem): MovieDetails =
        movies[item.id] ?: loadMovieDetails(config, credentials, item).also { movies[item.id] = it }

    suspend fun seriesDetails(config: AppConfig, credentials: Credentials, item: ContentItem): SeriesDetails =
        series[item.id] ?: loadSeriesDetails(config, credentials, item).also { series[item.id] = it }

    fun clear() { categories.clear(); content.clear(); movies.clear(); series.clear() }
}

suspend fun loadContent(config: AppConfig, credentials: Credentials, type: ContentType, category: String? = null): List<ContentItem> {
    val action = if (type == ContentType.MOVIE) "get_vod_streams" else "get_series"
    val extra = if (category.isNullOrBlank()) emptyMap() else mapOf("category_id" to category)
    return Api.arrayFrom(Api.apiUrl(config, credentials, action, extra)).mapNotNull { element ->
        val item = element.asJsonObject
        val id = (if (type == ContentType.MOVIE) item.get("stream_id") else item.get("series_id"))?.asInt ?: return@mapNotNull null
        val poster = item.stringOrNull("stream_icon") ?: item.stringOrNull("cover")
        val backdrop = item.arrayFirst("backdrop_path")
        ContentItem(
            id = id,
            title = item.stringOrNull("name") ?: "Untitled",
            poster = poster,
            backdrop = backdrop,
            extension = item.stringOrNull("container_extension") ?: "mp4",
            type = type,
            plot = item.stringOrNull("plot").orEmpty(),
            rating = item.stringOrNull("rating").orEmpty()
        )
    }.reversed()
}

suspend fun loadSeriesDetails(config: AppConfig, credentials: Credentials, item: ContentItem): SeriesDetails {
    val response = Api.objectFrom(Api.apiUrl(config, credentials, "get_series_info", mapOf("series_id" to item.id.toString())))
    val info = response.getAsJsonObject("info") ?: JsonObject()
    val plot = info.stringOrNull("plot") ?: item.plot
    val backdrop = info.arrayFirst("backdrop_path") ?: item.backdrop
    val trailer = info.stringOrNull("youtube_trailer")
        ?: info.stringOrNull("trailer")
        ?: response.stringOrNull("youtube_trailer")
    val grouped = linkedMapOf<Int, List<Episode>>()
    val episodesObject = response.getAsJsonObject("episodes") ?: JsonObject()
    episodesObject.entrySet().sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.forEach { (seasonKey, value) ->
        val season = seasonKey.toIntOrNull() ?: return@forEach
        val episodes = value.asJsonArray.mapNotNull { element ->
            val episode = element.asJsonObject
            val id = episode.get("id")?.asInt ?: return@mapNotNull null
            val episodeInfo = episode.getAsJsonObject("info")
            Episode(
                id = id,
                title = episode.stringOrNull("title") ?: "Episode ${episode.get("episode_num")?.asInt ?: 0}",
                episodeNumber = episode.get("episode_num")?.asInt ?: 0,
                season = season,
                extension = episode.stringOrNull("container_extension") ?: "mp4",
                poster = episodeInfo?.stringOrNull("movie_image") ?: episodeInfo?.stringOrNull("cover_big"),
                plot = episodeInfo?.stringOrNull("plot").orEmpty()
            )
        }
        grouped[season] = episodes
    }
    return SeriesDetails(item, grouped, plot, backdrop, trailer, parseCast(info))
}

suspend fun loadMovieDetails(config: AppConfig, credentials: Credentials, item: ContentItem): MovieDetails {
    val response = Api.objectFrom(Api.apiUrl(config, credentials, "get_vod_info", mapOf("vod_id" to item.id.toString())))
    val info = response.getAsJsonObject("info") ?: JsonObject()
    val movieData = response.getAsJsonObject("movie_data") ?: JsonObject()
    val plot = info.stringOrNull("plot") ?: movieData.stringOrNull("plot") ?: item.plot
    val backdrop = info.arrayFirst("backdrop_path") ?: movieData.arrayFirst("backdrop_path") ?: item.backdrop
    val trailer = info.stringOrNull("youtube_trailer")
        ?: info.stringOrNull("trailer")
        ?: movieData.stringOrNull("youtube_trailer")
        ?: movieData.stringOrNull("trailer")
    return MovieDetails(item, plot, backdrop, trailer, parseCast(info))
}

private fun parseCast(info: JsonObject): List<CastMember> {
    val candidates = listOf("actors", "cast")
    for (key in candidates) {
        val value = info.get(key) ?: continue
        if (value.isJsonArray) {
            val parsed = value.asJsonArray.mapNotNull { element ->
                if (element.isJsonObject) {
                    val actor = element.asJsonObject
                    val name = actor.stringOrNull("name") ?: actor.stringOrNull("actor") ?: return@mapNotNull null
                    CastMember(name, normalizeCastImage(actor.stringOrNull("profile_path") ?: actor.stringOrNull("image") ?: actor.stringOrNull("photo")), actor.stringOrNull("character").orEmpty())
                } else element.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { CastMember(it) }
            }
            if (parsed.isNotEmpty()) return parsed.take(20)
        }
        if (value.isJsonPrimitive) {
            val parsed = value.asString.split(',').mapNotNull { it.trim().takeIf(String::isNotBlank)?.let(::CastMember) }
            if (parsed.isNotEmpty()) return parsed.take(20)
        }
    }
    return emptyList()
}

private fun normalizeCastImage(value: String?): String? = value?.takeIf { it.isNotBlank() }?.let {
    if (it.startsWith("http://") || it.startsWith("https://")) it else "https://image.tmdb.org/t/p/w185/${it.trimStart('/')}"
}

fun streamUrl(config: AppConfig, credentials: Credentials, type: ContentType, id: Int, extension: String): String {
    val section = if (type == ContentType.MOVIE) "movie" else "series"
    return "${config.server_url.trimEnd('/')}/$section/${Uri.encode(credentials.username)}/${Uri.encode(credentials.password)}/$id.${extension.ifBlank { "mp4" }}"
}

private fun formatExpiration(raw: String?): String {
    val seconds = raw?.toLongOrNull() ?: return "Unlimited"
    if (seconds <= 0L) return "Unlimited"
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(seconds * 1000L))
}

private fun JsonObject.stringOrNull(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
private fun JsonObject.arrayFirst(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonArray) it.asJsonArray.firstOrNull()?.takeUnless { value -> value.isJsonNull }?.asString else it.asString
}

fun loadCredentials(context: Context): Credentials? {
    val prefs = context.getSharedPreferences("flix", Context.MODE_PRIVATE)
    val username = prefs.getString("username", null) ?: prefs.getString("u", null) ?: return null
    val password = prefs.getString("password", null) ?: prefs.getString("p", null) ?: return null
    return Credentials(username, password)
}

fun saveCredentials(context: Context, credentials: Credentials) {
    context.getSharedPreferences("flix", Context.MODE_PRIVATE).edit()
        .putString("username", credentials.username).putString("password", credentials.password)
        .remove("u").remove("p").apply()
}

fun clearCredentials(context: Context) = context.getSharedPreferences("flix", Context.MODE_PRIVATE).edit().clear().apply()

fun isExpired(account: AccountInfo): Boolean = account.status.equals("Expired", true) ||
    account.status.equals("Disabled", true) || account.status.equals("Banned", true)

private const val PLAYBACK_PREFS = "flix_playback"
private const val CONTINUE_KEY = "continue_entries"

fun loadContinueWatching(context: Context): List<ContinueEntry> {
    val json = context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE)
        .getString(CONTINUE_KEY, null) ?: return emptyList()
    return runCatching {
        Gson().fromJson(json, Array<ContinueEntry>::class.java).toList()
            .filter { it.progressKey.isNotBlank() && it.mediaId > 0 && it.position >= 30_000L && it.progress < .93f }
            .sortedByDescending { it.updatedAt }
            .take(20)
    }.getOrDefault(emptyList())
}

fun savedPosition(context: Context, progressKey: String): Long =
    loadContinueWatching(context).firstOrNull { it.progressKey == progressKey }?.position ?: 0L

fun savePlaybackProgress(context: Context, request: PlayRequest, position: Long, duration: Long) {
    if (request.progressKey.isBlank() || duration <= 0L) return
    val entries = loadContinueWatching(context).toMutableList()
    entries.removeAll { it.progressKey == request.progressKey }
    val ratio = position.toFloat() / duration
    if (position >= 30_000L && ratio < .93f) {
        entries.add(0, ContinueEntry(request.progressKey, request.title, request.poster, request.mediaType, request.mediaId, request.extension, position, duration, System.currentTimeMillis()))
    }
    context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE).edit()
        .putString(CONTINUE_KEY, Gson().toJson(entries.take(20))).apply()
}

fun clearPlaybackProgress(context: Context, progressKey: String) {
    if (progressKey.isBlank()) return
    val entries = loadContinueWatching(context).filterNot { it.progressKey == progressKey }
    context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE).edit()
        .putString(CONTINUE_KEY, Gson().toJson(entries)).apply()
}
