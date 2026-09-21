package com.flixtown.tv.data

import android.content.Context
import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.model.Category
import com.flixtown.tv.data.model.CategoryDto
import com.flixtown.tv.data.model.CatalogSnapshot
import com.flixtown.tv.data.model.Episode
import com.flixtown.tv.data.model.EpisodeDto
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.MovieDetails
import com.flixtown.tv.data.model.SeasonInfo
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.data.model.SeriesDetails
import com.flixtown.tv.data.model.SeriesDto
import com.flixtown.tv.data.model.SeriesInfoResponseDto
import com.flixtown.tv.data.model.VodInfoResponseDto
import com.flixtown.tv.data.model.VodStreamDto
import com.flixtown.tv.security.SecureCredentialStore
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Loads the Movies/Series catalog from the authenticated Xtream account.
 *
 * Category/movie/series lists are fetched together, mapped to domain models,
 * and cached (SharedPreferences + Gson, same pattern as [ConfigRepository])
 * with a 30-minute TTL: [getCachedSnapshot] is instant and used first, while
 * [refresh] does the network work in the background — the catalog screens
 * never block a cold Home render on a full-catalog network round trip.
 *
 * Per-item detail calls ([getMovieDetails], [getSeriesDetails]) are small
 * and not long-term cached; they run on demand when a details screen opens.
 */
