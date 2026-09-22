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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.flixtown.tv.AppGraph
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.TrailerResolver
import com.flixtown.tv.data.TrailerSource
import com.flixtown.tv.data.model.CastMember
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.MovieDetails
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.components.CastRow
import com.flixtown.tv.ui.components.DEFAULT_POSTER_WIDTH
import com.flixtown.tv.ui.components.MetadataRow
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.PrimaryActionButton
import com.flixtown.tv.ui.components.SHOW_SAFE_AREA_GUIDES
import com.flixtown.tv.ui.components.SafeAreaDebugOverlay
import com.flixtown.tv.ui.components.SecondaryActionButton
import com.flixtown.tv.ui.components.SectionHeader
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.components.reportXPosition
import com.flixtown.tv.ui.components.rememberSafeAreaMeasurements
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.player.SimpleVideoPlayerScreen
import com.flixtown.tv.ui.player.openYouTubeVideo
import com.flixtown.tv.ui.theme.FlixSpacing
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

@Composable
fun MovieDetailsScreen(
    graph: AppGraph,
    catalogState: CatalogUiState,
    streamId: Int,
    onPlay: (ContentScreen.Player) -> Unit,
    onMovieClick: (Movie) -> Unit
) {
    // Debug-only (SafeLog.d is a no-op in release): these run on every
    // recomposition of this composable, not just once per screen-open, so
    // they stay off the always-on SafeLog.e path unlike the LaunchedEffect-
    // scoped ones below (which only fire once per actual navigation).
    SafeLog.d("MovieDetailsScreen", "ENTER composition streamId=$streamId")
    val movie = (catalogState as? CatalogUiState.Loaded)?.snapshot?.movies?.firstOrNull { it.streamId == streamId }
    SafeLog.d("MovieDetailsScreen", "movie lookup result: ${if (movie == null) "NOT FOUND" else "found name=${movie.name}"}")

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
    SafeLog.d("MovieDetailsScreen", "composing body, name=${movie.name} posterUrl=${movie.posterUrl}")

    var showTrailer by remember(streamId) { mutableStateOf(false) }
    var showResumeMenu by remember(streamId) { mutableStateOf(false) }
    val context = LocalContext.current
    val trailerSource = TrailerResolver.resolve(details?.trailer)

    // Opening a title should never leave focus unset — without this the
    // first D-pad press after navigating in "finds" a focus target with no
    // visible highlight beforehand, which reads as broken on a real TV.
    val playButtonFocusRequester = remember(streamId) { FocusRequester() }
    LaunchedEffect(streamId) { playButtonFocusRequester.requestFocus() }

    // Cast never blocks the rest of the screen: details (and everything that
    // depends on it) render immediately, this just fills in once TMDB (or,
    // failing that, Xtream's plain names) resolves in the background.
    var castMembers by remember(streamId) { mutableStateOf<List<CastMember>?>(null) }
    LaunchedEffect(details) {
        val loadedDetails = details ?: return@LaunchedEffect
        val tmdbCast = graph.tmdbRepository.getMovieCast(loadedDetails.tmdbId, movie.name, movie.year)
        castMembers = tmdbCast.ifEmpty {
            loadedDetails.cast.mapIndexed { index, name ->
                CastMember(id = -(index + 1), name = name, character = null, profilePath = null)
            }
        }
    }

    val existingProgress = remember(streamId) {
        graph.continueWatchingStore.getAll().firstOrNull { it.streamId == movie.streamId && it.mediaType == "movie" }
    }

    val similarMovies = remember(streamId, catalogState) {
        (catalogState as? CatalogUiState.Loaded)?.snapshot?.movies
            ?.filter { it.streamId != movie.streamId && it.categoryId != null && it.categoryId == movie.categoryId }
            ?.take(15)
            .orEmpty()
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

    val safeAreaMeasurements = rememberSafeAreaMeasurements()

    Box(modifier = Modifier.fillMaxSize().background(FtBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Breathing room above the hero block so it never sits flush
            // against the screen's top edge — static, not focus-dependent.
            Spacer(modifier = Modifier.height(FlixSpacing.heroTopGap))

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
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 620.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = movie.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = FtTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (SHOW_SAFE_AREA_GUIDES) Modifier.reportXPosition("title", safeAreaMeasurements) else Modifier
                        )

                        MetadataRow(
                            parts = listOfNotNull(movie.year?.toString(), details?.runtimeMinutes?.let { "${it}m" }),
                            rating = movie.rating,
                            modifier = if (SHOW_SAFE_AREA_GUIDES) Modifier.reportXPosition("metadata", safeAreaMeasurements) else Modifier
                        )

                        if (!details?.genres.isNullOrEmpty()) {
                            Text(
                                text = details!!.genres.joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FtTextSecondary,
                                modifier = if (SHOW_SAFE_AREA_GUIDES) Modifier.reportXPosition("genres", safeAreaMeasurements) else Modifier
                            )
                        }

                        Text(
                            text = details?.plot ?: "No description available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FtTextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (SHOW_SAFE_AREA_GUIDES) Modifier.reportXPosition("description", safeAreaMeasurements) else Modifier
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = if (SHOW_SAFE_AREA_GUIDES) Modifier.reportXPosition("buttons", safeAreaMeasurements) else Modifier
                        ) {
                            PrimaryActionButton(
                                text = if (existingProgress != null) "Resume" else "Play",
                                icon = Icons.Filled.PlayArrow,
                                onClick = {
                                    if (existingProgress != null) showResumeMenu = true else launchPlayer(0L)
                                },
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

            if (!details?.cast.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FlixSpacing.safeHorizontal)
                        .let { if (SHOW_SAFE_AREA_GUIDES) it.reportXPosition("cast_heading", safeAreaMeasurements) else it }
                ) {
                    CastRow(cast = castMembers)
                }
                Spacer(modifier = Modifier.height(FlixSpacing.sectionGap))
            }

            if (similarMovies.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(FlixSpacing.rowHeaderGap)) {
                    SectionHeader(
                        title = "More Like This",
                        modifier = Modifier
                            .padding(start = FlixSpacing.safeHorizontal)
                            .let { if (SHOW_SAFE_AREA_GUIDES) it.reportXPosition("more_like_this", safeAreaMeasurements) else it }
                    )
                    LazyRow(
                        contentPadding = PaddingValues(
                            start = FlixSpacing.safeHorizontal,
                            end = FlixSpacing.safeHorizontal,
                            top = FlixSpacing.focusReserveTop,
                            bottom = FlixSpacing.focusReserveBottom
                        ),
                        horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap)
                    ) {
                        items(similarMovies, key = { it.streamId }) { similar ->
                            PosterCard(
                                title = similar.name,
                                posterUrl = similar.posterUrl,
                                subtitle = similar.year?.toString(),
                                onClick = { onMovieClick(similar) },
                                modifier = Modifier.width(DEFAULT_POSTER_WIDTH)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FlixSpacing.safeVertical))
        }

        if (SHOW_SAFE_AREA_GUIDES) {
            SafeAreaDebugOverlay(safeHorizontal = FlixSpacing.safeHorizontal, measurements = safeAreaMeasurements)
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
