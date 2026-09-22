package com.flixtown.tv.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.flixtown.tv.AppGraph
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.TrailerResolver
import com.flixtown.tv.data.TrailerSource
import com.flixtown.tv.data.model.CastMember
import com.flixtown.tv.data.model.Episode
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.data.model.SeriesDetails
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.components.CastRow
import com.flixtown.tv.ui.components.DEFAULT_POSTER_WIDTH
import com.flixtown.tv.ui.components.FilterChip
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.MetadataRow
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.PrimaryActionButton
import com.flixtown.tv.ui.components.SecondaryActionButton
import com.flixtown.tv.ui.components.SectionHeader
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.player.SimpleVideoPlayerScreen
import com.flixtown.tv.ui.player.openYouTubeVideo
import com.flixtown.tv.ui.theme.FlixSpacing
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

@Composable
fun SeriesDetailsScreen(
    graph: AppGraph,
    catalogState: CatalogUiState,
    seriesId: Int,
    onPlay: (ContentScreen.Player) -> Unit,
    onSeriesClick: (Series) -> Unit
) {
    SafeLog.e("SeriesDetailsScreen", "ENTER composition seriesId=$seriesId")
    val series = (catalogState as? CatalogUiState.Loaded)?.snapshot?.series?.firstOrNull { it.seriesId == seriesId }
    SafeLog.e("SeriesDetailsScreen", "series lookup result: ${if (series == null) "NOT FOUND" else "found name=${series.name}"}")

    if (series == null) {
        Box(modifier = Modifier.fillMaxSize().background(FtBackground), contentAlignment = Alignment.Center) {
            Text(text = "Loading…", style = MaterialTheme.typography.bodyLarge, color = FtTextSecondary)
        }
        return
    }

    var details by remember(seriesId) { mutableStateOf<SeriesDetails?>(null) }
    LaunchedEffect(seriesId) {
        SafeLog.e("SeriesDetailsScreen", "getSeriesDetails START seriesId=$seriesId")
        details = try {
            graph.catalogRepository.getSeriesDetails(series).also {
                SafeLog.e("SeriesDetailsScreen", "getSeriesDetails SUCCESS seriesId=$seriesId result=${it != null}")
            }
        } catch (e: Exception) {
            SafeLog.e("SeriesDetailsScreen", "getSeriesDetails THREW for seriesId=$seriesId", e)
            null
        }
    }
    SafeLog.e("SeriesDetailsScreen", "composing body, name=${series.name} posterUrl=${series.posterUrl}")

    var selectedSeasonNumber by remember(seriesId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(details) {
        if (selectedSeasonNumber == null) {
            selectedSeasonNumber = details?.seasons?.firstOrNull()?.seasonNumber
        }
    }

    var showTrailer by remember(seriesId) { mutableStateOf(false) }
    var pendingEpisode by remember(seriesId) { mutableStateOf<Episode?>(null) }
    val context = LocalContext.current
    val trailerSource = TrailerResolver.resolve(series.trailer)

    // Same rule as Movie details: never leave focus unset when a title opens.
    val playButtonFocusRequester = remember(seriesId) { FocusRequester() }
    LaunchedEffect(seriesId) { playButtonFocusRequester.requestFocus() }

    // Same non-blocking cast load as Movie details: waits for the one
    // get_series_info fetch (already in flight above) so this only ever
    // fires once, then prefers its tmdbId over the list-level one in case
    // the detail payload resolved a more specific/different id.
    var castMembers by remember(seriesId) { mutableStateOf<List<CastMember>?>(null) }
    LaunchedEffect(details) {
        val loadedDetails = details ?: return@LaunchedEffect
        val tmdbId = loadedDetails.tmdbId ?: series.tmdbId
        val tmdbCast = graph.tmdbRepository.getSeriesCast(tmdbId, series.name, series.year)
        castMembers = tmdbCast.ifEmpty {
            series.cast.mapIndexed { index, name ->
                CastMember(id = -(index + 1), name = name, character = null, profilePath = null)
            }
        }
    }

    val similarSeries = remember(seriesId, catalogState) {
        (catalogState as? CatalogUiState.Loaded)?.snapshot?.series
            ?.filter { it.seriesId != series.seriesId && it.categoryId != null && it.categoryId == series.categoryId }
            ?.take(15)
            .orEmpty()
    }

    val inProgress = remember(seriesId) {
        graph.continueWatchingStore.getAll().firstOrNull { it.mediaType == "episode" && it.seriesId == series.seriesId }
    }

    fun launchEpisode(episode: Episode, resumeMs: Long) {
        val url = graph.catalogRepository.buildEpisodeStreamUrl(episode) ?: return
        onPlay(
            ContentScreen.Player(
                contentId = episode.id.toIntOrNull() ?: episode.id.hashCode(),
                mediaType = "episode",
                title = "${series.name} – ${episode.title}",
                posterUrl = episode.thumbnailUrl ?: series.backdropUrl ?: series.posterUrl,
                streamUrl = url,
                seriesId = series.seriesId,
                season = selectedSeasonNumber,
                episodeNumber = episode.episodeNumber,
                resumePositionMs = resumeMs,
                seriesName = series.name,
                episodeTitle = episode.title
            )
        )
    }

    fun onEpisodeClick(episode: Episode) {
        val existing = graph.continueWatchingStore.getAll().firstOrNull {
            it.mediaType == "episode" && it.seriesId == series.seriesId &&
                it.episode == episode.episodeNumber && it.season == selectedSeasonNumber
        }
        if (existing != null) pendingEpisode = episode else launchEpisode(episode, 0L)
    }

    fun playPrimary() {
        val progress = inProgress
        if (progress != null) {
            val episode = details?.seasons
                ?.firstOrNull { it.seasonNumber == progress.season }
                ?.episodes?.firstOrNull { it.episodeNumber == progress.episode }
            if (episode != null) {
                launchEpisode(episode, progress.positionMs)
                return
            }
        }
        details?.seasons?.firstOrNull()?.episodes?.firstOrNull()?.let { launchEpisode(it, 0L) }
    }

    Box(modifier = Modifier.fillMaxSize().background(FtBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Poster/title/meta overlaps the backdrop by being aligned
            // BottomStart inside this same Box — never via negative padding
            // (Modifier.padding requires non-negative values and throws
            // IllegalArgumentException; that was the crash on every open).
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                val backdropUrl = series.backdropUrl ?: series.posterUrl
                if (!backdropUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(FtSurfaceElevated))
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, FtBackground)))
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = FlixSpacing.safeHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(170.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(FtSurfaceElevated)
                    ) {
                        if (!series.posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = series.posterUrl,
                                contentDescription = series.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 620.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = series.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = FtTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        MetadataRow(
                            parts = listOfNotNull(series.year?.toString()),
                            rating = series.rating
                        )

                        if (series.genres.isNotEmpty()) {
                            Text(text = series.genres.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                        }

                        Text(
                            text = series.plot ?: "No description available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FtTextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryActionButton(
                                text = "▶ Play",
                                onClick = { playPrimary() },
                                modifier = Modifier.focusRequester(playButtonFocusRequester)
                            )
                            if (trailerSource != TrailerSource.None) {
                                SecondaryActionButton(
                                    text = "Trailer",
                                    onClick = {
                                        when (val source = trailerSource) {
                                            is TrailerSource.DirectVideo -> showTrailer = true
                                            is TrailerSource.YouTube -> openYouTubeVideo(context, source.videoId)
                                            TrailerSource.None -> Unit
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FlixSpacing.sectionGap))

            if (series.cast.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = FlixSpacing.safeHorizontal)) {
                    CastRow(cast = castMembers)
                }
                Spacer(modifier = Modifier.height(FlixSpacing.sectionGap))
            }

            val seasons = details?.seasons.orEmpty()
            if (seasons.isNotEmpty()) {
                SectionHeader(title = "Seasons", modifier = Modifier.padding(start = FlixSpacing.safeHorizontal))
                Spacer(modifier = Modifier.height(FlixSpacing.rowHeaderGap))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = FlixSpacing.safeHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(seasons, key = { it.seasonNumber }) { season ->
                        FilterChip(
                            label = season.name,
                            isSelected = season.seasonNumber == selectedSeasonNumber,
                            onClick = { selectedSeasonNumber = season.seasonNumber }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(FlixSpacing.rowHeaderGap))

                val episodes = seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }?.episodes.orEmpty()
                Column(
                    modifier = Modifier.padding(horizontal = FlixSpacing.safeHorizontal),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    episodes.forEach { episode ->
                        val progress = remember(episode.id, selectedSeasonNumber) {
                            graph.continueWatchingStore.getAll().firstOrNull {
                                it.mediaType == "episode" && it.seriesId == series.seriesId &&
                                    it.episode == episode.episodeNumber && it.season == selectedSeasonNumber
                            }
                        }
                        val progressFraction = progress?.let {
                            if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs.toFloat()).coerceIn(0f, 1f) else null
                        }
                        EpisodeCard(episode, progressFraction = progressFraction, onClick = { onEpisodeClick(episode) })
                    }
                }
            } else if (details != null) {
                Box(modifier = Modifier.padding(horizontal = FlixSpacing.safeHorizontal)) {
                    Text(text = "No episode data available.", style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                }
            }

            if (similarSeries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(FlixSpacing.sectionGap))
                Column(verticalArrangement = Arrangement.spacedBy(FlixSpacing.rowHeaderGap)) {
                    SectionHeader(title = "More Like This", modifier = Modifier.padding(start = FlixSpacing.safeHorizontal))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = FlixSpacing.safeHorizontal, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap)
                    ) {
                        items(similarSeries, key = { it.seriesId }) { similar ->
                            PosterCard(
                                title = similar.name,
                                posterUrl = similar.posterUrl,
                                subtitle = similar.year?.toString(),
                                onClick = { onSeriesClick(similar) },
                                modifier = Modifier.width(DEFAULT_POSTER_WIDTH)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FlixSpacing.safeVertical))
        }

        if (showTrailer) {
            val source = trailerSource
            if (source is TrailerSource.DirectVideo) {
                SimpleVideoPlayerScreen(videoUrl = source.url, onClose = { showTrailer = false })
            }
        }

        val pending = pendingEpisode
        if (pending != null) {
            val existing = graph.continueWatchingStore.getAll().firstOrNull {
                it.mediaType == "episode" && it.seriesId == series.seriesId &&
                    it.episode == pending.episodeNumber && it.season == selectedSeasonNumber
            }
            Box(modifier = Modifier.align(Alignment.Center)) {
                SelectorMenu(
                    title = "Continue Watching?",
                    options = listOf("Resume", "Restart"),
                    selected = "Resume",
                    optionLabel = { it },
                    onSelect = { choice ->
                        launchEpisode(pending, if (choice == "Resume") existing?.positionMs ?: 0L else 0L)
                        pendingEpisode = null
                    },
                    onDismiss = { pendingEpisode = null }
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, progressFraction: Float?, onClick: () -> Unit) {
    FlixFocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FtSurfaceElevated)
            ) {
                if (!episode.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.thumbnailUrl,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (progressFraction != null && progressFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(FtBackground.copy(alpha = 0.6f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .background(FtAccent)
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val header = listOfNotNull("Episode ${episode.episodeNumber}", episode.runtimeMinutes?.let { "${it}m" })
                Text(text = header.joinToString("   •   "), style = MaterialTheme.typography.labelMedium, color = FtTextSecondary)
                Text(text = episode.title, style = MaterialTheme.typography.titleMedium, color = FtTextPrimary)
                if (!episode.plot.isNullOrBlank()) {
                    Text(
                        text = episode.plot,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FtTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
