package com.flixtown.tv.data.model

import com.google.gson.annotations.SerializedName

/**
 * Xtream `player_api.php` response shapes. Field names follow the widely
 * documented Xtream Codes API; a real-world panel occasionally omits a
 * field, so everything content-bearing is nullable and defensively parsed
 * rather than assumed present.
 */

data class CategoryDto(
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("category_name") val categoryName: String?
)

data class Category(val id: String, val name: String)

// ---- VOD (movies) ----

data class VodStreamDto(
    @SerializedName("stream_id") val streamId: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double?,
    @SerializedName("added") val addedEpochSeconds: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("year") val year: String?
)

data class Movie(
    val streamId: Int,
    val name: String,
    val posterUrl: String?,
    val rating: Double?,
    val addedEpochSeconds: Long,
    val categoryId: String?,
    val year: Int?,
    val containerExtension: String
)

data class VodInfoResponseDto(
    @SerializedName("info") val info: VodInfoDto?,
    @SerializedName("movie_data") val movieData: VodStreamDto?
)

data class VodInfoDto(
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("releasedate") val releaseDateAlt: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?
)

data class MovieDetails(
    val movie: Movie,
    val plot: String?,
    val cast: List<String>,
    val director: String?,
    val genres: List<String>,
    val releaseDate: String?,
    val runtimeMinutes: Int?,
    val backdropUrl: String?,
    val trailer: String?
)

// ---- Series ----

data class SeriesDto(
    @SerializedName("series_id") val seriesId: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("releaseDate") val releaseDateAlt: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double?,
    @SerializedName("last_modified") val lastModifiedEpochSeconds: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?
)

data class Series(
    val seriesId: Int,
    val name: String,
    val posterUrl: String?,
    val plot: String?,
    val cast: List<String>,
    val genres: List<String>,
    val rating: Double?,
    val addedEpochSeconds: Long,
    val categoryId: String?,
    val year: Int?,
    val backdropUrl: String?,
    val trailer: String?
)

data class SeriesInfoResponseDto(
    @SerializedName("info") val info: SeriesDto?,
    @SerializedName("seasons") val seasons: List<SeasonDto>?,
    @SerializedName("episodes") val episodes: Map<String, List<EpisodeDto>>?
)

data class SeasonDto(
    @SerializedName("season_number") val seasonNumber: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("episode_count") val episodeCount: Int?,
    @SerializedName("cover") val cover: String?
)

data class EpisodeDto(
    @SerializedName("id") val id: String?,
    @SerializedName("episode_num") val episodeNum: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("season") val season: Int?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: EpisodeInfoDto?
)

data class EpisodeInfoDto(
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("duration") val duration: String?
)

data class SeriesDetails(
    val series: Series,
    val seasons: List<SeasonInfo>
)

data class SeasonInfo(
    val seasonNumber: Int,
    val name: String,
    val episodes: List<Episode>
)

data class Episode(
    val id: String,
    val episodeNumber: Int,
    val title: String,
    val thumbnailUrl: String?,
    val plot: String?,
    val runtimeMinutes: Int?,
    val containerExtension: String
)
