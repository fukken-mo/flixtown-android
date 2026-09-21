package com.flixtown.tv.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.flixtown.tv.AppGraph
import com.flixtown.tv.data.TrailerResolver
import com.flixtown.tv.data.TrailerSource
import com.flixtown.tv.data.model.Episode
import com.flixtown.tv.data.model.SeriesDetails
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.components.CastRow
import com.flixtown.tv.ui.components.FilterChip
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.player.SimpleVideoPlayerScreen
import com.flixtown.tv.ui.player.openYouTubeVideo
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

@Composable
fun SeriesDetailsScreen(graph: AppGraph, catalogState: CatalogUiState, seriesId: Int) {
    val series = (catalogState as? CatalogUiState.Loaded)?.snapshot?.series?.firstOrNull { it.seriesId == seriesId }

    if (series == null) {
        Box(modifier = Modifier.fillMaxSize().background(FtBackground), contentAlignment = Alignment.Center) {
            Text(text = "Loading…", style = MaterialTheme.typography.bodyLarge, color = FtTextSecondary)
        }
        return
    }

    var details by remember(seriesId) { mutableStateOf<SeriesDetails?>(null) }
    LaunchedEffect(seriesId) { details = graph.catalogRepository.getSeriesDetails(series) }

    var selectedSeasonNumber by remember(seriesId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(details) {
        if (selectedSeasonNumber == null) {
            selectedSeasonNumber = details?.seasons?.firstOrNull()?.seasonNumber
        }
    }

    var showTrailer by remember(seriesId) { mutableStateOf(false) }
    val context = LocalContext.current
    val trailerSource = TrailerResolver.resolve(series.trailer)

    Box(modifier = Modifier.fillMaxSize().background(FtBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
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
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(200.dp)
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
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = series.name, style = MaterialTheme.typography.headlineLarge, color = FtTextPrimary)

                    val metaParts = listOfNotNull(
                        series.year?.toString(),
                        series.rating?.let { "★ ${"%.1f".format(it)}" }
                    )
                    if (metaParts.isNotEmpty()) {
                        Text(text = metaParts.joinToString("   •   "), style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                    }

                    if (series.genres.isNotEmpty()) {
                        Text(text = series.genres.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                    }

                    Text(
                        text = series.plot ?: "No description available.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = FtTextSecondary
                    )

                    if (trailerSource != TrailerSource.None) {
                        FlixFocusSurface(
                            onClick = {
                                when (val source = trailerSource) {
                                    is TrailerSource.DirectVideo -> showTrailer = true
                                    is TrailerSource.YouTube -> openYouTubeVideo(context, source.videoId)
                                    TrailerSource.None -> Unit
                                }
                            }
                        ) {
                            Text("Trailer")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (series.cast.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                    CastRow(cast = series.cast.map { it to null })
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            val seasons = details?.seasons.orEmpty()
            if (seasons.isNotEmpty()) {
                Box(modifier = Modifier.padding(start = 40.dp)) {
                    Text(text = "Seasons", style = MaterialTheme.typography.titleMedium, color = FtTextPrimary)
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 40.dp),
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

                Spacer(modifier = Modifier.height(20.dp))

                val episodes = seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }?.episodes.orEmpty()
                Column(
                    modifier = Modifier.padding(horizontal = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    episodes.forEach { episode -> EpisodeCard(episode) }
                }
            } else if (details != null) {
                Box(modifier = Modifier.padding(horizontal = 40.dp)) {
                    Text(text = "No episode data available.", style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }

        if (showTrailer) {
            val source = trailerSource
            if (source is TrailerSource.DirectVideo) {
                SimpleVideoPlayerScreen(videoUrl = source.url, onClose = { showTrailer = false })
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode) {
    FlixFocusSurface(onClick = { /* Playback ships in the next milestone. */ }, modifier = Modifier.fillMaxWidth()) {
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
