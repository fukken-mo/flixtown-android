package com.flixtown.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.AppGraph
import com.flixtown.tv.R
import com.flixtown.tv.data.AccountStatusStore
import com.flixtown.tv.data.ContinueWatchingEntry
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.catalog.CatalogViewModel
import com.flixtown.tv.ui.catalog.MoviesScreen
import com.flixtown.tv.ui.catalog.SeriesScreen
import com.flixtown.tv.ui.components.BackdropLayer
import com.flixtown.tv.ui.components.DEFAULT_POSTER_WIDTH
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.PosterSkeletonCard
import com.flixtown.tv.ui.details.MovieDetailsScreen
import com.flixtown.tv.ui.details.SeriesDetailsScreen
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.nav.LocalRailRevealFocusRequester
import com.flixtown.tv.ui.nav.NavSection
import com.flixtown.tv.ui.nav.sectionFor
import com.flixtown.tv.ui.player.PlayerScreen
import com.flixtown.tv.ui.search.SearchScreen
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val NAV_RAIL_WIDTH = 148.dp
private val NAV_RAIL_COLLAPSED_WIDTH = 0.dp

/**
 * The authenticated app shell: a slim original left nav rail plus a
 * hand-rolled screen stack (see [ContentScreen]) for Home/Movies/Series/
 * details/Search.
 *
 * Each top-level screen (Home/Movies/Series/Search) is wrapped in
 * [rememberSaveableStateHolder]'s `SaveableStateProvider`, which is the
 * standard Compose mechanism for exactly this situation — a composable
 * leaving composition (nav stack changes) and later re-entering — so scroll
 * position, selected category/sort, and search text all restore correctly
 * without any hand-rolled state cache.
 *
 * The catalog is loaded once here via [CatalogViewModel] (scoped to the
 * Activity, so it isn't re-fetched on every screen change) and shared down
 * to every screen that needs it.
 */