class XtreamCatalogRepository(
    context: Context,
    private val configRepository: ConfigRepository,
    private val secureStore: SecureCredentialStore
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCachedSnapshot(): CatalogSnapshot? {
        val json = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            NetworkModule.gson.fromJson(json, CatalogSnapshot::class.java)
        } catch (e: Exception) {
            SafeLog.w(TAG, "Cached catalog was corrupt, ignoring", e)
            null
        }
    }

    fun isCacheStale(snapshot: CatalogSnapshot): Boolean =
        System.currentTimeMillis() - snapshot.fetchedAtMillis > CACHE_TTL_MILLIS

    suspend fun refresh(): Result<CatalogSnapshot> = withContext(Dispatchers.IO) {
        try {
            val (baseUrl, username, password) = credentialsOrThrow()

            coroutineScope {
                val vodCategoriesDeferred = async { fetchList<CategoryDto>(baseUrl, username, password, "get_vod_categories") }
                val moviesDeferred = async { fetchList<VodStreamDto>(baseUrl, username, password, "get_vod_streams") }
                val seriesCategoriesDeferred = async { fetchList<CategoryDto>(baseUrl, username, password, "get_series_categories") }
                val seriesDeferred = async { fetchList<SeriesDto>(baseUrl, username, password, "get_series") }

                val snapshot = CatalogSnapshot(
                    vodCategories = vodCategoriesDeferred.await().map { it.toDomain() },
                    movies = moviesDeferred.await().mapNotNull { it.toDomain() },
                    seriesCategories = seriesCategoriesDeferred.await().map { it.toDomain() },
                    series = seriesDeferred.await().mapNotNull { it.toDomain() },
                    fetchedAtMillis = System.currentTimeMillis()
                )

                prefs.edit().putString(KEY_SNAPSHOT, NetworkModule.gson.toJson(snapshot)).apply()
                Result.success(snapshot)
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Catalog refresh failed, keeping last known good catalog", e)
            Result.failure(e)
        }
    }

    suspend fun getMovieDetails(movie: Movie): MovieDetails? = withContext(Dispatchers.IO) {
        try {
            val (baseUrl, username, password) = credentialsOrThrow()
            val body = fetchRaw(baseUrl, username, password, "get_vod_info", "vod_id" to movie.streamId.toString())
                ?: return@withContext null
            val dto = NetworkModule.gson.fromJson(body, VodInfoResponseDto::class.java)
            val info = dto.info

            MovieDetails(
                movie = movie,
                plot = info?.plot?.takeIf { it.isNotBlank() },
                cast = splitCommaList(info?.cast),
                director = info?.director?.takeIf { it.isNotBlank() },
                genres = splitCommaList(info?.genre),
                releaseDate = info?.releaseDate ?: info?.releaseDateAlt,
                runtimeMinutes = parseRuntimeMinutes(info?.durationSecs, info?.duration),
                backdropUrl = info?.backdropPath?.firstOrNull() ?: info?.movieImage ?: movie.posterUrl,
                trailer = info?.youtubeTrailer?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            SafeLog.w(TAG, "getMovieDetails failed", e)
            null
        }
    }

    suspend fun getSeriesDetails(series: Series): SeriesDetails? = withContext(Dispatchers.IO) {
        try {
            val (baseUrl, username, password) = credentialsOrThrow()
            val body = fetchRaw(baseUrl, username, password, "get_series_info", "series_id" to series.seriesId.toString())
                ?: return@withContext null
            val dto = NetworkModule.gson.fromJson(body, SeriesInfoResponseDto::class.java)

            val episodesBySeason = dto.episodes.orEmpty()
            val seasons = (dto.seasons.orEmpty())
                .mapNotNull { seasonDto ->
                    val seasonNumber = seasonDto.seasonNumber ?: return@mapNotNull null
                    val episodeDtos = episodesBySeason[seasonNumber.toString()].orEmpty()
                    SeasonInfo(
                        seasonNumber = seasonNumber,
                        name = seasonDto.name?.takeIf { it.isNotBlank() } ?: "Season $seasonNumber",
                        episodes = episodeDtos.mapNotNull { it.toDomain(series.posterUrl) }
                    )
                }
                .sortedBy { it.seasonNumber }

            SeriesDetails(series = series, seasons = seasons)
        } catch (e: Exception) {
            SafeLog.w(TAG, "getSeriesDetails failed", e)
            null
        }
    }

    private data class Credentials(val baseUrl: String, val username: String, val password: String)

    private fun credentialsOrThrow(): Credentials {
        val baseUrl = configRepository.getCached()?.xtreamBaseUrl
            ?: throw IllegalStateException("No Xtream server URL configured")
        val username = secureStore.getXtreamUsername() ?: throw IllegalStateException("No stored Xtream username")
        val password = secureStore.getXtreamPassword() ?: throw IllegalStateException("No stored Xtream password")
        return Credentials(baseUrl, username, password)
    }

    private inline fun <reified T> fetchList(baseUrl: String, username: String, password: String, action: String): List<T> {
        val body = fetchRaw(baseUrl, username, password, action) ?: return emptyList()
        return try {
            val type = TypeToken.getParameterized(List::class.java, T::class.java).type
            NetworkModule.gson.fromJson<List<T>>(body, type) ?: emptyList()
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to parse $action response", e)
            emptyList()
        }
    }

    private fun fetchRaw(
        baseUrl: String,
        username: String,
        password: String,
        action: String,
        vararg extraParams: Pair<String, String>
    ): String? {
        val url = "$baseUrl/player_api.php".toHttpUrlOrNull() ?: return null
        val builder: HttpUrl.Builder = url.newBuilder()
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .addQueryParameter("action", action)
        extraParams.forEach { (key, value) -> builder.addQueryParameter(key, value) }

        val request = Request.Builder().url(builder.build()).get().build()
        NetworkModule.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    companion object {
        private const val TAG = "XtreamCatalogRepository"
        private const val PREFS_NAME = "flixtown_catalog_cache"
        private const val KEY_SNAPSHOT = "catalog_snapshot"
        private const val CACHE_TTL_MILLIS = 30L * 60L * 1000L
    }
}

// ---- DTO -> domain mapping -------------------------------------------------

private fun CategoryDto.toDomain(): Category =
    Category(id = categoryId.orEmpty(), name = categoryName?.takeIf { it.isNotBlank() } ?: "Uncategorized")

private fun VodStreamDto.toDomain(): Movie? {
    val id = streamId ?: return null
    val title = name?.takeIf { it.isNotBlank() } ?: return null
    return Movie(
        streamId = id,
        name = title,
        posterUrl = streamIcon?.takeIf { it.isNotBlank() },
        rating = rating?.toDoubleOrNull() ?: rating5Based,
        addedEpochSeconds = addedEpochSeconds?.toLongOrNull() ?: 0L,
        categoryId = categoryId,
        year = year?.toIntOrNull() ?: extractYearFromTitle(title)
    )
}

private fun SeriesDto.toDomain(): Series? {
    val id = seriesId ?: return null
    val title = name?.takeIf { it.isNotBlank() } ?: return null
    return Series(
        seriesId = id,
        name = title,
        posterUrl = cover?.takeIf { it.isNotBlank() },
        plot = plot?.takeIf { it.isNotBlank() },
        cast = splitCommaList(cast),
        genres = splitCommaList(genre),
        rating = rating?.toDoubleOrNull() ?: rating5Based,
        addedEpochSeconds = lastModifiedEpochSeconds?.toLongOrNull() ?: 0L,
        categoryId = categoryId,
        year = (releaseDate ?: releaseDateAlt)?.take(4)?.toIntOrNull() ?: extractYearFromTitle(title),
        backdropUrl = backdropPath?.firstOrNull(),
        trailer = youtubeTrailer?.takeIf { it.isNotBlank() }
    )
}

private fun EpisodeDto.toDomain(fallbackImage: String?): Episode? {
    val episodeId = id ?: return null
    val number = episodeNum ?: return null
    return Episode(
        id = episodeId,
        episodeNumber = number,
        title = title?.takeIf { it.isNotBlank() } ?: "Episode $number",
        thumbnailUrl = info?.movieImage?.takeIf { it.isNotBlank() } ?: fallbackImage,
        plot = info?.plot?.takeIf { it.isNotBlank() },
        runtimeMinutes = parseRuntimeMinutes(info?.durationSecs, info?.duration)
    )
}

private fun splitCommaList(value: String?): List<String> =
    value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

private fun parseRuntimeMinutes(durationSecs: Int?, duration: String?): Int? {
    if (durationSecs != null && durationSecs > 0) return durationSecs / 60
    val parts = duration?.split(":")?.mapNotNull { it.toIntOrNull() } ?: return null
    return when (parts.size) {
        3 -> parts[0] * 60 + parts[1]
        2 -> parts[0]
        else -> null
    }
}

private val TRAILING_YEAR_REGEX = Regex("""\((\d{4})\)\s*$""")

private fun extractYearFromTitle(title: String): Int? =
    TRAILING_YEAR_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
