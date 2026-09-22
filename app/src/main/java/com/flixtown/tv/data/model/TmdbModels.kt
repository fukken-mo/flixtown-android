package com.flixtown.tv.data.model

import com.google.gson.annotations.SerializedName

/** Raw shape returned by GET /api/v1/tmdb/credits.php — already trimmed/ordered server-side. */
data class TmdbCreditsResponseDto(
    @SerializedName("enabled") val enabled: Boolean?,
    @SerializedName("cast") val cast: List<TmdbCastDto>?
)

data class TmdbCastDto(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("character") val character: String?,
    @SerializedName("profile_path") val profilePath: String?,
    @SerializedName("order") val order: Int?
)

/** Raw shape returned by GET /api/v1/tmdb/resolve.php. */
data class TmdbResolveResponseDto(
    @SerializedName("enabled") val enabled: Boolean?,
    @SerializedName("tmdb_id") val tmdbId: Int?
)

/** App-facing cast member — the only shape UI code ever touches. */
data class CastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profilePath: String?
)

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w185"

/**
 * null profilePath (or a load failure downstream in Coil) is the only case
 * that falls back to initials. TMDB's own API always returns profile_path
 * with a leading slash (e.g. "/abc123.jpg"), but this normalizes it anyway
 * rather than trusting that contract blindly — strips any leading slash
 * before joining, so the result always has exactly one, never zero
 * (".../w185abc.jpg") or two (".../w185//abc.jpg").
 */
val CastMember.imageUrl: String?
    get() = profilePath?.trim()?.removePrefix("/")?.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE_BASE/$it" }

fun TmdbCastDto.toDomain(): CastMember? {
    val castId = id ?: return null
    val castName = name?.takeIf { it.isNotBlank() } ?: return null
    return CastMember(
        id = castId,
        name = castName,
        character = character?.takeIf { it.isNotBlank() },
        profilePath = profilePath?.takeIf { it.isNotBlank() }
    )
}
