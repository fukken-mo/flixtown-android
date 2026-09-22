package com.flixtown.tv.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.flixtown.tv.AppGraph
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.TrailerResolver
import com.flixtown.tv.data.TrailerSource
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.MovieDetails
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.components.CastRow
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.player.SimpleVideoPlayerScreen
import com.flixtown.tv.ui.player.openYouTubeVideo
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

@Composable
fun MovieDetailsScreen(
    graph: AppGraph,
    catalogState: CatalogUiState,
    streamId: Int,
    onPlay: (ContentScreen.Player) -> Unit
) {
    SafeLog.e("MovieDetailsScreen", "ENTER composition streamId=$streamId")
    val movie = (catalogState as? CatalogUiState.Loaded)?.snapshot?.movies?.firstOrNull { it.streamId == streamId }
    SafeLog.e("MovieDetailsScreen", "movie lookup result: ${if (movie == null) "NOT FOUND" else "found name=${movie.name}"}")

    if (movie == null) {
        Box(modifier = Modifier.fillMaxSize().background(FtBackground), contentAlignment = Alignment.Center) {
            Text(text = "Loading…", style = MaterialTheme.typography.bodyLarge, color = FtTextSecondary)
        }
        return
    }

    var details by remember(streamId) { mutableStateOf<MovieDetails?>(null) }
    LaunchedEffect(streamId) {
        SafeLog.e("MovieDetailsScreen", "getMovieDetails START streamId=$streamId")
        details = try {
            graph.catalogRepository.getMovieDetails(movie).also {
                SafeLog.e("MovieDetailsScreen", "getMovieDetails SUCCESS streamId=$streamId result=${it != null}")
            }
        } catch (e: Exception) {
            SafeLog.e("MovieDetailsScreen", "getMovieDetails THREW for streamId=$streamId", e)
            null
        }
    }
    SafeLog.e("MovieDetailsScreen", "composing body, name=${movie.name} posterUrl=${movie.posterUrl}")

    var showTrailer by remember(streamId) { mutableStateOf(false) }
    var showResumeMenu by remember(streamId) { mutableStateOf(false) }
    val context = LocalContext.current
    val trailerSource = TrailerResolver.resolve(details?.trailer)

    val existingProgress = remember(streamId) {
        graph.continueWatchingStore.getAll().firstOrNull { it.streamId == movie.streamId && it.mediaType == "movie" }
    }

    fun launchPlayer(resumeMs: Long) {
        val url = graph.catalogRepository.buildMovieStreamUrl(movie) ?: return
        onPlay(
            ContentScreen.Player(
                contentId = movie.streamId,
                mediaType = "movie",
                title = movie.name,
                posterUrl = movie.posterUrl,
                streamUrl = url,
                resumePositionMs = resumeMs
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(FtBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // The poster/title/meta row overlaps the bottom of the backdrop
            // by being a child of this same Box, aligned to BottomStart —
            // not by giving it negative padding (Modifier.padding requires
            // non-negative values and throws IllegalArgumentException; a
            // literal `top = (-56).dp` here is what crashed every details
            // open). This Box is sized tall enough to hold both the visible
            // backdrop strip above and the full poster height below.
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                val backdropUrl = details?.backdropUrl ?: movie.posterUrl
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
                        .padding(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(170.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(FtSurfaceElevated)
                    ) {
                        if (!movie.posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = movie.posterUrl,
                                contentDescription = movie.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(text = movie.name, style = MaterialTheme.typography.headlineLarge, color = FtTextPrimary)

                        val metaParts = listOfNotNull(
                            movie.year?.toString(),
                            details?.runtimeMinutes?.let { "${it}m" },
                            movie.rating?.let { "★ ${"%.1f".format(it)}" }
                        )
                        if (metaParts.isNotEmpty()) {
                            Text(text = metaParts.joinToString("   •   "), style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                        }

                        if (!details?.genres.isNullOrEmpty()) {
                            Text(text = details!!.genres.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                        }

                        Text(
                            text = details?.plot ?: "No description available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FtTextSecondary,
                            maxLines = 3
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FlixFocusSurface(
                                onClick = {
                                    if (existingProgress != null) showResumeMenu = true else launchPlayer(0L)
                                }
                            ) {
                                Text("Play")
                            }
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
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                CastRow(cast = (details?.cast ?: emptyList()).map { it to null })
            }

            Spacer(modifier = Modifier.height(40.dp))
        }

        if (showTrailer) {
            val source = trailerSource
            if (source is TrailerSource.DirectVideo) {
                SimpleVideoPlayerScreen(videoUrl = source.url, onClose = { showTrailer = false })
            }
        }

        if (showResumeMenu) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                SelectorMenu(
                    title = "Continue Watching?",
                    options = listOf("Resume", "Restart"),
                    selected = "Resume",
                    optionLabel = { it },
                    onSelect = { choice ->
                        showResumeMenu = false
                        launchPlayer(if (choice == "Resume") existingProgress?.positionMs ?: 0L else 0L)
                    },
                    onDismiss = { showResumeMenu = false }
                )
            }
        }
    }
}