@Composable
fun HomeShellScreen(graph: AppGraph) {
    val catalogViewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory(graph.catalogRepository))
    val catalogState by catalogViewModel.state.collectAsState()

    val backStack = remember { mutableStateListOf<ContentScreen>(ContentScreen.Home) }
    val saveableStateHolder = rememberSaveableStateHolder()
    val current = backStack.last()
    val coroutineScope = rememberCoroutineScope()

    val firstNavFocus = remember { FocusRequester() }
    val railRevealFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstNavFocus.requestFocus() }

    // The rail collapses whenever focus leaves it (the user is browsing
    // content) and expands the moment it — or one of its items via
    // railRevealFocusRequester — regains focus. Driven purely by focus state
    // so it works the same on Home's rows as on the Movies/Series grids.
    var railHasFocus by remember { mutableStateOf(true) }
    val railWidth by animateDpAsState(
        targetValue = if (railHasFocus) NAV_RAIL_WIDTH else NAV_RAIL_COLLAPSED_WIDTH,
        animationSpec = tween(durationMillis = 220),
        label = "railWidth"
    )

    BackHandler(enabled = backStack.size > 1 && current !is ContentScreen.Player) {
        backStack.removeAt(backStack.lastIndex)
    }

    fun push(screen: ContentScreen) {
        backStack.add(screen)
    }

    fun popToRoot(screen: ContentScreen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun exitPlayer() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun playContinueWatching(entry: ContinueWatchingEntry) {
        val snapshot = (catalogState as? CatalogUiState.Loaded)?.snapshot ?: return
        if (entry.mediaType == "movie") {
            val movie = snapshot.movies.firstOrNull { it.streamId == entry.streamId } ?: return
            val url = graph.catalogRepository.buildMovieStreamUrl(movie) ?: return
            push(
                ContentScreen.Player(
                    contentId = movie.streamId,
                    mediaType = "movie",
                    title = movie.name,
                    posterUrl = movie.posterUrl,
                    streamUrl = url,
                    resumePositionMs = entry.positionMs
                )
            )
        } else {
            val series = snapshot.series.firstOrNull { it.seriesId == entry.seriesId } ?: return
            coroutineScope.launch {
                val details = graph.catalogRepository.getSeriesDetails(series)
                val episode = details?.seasons?.firstOrNull { it.seasonNumber == entry.season }
                    ?.episodes?.firstOrNull { it.episodeNumber == entry.episode } ?: return@launch
                val url = graph.catalogRepository.buildEpisodeStreamUrl(episode) ?: return@launch
                push(
                    ContentScreen.Player(
                        contentId = episode.id.toIntOrNull() ?: entry.streamId,
                        mediaType = "episode",
                        title = entry.title,
                        posterUrl = entry.posterUrl,
                        streamUrl = url,
                        seriesId = series.seriesId,
                        season = entry.season,
                        episodeNumber = entry.episode,
                        resumePositionMs = entry.positionMs
                    )
                )
            }
        }
    }

    if (current is ContentScreen.Player) {
        PlayerScreen(graph = graph, screen = current, onExit = { exitPlayer() })
        return
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FtBackground)
    ) {
        NavRail(
            selected = sectionFor(current),
            onSelect = { section ->
                when (section) {
                    NavSection.Home -> popToRoot(ContentScreen.Home)
                    NavSection.Movies -> popToRoot(ContentScreen.Movies())
                    NavSection.Series -> popToRoot(ContentScreen.SeriesBrowse())
                    NavSection.Search -> popToRoot(ContentScreen.Search)
                    NavSection.Settings -> popToRoot(ContentScreen.Settings)
                }
            },
            firstItemFocusRequester = firstNavFocus,
            revealFocusRequester = railRevealFocusRequester,
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
                .onFocusChanged { state -> railHasFocus = state.hasFocus }
        )

        CompositionLocalProvider(LocalRailRevealFocusRequester provides railRevealFocusRequester) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when (val screen = current) {
                    is ContentScreen.Home -> saveableStateHolder.SaveableStateProvider("home") {
                        HomeContent(
                            catalogState = catalogState,
                            continueWatching = graph.continueWatchingStore.getAll(),
                            accountStatusStore = graph.accountStatusStore,
                            onRetry = { catalogViewModel.retry() },
                            onMovieClick = { push(ContentScreen.MovieDetails(it.streamId)) },
                            onSeriesClick = { push(ContentScreen.SeriesDetails(it.seriesId)) },
                            onContinueWatchingClick = { playContinueWatching(it) }
                        )
                    }
                    is ContentScreen.Movies -> saveableStateHolder.SaveableStateProvider("movies") {
                        MoviesScreen(
                            catalogState = catalogState,
                            initialCategoryId = screen.categoryId,
                            onMovieClick = { push(ContentScreen.MovieDetails(it.streamId)) },
                            onSearchClick = { push(ContentScreen.Search) },
                            onRetry = { catalogViewModel.retry() }
                        )
                    }
                    is ContentScreen.SeriesBrowse -> saveableStateHolder.SaveableStateProvider("series") {
                        SeriesScreen(
                            catalogState = catalogState,
                            initialCategoryId = screen.categoryId,
                            onSeriesClick = { push(ContentScreen.SeriesDetails(it.seriesId)) },
                            onSearchClick = { push(ContentScreen.Search) },
                            onRetry = { catalogViewModel.retry() }
                        )
                    }
                    is ContentScreen.MovieDetails -> MovieDetailsScreen(
                        graph = graph,
                        catalogState = catalogState,
                        streamId = screen.streamId,
                        onPlay = { push(it) }
                    )
                    is ContentScreen.SeriesDetails -> SeriesDetailsScreen(
                        graph = graph,
                        catalogState = catalogState,
                        seriesId = screen.seriesId,
                        onPlay = { push(it) }
                    )
                    is ContentScreen.Search -> saveableStateHolder.SaveableStateProvider("search") {
                        SearchScreen(
                            catalogState = catalogState,
                            onMovieClick = { push(ContentScreen.MovieDetails(it.streamId)) },
                            onSeriesClick = { push(ContentScreen.SeriesDetails(it.seriesId)) }
                        )
                    }
                    is ContentScreen.Settings -> Box(
                        modifier = Modifier.fillMaxSize().padding(40.dp)
                    ) {
                        Text(
                            text = "Settings is coming in the next milestone.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = FtTextSecondary
                        )
                    }
                    is ContentScreen.Player -> Unit // handled by the fullscreen branch above
                }
            }
        }
    }
}

@Composable
private fun NavRail(
    selected: NavSection,
    onSelect: (NavSection) -> Unit,
    firstItemFocusRequester: FocusRequester,
    revealFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(FtSurface)
            .padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.flixtown_logo),
            contentDescription = "Flix Town",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(40.dp)
        )
        Spacer(modifier = Modifier.height(28.dp))

        NavSection.entries.forEachIndexed { index, section ->
            val isSelected = section == selected
            var itemModifier: Modifier = Modifier
            if (index == 0) itemModifier = itemModifier.focusRequester(firstItemFocusRequester)
            if (isSelected) itemModifier = itemModifier.focusRequester(revealFocusRequester)
            NavRailItem(
                label = section.label,
                isSelected = isSelected,
                onClick = { onSelect(section) },
                modifier = itemModifier
            )
        }
    }
}

