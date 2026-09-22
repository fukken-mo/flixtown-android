package com.flixtown.tv.data

import com.flixtown.tv.core.BackendConstants
import com.flixtown.tv.core.NetworkModule
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.model.CastMember
import com.flixtown.tv.data.model.TmdbCreditsResponseDto
import com.flixtown.tv.data.model.TmdbResolveResponseDto
import com.flixtown.tv.data.model.toDomain
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Cast enrichment via the backend's TMDB proxy (never calls TMDB directly —
 * the API key lives only on the server, see backend/includes/tmdb_client.php).
 * Every call is best-effort: a disabled/unreachable/misconfigured TMDB proxy
 * just means an empty list comes back, never an exception the caller has to
 * handle — Xtream's plain-name cast list is always a valid fallback the UI
 * already renders.
 *
 * Two in-memory caches (process lifetime only, cleared on app restart):
 *  - credits by "movie:<tmdbId>" / "tv:<tmdbId>" — the actual cast/photo data
 *  - resolved ids by "movie:<title>:<year>" / "tv:<title>:<year>" — so a
 *    title with no Xtream-provided tmdb id only ever triggers one /search
 *    call total, including caching a negative (no-match) result, however
 *    many times its details screen is reopened or focused.
 */
class TmdbRepository(private val configRepository: ConfigRepository) {

    private val creditsCache = ConcurrentHashMap<String, List<CastMember>>()

    // ConcurrentHashMap rejects null values outright (throws on put), but a
    // resolved-to-no-match result is a legitimate, cacheable outcome here —
    // NO_MATCH stands in for it instead of storing null.
    private val resolvedIdCache = ConcurrentHashMap<String, Int>()

    suspend fun getMovieCast(tmdbIdFromXtream: Int?, title: String, year: Int?): List<CastMember> =
        getCast(type = "movie", tmdbIdFromXtream = tmdbIdFromXtream, title = title, year = year)

    suspend fun getSeriesCast(tmdbIdFromXtream: Int?, title: String, year: Int?): List<CastMember> =
        getCast(type = "tv", tmdbIdFromXtream = tmdbIdFromXtream, title = title, year = year)

    private suspend fun getCast(type: String, tmdbIdFromXtream: Int?, title: String, year: Int?): List<CastMember> {
        if (configRepository.getCached()?.tmdbEnabled != true) {
            SafeLog.d(TAG, "getCast($type, \"$title\") skipped: tmdb_enabled is false/missing in cached config")
            return emptyList()
        }

        val tmdbId = tmdbIdFromXtream ?: resolveTmdbId(type, title, year)
        if (tmdbId == null) {
            SafeLog.d(TAG, "getCast($type, \"$title\") no tmdb id (neither Xtream nor /resolve produced one)")
            return emptyList()
        }
        val cacheKey = "$type:$tmdbId"
        creditsCache[cacheKey]?.let { return it }

        val fetched = fetchCredits(type, tmdbId)
        SafeLog.d(TAG, "getCast($type, \"$title\") tmdbId=$tmdbId -> ${fetched.size} cast members")
        creditsCache[cacheKey] = fetched
        return fetched
    }

    private suspend fun resolveTmdbId(type: String, title: String, year: Int?): Int? {
        val resolveKey = "$type:${title.lowercase()}:${year ?: 0}"
        resolvedIdCache[resolveKey]?.let { cached -> return cached.takeIf { it != NO_MATCH } }

        val resolved = withContext(Dispatchers.IO) {
            try {
                val url = BackendConstants.TMDB_RESOLVE_ENDPOINT.toHttpUrlOrNull() ?: return@withContext null
                val builder = url.newBuilder()
                    .addQueryParameter("type", type)
                    .addQueryParameter("query", title)
                if (year != null && year > 0) builder.addQueryParameter("year", year.toString())

                val request = Request.Builder().url(builder.build()).get().build()
                NetworkModule.client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body.isNullOrBlank()) {
                        SafeLog.d(TAG, "resolve HTTP ${response.code} for \"$title\" (body ${if (body.isNullOrBlank()) "empty" else "present"})")
                        return@withContext null
                    }
                    NetworkModule.gson.fromJson(body, TmdbResolveResponseDto::class.java)?.tmdbId
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "TMDB resolve failed for \"$title\"", e)
                null
            }
        }

        resolvedIdCache[resolveKey] = resolved ?: NO_MATCH
        return resolved
    }

    private suspend fun fetchCredits(type: String, tmdbId: Int): List<CastMember> = withContext(Dispatchers.IO) {
        try {
            val url = BackendConstants.TMDB_CREDITS_ENDPOINT.toHttpUrlOrNull() ?: return@withContext emptyList()
            val builder = url.newBuilder()
                .addQueryParameter("type", type)
                .addQueryParameter("tmdb_id", tmdbId.toString())

            val request = Request.Builder().url(builder.build()).get().build()
            NetworkModule.client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrBlank()) {
                    SafeLog.d(TAG, "credits HTTP ${response.code} for $type/$tmdbId (body ${if (body.isNullOrBlank()) "empty" else "present"})")
                    return@withContext emptyList()
                }
                val dto = NetworkModule.gson.fromJson(body, TmdbCreditsResponseDto::class.java)
                dto?.cast.orEmpty().mapNotNull { it.toDomain() }
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "TMDB credits fetch failed for $type/$tmdbId", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "TmdbRepository"
        private const val NO_MATCH = -1
    }
}
