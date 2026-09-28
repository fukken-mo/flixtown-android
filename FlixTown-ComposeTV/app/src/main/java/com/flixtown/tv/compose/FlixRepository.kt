package com.flixtown.tv.compose

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TvAccount(val server: String, val username: String, val password: String)
data class PairCode(val code: String, val verifier: String, val activationUrl: String)
data class MovieInfo(val year: String = "", val duration: String = "",
                     val contentRating: String = "", val synopsis: String = "", val trailer: String = "")

class FlixRepository(private val context: Context) {
    companion object { const val PANEL = "https://panelsandapps.com/panels/flixtown2027/api/" }
    private val prefs = context.getSharedPreferences("flix_compose", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("flix_compose_account", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("flix_compose_account",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun savedAccount(): TvAccount? {
      return try {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("account", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val value = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        val parts = value.split('\n', limit = 3)
        if (parts.size == 3) TvAccount(parts[0], parts[1], parts[2]) else null
      } catch (_: Exception) { null }
    }

    private fun save(account: TvAccount) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal("${account.server}\n${account.username}\n${account.password}".toByteArray())
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("account", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun signOut() { prefs.edit().clear().apply() }
    fun inWatchlist(id: String): Boolean = prefs.getStringSet("watchlist", emptySet())?.contains(id) == true
    fun toggleWatchlist(id: String): Boolean {
        val updated = prefs.getStringSet("watchlist", emptySet()).orEmpty().toMutableSet()
        if (!updated.add(id)) updated.remove(id)
        prefs.edit().putStringSet("watchlist", updated).apply()
        return id in updated
    }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun request(url: String, body: JSONObject? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 14000
            instanceFollowRedirects = false
            if (body != null) {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        try {
            val status = conn.responseCode
            val response = (if (status >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val error = runCatching { JSONObject(response).optString("error") }.getOrNull()
                throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "Server returned $status")
            }
            return response
        } finally { conn.disconnect() }
    }

    private fun config() = JSONObject(request(PANEL + "config.php"))
    private fun base(server: String) = server.trimEnd('/')
    private fun accountUrl(a: TvAccount) = "${base(a.server)}/player_api.php?username=${enc(a.username)}&password=${enc(a.password)}"
    private fun api(a: TvAccount, action: String, extra: String = "") =
        request(accountUrl(a) + "&action=${enc(action)}" + extra)

    suspend fun signIn(username: String, password: String): TvAccount = withContext(Dispatchers.IO) {
        val server = config().getString("xtream_url")
        require(server.startsWith("http://") || server.startsWith("https://")) { "Panel server URL is invalid" }
        val account = TvAccount(server, username.trim(), password)
        val info = JSONObject(request(accountUrl(account))).optJSONObject("user_info")
        require(info?.optString("status").equals("Active", true)) { "Account is not active" }
        save(account); account
    }

    suspend fun verify(account: TvAccount): Boolean = withContext(Dispatchers.IO) {
        val info = JSONObject(request(accountUrl(account))).optJSONObject("user_info")
        info?.optString("status").equals("Active", true)
    }

    suspend fun startPair(): PairCode = withContext(Dispatchers.IO) {
        val j = JSONObject(request(PANEL + "pair-start.php", JSONObject()))
        PairCode(j.getString("code"), j.getString("verifier"), j.getString("activation_url"))
    }

    suspend fun pollPair(pair: PairCode): TvAccount? = withContext(Dispatchers.IO) {
        val j = JSONObject(request(PANEL + "pair-poll.php",
            JSONObject().put("code", pair.code).put("verifier", pair.verifier)))
        if (j.optString("status") != "approved") return@withContext null
        val credentials = j.getJSONObject("account")
        // Keep the server in panel configuration, never in the QR response.
        val account = TvAccount(config().getString("xtream_url"),
            credentials.getString("username"), credentials.getString("password"))
        if (!verify(account)) throw IllegalStateException("Account is not active")
        save(account); account
    }

    suspend fun browse(account: TvAccount): BrowseCatalog = withContext(Dispatchers.IO) {
        coroutineScope {
            val moviesJob = async { parse(JSONArray(api(account, "get_vod_streams")), "movie", account) }
            val seriesJob = async { parse(JSONArray(api(account, "get_series")), "series", account) }
            val movies = moviesJob.await(); val series = seriesJob.await()
            val latestMovies = movies.sortedByDescending { it.added }.take(32)
            val latestSeries = series.sortedByDescending { it.added }.take(32)
            val topMovies = movies.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(32)
            BrowseCatalog(
                featured = latestMovies.firstOrNull() ?: latestSeries.firstOrNull(),
                rows = listOf("Latest Movies" to latestMovies, "Latest Series" to latestSeries,
                    "Top Rated Movies" to topMovies)
            )
        }
    }

    private fun parse(array: JSONArray, type: String, account: TvAccount): List<TvTitle> = buildList {
        for (i in 0 until array.length()) {
            val j = array.optJSONObject(i) ?: continue
            val id = j.optString(if (type == "movie") "stream_id" else "series_id")
            if (id.isBlank()) continue
            val image = j.optString(if (type == "movie") "stream_icon" else "cover")
            val backdropRaw = j.opt("backdrop_path")
            val backdrop = when (backdropRaw) {
                is JSONArray -> backdropRaw.optString(0, image)
                is String -> backdropRaw.takeUnless { it.startsWith("[") } ?: image
                else -> image
            }
            val extension = j.optString("container_extension", "mp4").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,6}")) } ?: "mp4"
            val stream = if (type == "movie") "${base(account.server)}/movie/${enc(account.username)}/${enc(account.password)}/$id.$extension" else ""
            add(TvTitle(id = "$type:$id", name = j.optString("name", "Untitled"),
                year = j.optString("year"), overview = j.optString("plot", j.optString("description")),
                tags = listOf(j.optString("year"), j.optString("genre"), j.optString("rating"))
                    .filter { it.isNotBlank() }.joinToString("  •  "),
                posterUrl = image, backdropUrl = backdrop, streamUrl = stream,
                kind = type, added = j.optLong("added", j.optLong("last_modified")),
                rating = j.optDouble("rating", 0.0), categoryId = j.optString("category_id")))
        }
    }

    suspend fun movieInfo(account: TvAccount, movie: TvTitle): MovieInfo = withContext(Dispatchers.IO) {
        val j = JSONObject(api(account, "get_vod_info", "&vod_id=${enc(movie.id.substringAfter(':'))}"))
        val info = j.optJSONObject("info") ?: JSONObject()
        val rawTrailer = info.optString("youtube_trailer", "")
        val trailer = when {
            rawTrailer.startsWith("https://") -> rawTrailer
            rawTrailer.matches(Regex("[a-zA-Z0-9_-]{11}")) -> "https://www.youtube.com/watch?v=$rawTrailer"
            else -> ""
        }
        MovieInfo(year = info.optString("releasedate", movie.year).take(4),
            duration = info.optString("duration"),
            contentRating = info.optString("age", info.optString("rated")),
            synopsis = info.optString("plot", movie.overview), trailer = trailer)
    }

    suspend fun episodes(account: TvAccount, series: TvTitle): Map<Int, List<TvTitle>> = withContext(Dispatchers.IO) {
        val id = series.id.substringAfter(':')
        val info = JSONObject(api(account, "get_series_info", "&series_id=${enc(id)}"))
        val seasons = info.optJSONObject("episodes") ?: return@withContext emptyMap()
        buildMap {
            for (season in seasons.keys()) {
                val number = season.toIntOrNull() ?: continue
                val list = seasons.optJSONArray(season) ?: continue
                put(number, buildList {
                    for (i in 0 until list.length()) {
                        val episode = list.optJSONObject(i) ?: continue
                        val episodeId = episode.optString("id")
                        if (episodeId.isBlank()) continue
                        val extension = episode.optString("container_extension", "mp4")
                            .takeIf { it.matches(Regex("[a-zA-Z0-9]{1,6}")) } ?: "mp4"
                        val image = episode.optJSONObject("info")?.optString("movie_image").orEmpty()
                        add(TvTitle("episode:$episodeId", episode.optString("title", "Episode ${i + 1}"),
                            overview = episode.optJSONObject("info")?.optString("plot").orEmpty(),
                            posterUrl = image, kind = "episode",
                            streamUrl = "${base(account.server)}/series/${enc(account.username)}/${enc(account.password)}/$episodeId.$extension"))
                    }
                })
            }
        }.toSortedMap()
    }
}