@Composable
private fun NavRailItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlixFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) FtAccent else FtTextPrimary
        )
    }
}

@Composable
private fun HomeHeader(sectionLabel: String, accountStatusStore: AccountStatusStore) {
    val expiresAtEpochSeconds by accountStatusStore.expiresAtEpochSeconds.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = sectionLabel, style = MaterialTheme.typography.headlineMedium)
        val expires = expiresAtEpochSeconds
        if (expires != null) {
            val formatted = remember(expires) {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(expires * 1000))
            }
            Text(
                text = "Subscription active until $formatted",
                style = MaterialTheme.typography.bodyMedium,
                color = FtTextSecondary
            )
        }
    }
}

// ---- Home content rows -----------------------------------------------------

private data class RowItem(
    val key: String,
    val title: String,
    val posterUrl: String?,
    val subtitle: String?,
    val backdropUrl: String?,
    val onClick: () -> Unit
)

/**
 * Home's outer shell: a dynamic backdrop behind everything (whatever the
 * currently focused row item resolves to — see [RowItem.backdropUrl]), the
 * header, a small fixed-height hero line so text appearing/disappearing
 * never shifts the rows below it, then the scrollable rows themselves.
 */
@Composable
private fun HomeContent(
    catalogState: CatalogUiState,
    continueWatching: List<ContinueWatchingEntry>,
    accountStatusStore: AccountStatusStore,
    onRetry: () -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onContinueWatchingClick: (ContinueWatchingEntry) -> Unit
) {
    // A State object, not `by` — HomeContent itself never unwraps `.value`,
    // so a focus-change write (from deep inside the rows below) never
    // recomposes this function/the rows. Only the two leaf composables that
    // actually read `.value` (hero text instantly, backdrop debounced) do.
    val focused = remember { mutableStateOf<RowItem?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        BackdropLayer(state = remember { derivedStateOf { focused.value?.backdropUrl } })

        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(sectionLabel = "Home", accountStatusStore = accountStatusStore)
            HeroLayer(state = focused)
            HomeRows(
                catalogState = catalogState,
                continueWatching = continueWatching,
                onRetry = onRetry,
                onMovieClick = onMovieClick,
                onSeriesClick = onSeriesClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onFocusedItemChange = { focused.value = it }
            )
        }
    }
}

@Composable
private fun HeroLayer(state: State<RowItem?>) {
    FocusedHero(item = state.value)
}

