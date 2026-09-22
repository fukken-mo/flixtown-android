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
import com.flixtown.tv.ui.components.SecondaryActionButton
import com.flixtown.tv.ui.components.SectionHeader
import com.flixtown.tv.ui.components.SelectorMenu
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

    Box(modifier = Modifier.fillMaxSize().background(FtBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Fixed, deterministic vertical layout — no percentage-of-
            // screen-height math. Three real-device test rounds showed
            // that reproducing IBO's guideline percentages (which are
            // relative to IBO's own ConstraintLayout coordinate system)
            // did not land the hero in the right place in this Compose
            // layout on the actual TV, so the real screenshot is now the
            // source of truth instead: a fixed 120dp top margin, a fixed
            // poster size, and a fixed gap before Cast.
            Spacer(modifier = Modifier.height(120.dp))

            // Poster-beside-title hero: title/metadata/genres/description
            // column sits beside the poster, vertically centered against
            // it as one group. Backdrop is one continuous image behind the
            // whole group (matchParentSize sizes it to the Row's own
            // height), with a horizontal dark-from-the-left gradient plus
            // a vertical dark-toward-bottom gradient so text stays readable
            // while backdrop stays visible on the right, rather than a
            // flat near-black panel.
            Box(modifier = Modifier.fillMaxWidth()) {
                val backdropUrl = details?.backdropUrl ?: movie.posterUrl
                if (!backdropUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    Box(modifier = Modifier.matchParentSize().background(FtSurfaceElevated))
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to FtBackground.copy(alpha = 0.92f),
                                0.55f to FtBackground.copy(alpha = 0.55f),
                                1f to Color.Transparent
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, FtBackground)))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FlixSpacing.safeHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(240.dp)
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

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = movie.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = FtTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        MetadataRow(
                            parts = listOfNotNull(movie.year?.toString(), details?.runtimeMinutes?.let { "${it}m" }),
                            rating = movie.rating
                        )

                        if (!details?.genres.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = details!!.genres.joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FtTextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = details?.plot ?: "No description available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FtTextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

            // Fixed 32dp gap between hero and Cast — enough to read as a
            // new section without leaving a large blank area.
            Spacer(modifier = Modifier.height(FlixSpacing.sectionGap))

            if (!details?.cast.isNullOrEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = FlixSpacing.safeHorizontal)) {
                    CastRow(cast = castMembers)
                }
                Spacer(modifier = Modifier.height(FlixSpacing.sectionGap))
            }

            if (similarMovies.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(FlixSpacing.rowHeaderGap)) {
                    SectionHeader(title = "More Like This", modifier = Modifier.padding(start = FlixSpacing.safeHorizontal))
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
