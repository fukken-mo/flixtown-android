package com.flixtown.tv.ui.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.AppGraph
import com.flixtown.tv.data.AccountStatusStore
import com.flixtown.tv.data.ContinueWatchingEntry
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.catalog.CatalogViewModel
import com.flixtown.tv.ui.catalog.MoviesScreen
import com.flixtown.tv.ui.catalog.SeriesScreen
import com.flixtown.tv.ui.components.DEFAULT_POSTER_WIDTH
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.PosterSkeletonCard
import com.flixtown.tv.ui.details.MovieDetailsScreen
import com.flixtown.tv.ui.details.SeriesDetailsScreen
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.nav.NavSection
import com.flixtown.tv.ui.nav.sectionFor
import com.flixtown.tv.ui.search.SearchScreen
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NAV_RAIL_WIDTH = 148.dp

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

    val firstNavFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstNavFocus.requestFocus() }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }

    fun push(screen: ContentScreen) {
        backStack.add(screen)
    }

    fun popToRoot(screen: ContentScreen) {
        backStack.clear()
        backStack.add(screen)
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
            firstItemFocusRequester = firstNavFocus
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            when (val screen = current) {
                is ContentScreen.Home -> saveableStateHolder.SaveableStateProvider("home") {
                    Column {
                        HomeHeader(sectionLabel = "Home", accountStatusStore = graph.accountStatusStore)
                        HomeRows(
                            catalogState = catalogState,
                            continueWatching = graph.continueWatchingStore.getAll(),
                            onRetry = { catalogViewModel.retry() },
                            onMovieClick = { push(ContentScreen.MovieDetails(it.streamId)) },
                            onSeriesClick = { push(ContentScreen.SeriesDetails(it.seriesId)) }
                        )
                    }
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
                    streamId = screen.streamId
                )
                is ContentScreen.SeriesDetails -> SeriesDetailsScreen(
                    graph = graph,
                    catalogState = catalogState,
                    seriesId = screen.seriesId
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
            }
        }
    }
}

@Composable
private fun NavRail(
    selected: NavSection,
    onSelect: (NavSection) -> Unit,
    firstItemFocusRequester: FocusRequester
) {
    Column(
        modifier = Modifier
            .width(NAV_RAIL_WIDTH)
            .fillMaxHeight()
            .background(FtSurface)
            .padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "FLIX",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = FtTextPrimary
        )
        Text(
            text = "TOWN",
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
            color = FtAccent
        )
        Spacer(modifier = Modifier.height(28.dp))

        NavSection.entries.forEachIndexed { index, section ->
            NavRailItem(
                label = section.label,
                isSelected = section == selected,
                onClick = { onSelect(section) },
                modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
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
    val onClick: () -> Unit
)

@Composable
private fun HomeRows(
    catalogState: CatalogUiState,
    continueWatching: List<ContinueWatchingEntry>,
    onRetry: () -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit
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
                RowItem("cw-${entry.mediaType}-${entry.streamId}", entry.title, entry.posterUrl, progressLabel) {}
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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                if (continueWatchingItems.isNotEmpty()) {
                    PosterRow("Continue Watching", continueWatchingItems)
                }
                if (recentlyAdded.isNotEmpty()) {
                    PosterRow("Recently Added", recentlyAdded)
                }
                if (trending.isNotEmpty()) {
                    PosterRow("Trending", trending)
                }
                if (latestMovies.isNotEmpty()) {
                    PosterRow("Latest Movies", latestMovies)
                }
                if (latestSeries.isNotEmpty()) {
                    PosterRow("Latest Series", latestSeries)
                }
            }
        }
    }
}

@Composable
private fun HomeRowsSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize(),
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
private fun PosterRow(title: String, items: List<RowItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.padding(start = 40.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = FtTextPrimary)
        }
        val listState = rememberLazyListState()
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items, key = { it.key }) { item ->
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    subtitle = item.subtitle,
                    onClick = item.onClick,
                    modifier = Modifier.width(DEFAULT_POSTER_WIDTH)
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
    RowItem("movie-$streamId", name, posterUrl, year?.toString(), onClick = { onClick(this) })

private fun Series.toRowItem(onClick: (Series) -> Unit): RowItem =
    RowItem("series-$seriesId", name, posterUrl, year?.toString(), onClick = { onClick(this) })

@JvmName("movieOrSeriesToRowItem")
private fun Any.toRowItem(onMovieClick: (Movie) -> Unit, onSeriesClick: (Series) -> Unit): RowItem = when (this) {
    is Movie -> toRowItem(onMovieClick)
    is Series -> toRowItem(onSeriesClick)
    else -> error("Unsupported row item type")
}
