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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import com.flixtown.tv.ui.components.parseRawEpisodeTitle
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
    // Debug-only (SafeLog.d is a no-op in release): these run on every
    // recomposition of this composable, not just once per screen-open, so
    // they stay off the always-on SafeLog.e path unlike the LaunchedEffect-
    // scoped ones below (which only fire once per actual navigation).
    SafeLog.d("SeriesDetailsScreen", "ENTER composition seriesId=$seriesId")
    val series = (catalogState as? CatalogUiState.Loaded)?.snapshot?.series?.firstOrNull { it.seriesId == seriesId }
    SafeLog.d("SeriesDetailsScreen", "series lookup result: ${if (series == null) "NOT FOUND" else "found name=${series.name}"}")

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
    SafeLog.d("SeriesDetailsScreen", "composing body, name=${series.name} posterUrl=${series.posterUrl}")

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

    // NOT keyed to seriesId — remember() is positional, so without an
    // explicit reset below the same ScrollState instance (and its scroll
    // offset) would otherwise carry over from whatever title was open
    // previously.
    val scrollState = rememberScrollState()

    // Same rule as Movie details: never leave focus unset when a title opens.
    val playButtonFocusRequester = remember(seriesId) { FocusRequester() }
    LaunchedEffect(seriesId) {
        SafeLog.d("SeriesDetailsScreen", "scroll debug: value at open (pre-reset)=${scrollState.value} maxValue=${scrollState.maxValue}")
        scrollState.scrollTo(0)
        SafeLog.d("SeriesDetailsScreen", "scroll debug: value before Play focus=${scrollState.value}")
        playButtonFocusRequester.requestFocus()
        SafeLog.d("SeriesDetailsScreen", "scroll debug: value immediately after requestFocus()=${scrollState.value}")
        withFrameNanos { }
        SafeLog.d("SeriesDetailsScreen", "scroll debug: value after next frame=${scrollState.value} maxValue=${scrollState.maxValue}")
        // Compose's default focus behavior scrolls an ancestor
        // verticalScroll to bring a newly focused descendant into view.
        // Play sits below the poster/title/metadata/genres/description
        // inside this same scrollable Column, so that auto-scroll was
        // dragging the whole hero — including the 120dp top spacer and
        // the title — out of view every time Details opened. Counter it
        // for a short settle window right after requesting focus by
        // forcing the scroll position back to 0 on each frame; Play stays
        // logically focused throughout even while its bring-into-view
        // target is being overridden.
        repeat(14) {
            withFrameNanos { }
            if (scrollState.value != 0) scrollState.scrollTo(0)
        }
        SafeLog.d("SeriesDetailsScreen", "scroll debug: final settled value=${scrollState.value}")
    }

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
                .verticalScroll(scrollState)
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
                val backdropUrl = series.backdropUrl ?: series.posterUrl
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
                        if (!series.posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = series.posterUrl,
                                contentDescription = series.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = series.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = FtTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        MetadataRow(
                            parts = listOfNotNull(series.year?.toString()),
                            rating = series.rating
                        )

                        if (series.genres.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = series.genres.joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FtTextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = series.plot ?: "No description available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FtTextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryActionButton(
                                text = if (inProgress != null) "Resume" else "Play",
                                icon = Icons.Filled.PlayArrow,
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

            // Wider fixed gap between hero and Cast (80dp, up from 32dp) so
            // the poster/title/metadata/description/buttons group gets real
            // breathing room and doesn't read as squeezed against Cast —
            // this is a separate, larger value from FlixSpacing.sectionGap,
            // which every other inter-section gap on this screen still uses.
            Spacer(modifier = Modifier.height(80.dp))

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

                // Explicit focus routing between the season row and the
                // episode list below it, rather than trusting default 2D
                // spatial focus search (which was landing DOWN-from-a-season
                // back inside the season row instead of into episodes). One
                // stable FocusRequester per season chip, plus one requester
                // for "the first episode currently on screen" that every
                // chip's DOWN points at — it's re-created (via `remember`
                // keyed on the season) whenever the episode list itself
                // changes, since that's a genuinely different composable
                // instance each time the selection changes.
                val seasonFocusRequesters = remember(seasons) {
                    seasons.associate { it.seasonNumber to FocusRequester() }
                }
                val firstEpisodeFocusRequester = remember(selectedSeasonNumber) { FocusRequester() }
                val selectedSeasonFocusRequester = seasonFocusRequesters[selectedSeasonNumber]

                LazyRow(
                    contentPadding = PaddingValues(
                        start = FlixSpacing.safeHorizontal,
                        end = FlixSpacing.safeHorizontal,
                        top = FlixSpacing.focusReserveTop,
                        bottom = FlixSpacing.focusReserveBottom
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(seasons, key = { it.seasonNumber }) { season ->
                        FilterChip(
                            label = season.name,
                            isSelected = season.seasonNumber == selectedSeasonNumber,
                            onClick = { selectedSeasonNumber = season.seasonNumber },
                            modifier = Modifier
                                .focusRequester(seasonFocusRequesters.getValue(season.seasonNumber))
                                .focusProperties { down = firstEpisodeFocusRequester }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(FlixSpacing.rowHeaderGap))

                val episodes = seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }?.episodes.orEmpty()
                Column(
                    // Not a Lazy container, but this whole screen is a single
                    // verticalScroll Column — a focused episode card's
                    // scale+lift growth still needs somewhere to go that
                    // doesn't clip against a neighbor or trigger a scroll
                    // correction, same reasoning as every Lazy row/grid.
                    modifier = Modifier.padding(
                        horizontal = FlixSpacing.safeHorizontal,
                        vertical = FlixSpacing.focusReserveTop
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    episodes.forEachIndexed { index, episode ->
                        val progress = remember(episode.id, selectedSeasonNumber) {
                            graph.continueWatchingStore.getAll().firstOrNull {
                                it.mediaType == "episode" && it.seriesId == series.seriesId &&
                                    it.episode == episode.episodeNumber && it.season == selectedSeasonNumber
                            }
                        }
                        val progressFraction = progress?.let {
                            if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs.toFloat()).coerceIn(0f, 1f) else null
                        }
                        EpisodeCard(
                            episode = episode,
                            seasonNumber = selectedSeasonNumber,
                            progressFraction = progressFraction,
                            onClick = { onEpisodeClick(episode) },
                            modifier = Modifier
                                // Vertical list: LEFT/RIGHT has nothing to do
                                // here and must never fall through to default
                                // spatial search, which could otherwise jump
                                // back into the season row above.
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                    if (index == 0) {
                                        selectedSeasonFocusRequester?.let { up = it }
                                    }
                                }
                                .let { m -> if (index == 0) m.focusRequester(firstEpisodeFocusRequester) else m }
                        )
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
                        contentPadding = PaddingValues(
                            start = FlixSpacing.safeHorizontal,
                            end = FlixSpacing.safeHorizontal,
                            top = FlixSpacing.focusReserveTop,
                            bottom = FlixSpacing.focusReserveBottom
                        ),
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
private fun EpisodeCard(
    episode: Episode,
    seasonNumber: Int?,
    progressFraction: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The Xtream API gives one raw title string per episode (no separate
    // structured "clean name" field), so a fallback parse is the only
    // option here — but season/episode numbers ARE already structured data
    // (separate JSON fields), so the "S03 E01" part never needs parsing.
    val cleanTitle = remember(episode.title) { parseRawEpisodeTitle(episode.title).episodeTitle ?: episode.title }
    FlixFocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
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
                val seasonEpisodeCode = seasonNumber?.let { "S%02d E%02d".format(it, episode.episodeNumber) }
                    ?: "Episode ${episode.episodeNumber}"
                val header = listOfNotNull(seasonEpisodeCode, episode.runtimeMinutes?.let { "${it}m" })
                Text(text = header.joinToString(" • "), style = MaterialTheme.typography.labelMedium, color = FtTextSecondary)
                Text(text = cleanTitle, style = MaterialTheme.typography.titleMedium, color = FtTextPrimary)
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