@Composable
private fun FocusedHero(item: RowItem?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (item != null) {
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = FtTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.subtitle.isNullOrBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FtTextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeRows(
    catalogState: CatalogUiState,
    continueWatching: List<ContinueWatchingEntry>,
    onRetry: () -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onContinueWatchingClick: (ContinueWatchingEntry) -> Unit,
    onFocusedItemChange: (RowItem) -> Unit
) {
    when (catalogState) {
        is CatalogUiState.Loading -> HomeRowsSkeleton()
        is CatalogUiState.Error -> Box(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Couldn't load your catalog: ${catalogState.message}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
                FlixFocusSurface(onClick = onRetry) { Text("Retry") }
            }
        }
        is CatalogUiState.Loaded -> {
            val snapshot = catalogState.snapshot

            val continueWatchingItems = continueWatching.map { entry ->
                val progressLabel = if (entry.durationMs > 0) {
                    val percent = (entry.positionMs * 100 / entry.durationMs).coerceIn(0, 100)
                    "$percent% watched"
                } else null
                RowItem("cw-${entry.mediaType}-${entry.streamId}", entry.title, entry.posterUrl, progressLabel, entry.posterUrl) {
                    onContinueWatchingClick(entry)
                }
            }

            val recentlyAdded = (snapshot.movies.map { it to it.addedEpochSeconds } + snapshot.series.map { it to it.addedEpochSeconds })
                .sortedByDescending { it.second }
                .take(ROW_ITEM_LIMIT)
                .map { (item, _) -> item.toRowItem(onMovieClick, onSeriesClick) }

            // Deterministic trending: rating (60%) + recency over a 90-day
            // falloff (40%). Not TMDB-backed this pass — see project notes.
            val trending = (snapshot.movies.map { it to trendingScore(it.rating, it.addedEpochSeconds) } +
                snapshot.series.map { it to trendingScore(it.rating, it.addedEpochSeconds) })
                .sortedByDescending { it.second }
                .take(ROW_ITEM_LIMIT)
                .map { (item, _) -> item.toRowItem(onMovieClick, onSeriesClick) }

            val latestMovies = snapshot.movies
                .sortedByDescending { it.addedEpochSeconds }
                .take(ROW_ITEM_LIMIT)
                .map { it.toRowItem(onMovieClick) }

            val latestSeries = snapshot.series
                .sortedByDescending { it.addedEpochSeconds }
                .take(ROW_ITEM_LIMIT)
                .map { it.toRowItem(onSeriesClick) }

            // A LazyColumn: every row is measured with an unbounded main-axis
            // constraint (that's how lazy layouts work), so a poster row can
            // never be squeezed by "not enough remaining height" the way a
            // plain fillMaxSize() Column would squeeze it. If everything
            // doesn't fit on screen at once, it scrolls instead of shrinking.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                if (continueWatchingItems.isNotEmpty()) {
                    item(key = "row-continue-watching") {
                        PosterRow("Continue Watching", continueWatchingItems, onFocusedItemChange)
                    }
                }
                if (recentlyAdded.isNotEmpty()) {
                    item(key = "row-recently-added") {
                        PosterRow("Recently Added", recentlyAdded, onFocusedItemChange)
                    }
                }
                if (trending.isNotEmpty()) {
                    item(key = "row-trending") {
                        PosterRow("Trending", trending, onFocusedItemChange)
                    }
                }
                if (latestMovies.isNotEmpty()) {
                    item(key = "row-latest-movies") {
                        PosterRow("Latest Movies", latestMovies, onFocusedItemChange)
                    }
                }
                if (latestSeries.isNotEmpty()) {
                    item(key = "row-latest-series") {
                        PosterRow("Latest Series", latestSeries, onFocusedItemChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRowsSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.padding(start = 40.dp)) {
                    Text(text = "Loading…", style = MaterialTheme.typography.titleMedium, color = FtTextSecondary)
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(6) { PosterSkeletonCard() }
                }
            }
        }
    }
}

@Composable
private fun PosterRow(title: String, items: List<RowItem>, onFocusedItemChange: (RowItem) -> Unit) {
    val railFocusRequester = LocalRailRevealFocusRequester.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.padding(start = 40.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = FtTextPrimary)
        }
        val listState = rememberLazyListState()
        LazyRow(
            state = listState,
            // Vertical padding isn't cosmetic here: LazyRow clips its content
            // to its own bounds, and with zero vertical margin a focused
            // card's scale-up had nowhere to go but into that clip edge —
            // this is the "focused poster gets cut off" bug.
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    subtitle = item.subtitle,
                    onClick = item.onClick,
                    modifier = Modifier
                        .width(DEFAULT_POSTER_WIDTH)
                        .onFocusChanged { state -> if (state.isFocused) onFocusedItemChange(item) }
                        .let { m ->
                            if (index == 0 && railFocusRequester != null) {
                                m.focusProperties { left = railFocusRequester }
                            } else {
                                m
                            }
                        }
                )
            }
        }
    }
}

private const val ROW_ITEM_LIMIT = 15

private fun trendingScore(rating: Double?, addedEpochSeconds: Long): Double {
    val normalizedRating = ((rating ?: 0.0) / 10.0).coerceIn(0.0, 1.0)
    val ageDays = ((System.currentTimeMillis() / 1000) - addedEpochSeconds).coerceAtLeast(0) / 86400.0
    val recencyScore = (1.0 - (ageDays / 90.0)).coerceIn(0.0, 1.0)
    return normalizedRating * 0.6 + recencyScore * 0.4
}

private fun Movie.toRowItem(onClick: (Movie) -> Unit): RowItem =
    RowItem("movie-$streamId", name, posterUrl, year?.toString(), posterUrl, onClick = { onClick(this) })

private fun Series.toRowItem(onClick: (Series) -> Unit): RowItem =
    RowItem("series-$seriesId", name, posterUrl, year?.toString(), backdropUrl ?: posterUrl, onClick = { onClick(this) })

@JvmName("movieOrSeriesToRowItem")
private fun Any.toRowItem(onMovieClick: (Movie) -> Unit, onSeriesClick: (Series) -> Unit): RowItem = when (this) {
    is Movie -> toRowItem(onMovieClick)
    is Series -> toRowItem(onSeriesClick)
    else -> error("Unsupported row item type")
}
