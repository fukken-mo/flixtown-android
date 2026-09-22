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
import androidx.compose.ui.platform.LocalConfiguration
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
            // Real IBO geometry, decoded from activity_movie_info.xml's
            // ConstraintLayout (root is match_parent, i.e. the guidelines
            // below are percentages of the actual screen, not some taller
            // scrollable canvas): a vertical guideline at 27% of screen
            // WIDTH is the poster/text boundary; a horizontal guideline at
            // 38% of screen HEIGHT is where the poster's top AND bottom are
            // both pinned — ConstraintLayout's way of centering the poster
            // on that line. The poster itself resolves to 324x504dp at
            // IBO's own TV density bucket, where screen height is 1080dp —
            // i.e. the poster is ~46.7% of screen height. Both numbers are
            // computed here from the real runtime screen height/width
            // (LocalConfiguration), not hardcoded dp guesses, so the same
            // proportion holds regardless of this device's actual density.
            val configuration = LocalConfiguration.current
            val screenHeightDp = configuration.screenHeightDp.dp
            val posterHeight = (screenHeightDp * 0.467f).coerceIn(260.dp, 420.dp)
            val posterWidth = posterHeight * (324f / 504f)
            // Poster's vertical CENTER should land at 38% of screen height;
            // top padding is however much space that leaves above the
            // poster given its own (now percentage-derived) height.
            val heroTopPadding = (screenHeightDp * 0.38f - posterHeight / 2f).coerceAtLeast(FlixSpacing.heroTopGap)

            // Poster-beside-title hero, modeled on the actual IBO movie
            // info layout: title/metadata/genres/description column starts
            // at the same 27%-width guideline as the poster's right edge
            // and runs almost to the screen's right edge — not a narrow
            // capped column. Backdrop is one continuous image behind the
            // whole group (matchParentSize sizes it to the content's real
            // height, which now reaches down to where IBO's own guideline
            // math puts it), with a horizontal dark-from-the-left gradient
            // plus a vertical dark-toward-bottom gradient so text stays
            // readable while backdrop stays visible on the right, rather
            // than a flat near-black panel.
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
                        .padding(horizontal = FlixSpacing.safeHorizontal)
                        .padding(top = heroTopPadding, bottom = FlixSpacing.sectionGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(posterWidth)
                            .height(posterHeight)
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

            // Explicit clear gap so Cast never visually reads as part of
            // the hero — the IBO reference itself puts real distance (its
            // own Cast heading sits well below the info block, back at the
            // screen's own left edge rather than the hero's indented
            // guideline) between the two.
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
