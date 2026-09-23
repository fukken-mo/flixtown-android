package com.flixtown.tv.ui.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.AppGraph
import com.flixtown.tv.R
import com.flixtown.tv.core.CrashReporter
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.AccountStatusStore
import com.flixtown.tv.data.ContinueWatchingEntry
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.catalog.CatalogViewModel
import com.flixtown.tv.ui.catalog.MoviesScreen
import com.flixtown.tv.ui.catalog.SeriesScreen
import com.flixtown.tv.ui.components.BackdropLayer
import com.flixtown.tv.ui.components.ContinueWatchingCard
import com.flixtown.tv.ui.components.DEFAULT_POSTER_WIDTH
import com.flixtown.tv.ui.components.ExitConfirmationDialog
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.PosterSkeletonCard
import com.flixtown.tv.ui.components.PrimaryActionButton
import com.flixtown.tv.ui.components.SecondaryActionButton
import com.flixtown.tv.ui.components.SectionHeader
import com.flixtown.tv.ui.components.SettingsToggleRow
import com.flixtown.tv.ui.components.continueWatchingDisplay
import com.flixtown.tv.ui.details.MovieDetailsScreen
import com.flixtown.tv.ui.details.SeriesDetailsScreen
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.nav.LocalRailRevealFocusRequester
import com.flixtown.tv.ui.nav.NavSection
import com.flixtown.tv.ui.nav.sectionFor
import com.flixtown.tv.ui.player.PlayerScreen
import com.flixtown.tv.ui.search.SearchScreen
import com.flixtown.tv.ui.theme.FlixMotion
import com.flixtown.tv.ui.theme.FlixSpacing
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextMuted
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val NAV_RAIL_WIDTH = 148.dp
private val NAV_RAIL_COLLAPSED_WIDTH = 0.dp

/** Every screen reachable directly from the nav rail via popToRoot. */
private fun ContentScreen.isRailTopLevel(): Boolean = this is ContentScreen.Home ||
    this is ContentScreen.Movies ||
    this is ContentScreen.SeriesBrowse ||
    this is ContentScreen.Search ||
    this is ContentScreen.Settings

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
    // The rail's width is a Row sibling of the content pane (Movies/Series/
    // Home/etc, which fills the remaining weight(1f) space) — every frame
    // this animates, that content pane's available width changes too,
    // forcing a real re-measure/re-layout of whatever's on screen (a
    // LazyVerticalGrid grid in particular) for the full duration, not just
    // a repaint. Shortened from 220ms so that concurrent relayout window is
    // roughly half as long on every rail<->content focus change; a full fix
    // (decoupling the rail's collapse from the content pane's measured
    // width entirely) would need a larger nav-shell restructuring than this
    // pass's targeted audit scope.
    val railWidth by animateDpAsState(
        targetValue = if (railHasFocus) NAV_RAIL_WIDTH else NAV_RAIL_COLLAPSED_WIDTH,
        animationSpec = tween(durationMillis = 120),
        label = "railWidth"
    )

    // Three mutually exclusive levels, so exactly one BackHandler is ever
    // enabled for a given BACK press (never a "which one fires first"
    // ambiguity, and never more than one popping/navigating per press):
    //   1. Truly at the app root (Home, nothing else on the stack) -> ask
    //      before exiting, instead of silently falling through to the
    //      system default (which finishes the Activity with no warning).
    //   2. Stack has more than one entry -> pop exactly one level, same as
    //      before.
    //   3. Stack has exactly one entry but it's NOT Home (Movies/Series/
    //      Search/Settings reached directly via the nav rail, which always
    //      replaces the whole stack via popToRoot) -> there is no "previous
    //      screen" to return to, so BACK goes to Home rather than exiting
    //      the app from what the user doesn't perceive as the root.
    // All three are disabled while the exit dialog itself is showing, so
    // its own BackHandler (registered only while it's composed) is the
    // only one that can act on that BACK press.
    var showExitDialog by remember { mutableStateOf(false) }
    val isAtAppRoot = current is ContentScreen.Home && backStack.size == 1

    BackHandler(enabled = isAtAppRoot && !showExitDialog) {
        showExitDialog = true
    }
    BackHandler(enabled = backStack.size > 1 && current !is ContentScreen.Player && !showExitDialog) {
        backStack.removeAt(backStack.lastIndex)
    }
    BackHandler(enabled = backStack.size == 1 && current !is ContentScreen.Home && current !is ContentScreen.Player && !showExitDialog) {
        backStack.clear()
        backStack.add(ContentScreen.Home)
    }

    fun push(screen: ContentScreen) {
        SafeLog.e("Navigation", "push -> $screen")
        CrashReporter.lastRoute = screen.javaClass.simpleName
        when (screen) {
            is ContentScreen.MovieDetails -> {
                CrashReporter.lastStreamId = screen.streamId.toString()
                CrashReporter.lastContentType = "movie"
            }
            is ContentScreen.SeriesDetails -> {
                CrashReporter.lastStreamId = screen.seriesId.toString()
                CrashReporter.lastContentType = "series"
            }
            is ContentScreen.Player -> {
                CrashReporter.lastStreamId = screen.contentId.toString()
                CrashReporter.lastContentType = screen.mediaType
            }
            else -> Unit
        }
        backStack.add(screen)
    }

    fun popToRoot(screen: ContentScreen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun exitPlayer() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // Autoplay's "next episode" transition swaps the top of the stack
    // in-place (no intervening non-Player screen), unlike every other path
    // into the player, which always pops back to a non-Player screen first.
    // The `key(current.contentId)` wrapper below is what actually forces a
    // full teardown/rebuild of PlayerScreen's composition (fresh ExoPlayer,
    // fresh position/duration state, etc.) across that in-place swap.
    fun playNextEpisode(next: ContentScreen.Player) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        push(next)
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
                        resumePositionMs = entry.positionMs,
                        seriesName = entry.seriesName ?: series.name,
                        episodeTitle = entry.episodeTitle ?: episode.title
                    )
                )
            }
        }
    }

    if (current is ContentScreen.Player) {
        key(current.contentId) {
            PlayerScreen(
                graph = graph,
                screen = current,
                onExit = { exitPlayer() },
                onNextEpisode = { playNextEpisode(it) }
            )
        }
        return
    }

    val activity = LocalContext.current as? Activity

    Box(modifier = Modifier.fillMaxSize()) {
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
            // A plain, minimal fade between screens — no scale, no slide, no
            // spring. IBO-style responsiveness matters more here than a
            // decorative transition; sizeTransform is left at its default
            // since every branch already fills the same weight(1f) area, so
            // there's nothing to interpolate there anyway.
            //
            // Rail-driven top-level switches (Home/Movies/Series/Search/
            // Settings, all reached via popToRoot from NavRail) skip the
            // fade entirely instead: AnimatedContent composes BOTH the
            // outgoing and incoming screen for the duration of any
            // transition, even a fast one, and doing that at the exact
            // moment a freshly-entered Movies/Series grid is also doing its
            // own first composition/layout/image-request burst was a real,
            // avoidable source of the "Menu -> Movies" lag — not just a
            // repaint cost. Details/Player/Search-result navigation (pushed
            // onto the stack, not a rail switch) keeps the fade, where a
            // "drilling into a new page" transition still reads as
            // intentional rather than as a bug.
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    if (initialState.isRailTopLevel() && targetState.isRailTopLevel()) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        fadeIn(tween(FlixMotion.ScreenTransitionMs))
                            .togetherWith(fadeOut(tween(FlixMotion.ScreenTransitionMs)))
                    }
                },
                label = "screenTransition",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) { screen ->
                when (screen) {
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
                        onPlay = { push(it) },
                        onMovieClick = { push(ContentScreen.MovieDetails(it.streamId)) }
                    )
                    is ContentScreen.SeriesDetails -> SeriesDetailsScreen(
                        graph = graph,
                        catalogState = catalogState,
                        seriesId = screen.seriesId,
                        onPlay = { push(it) },
                        onSeriesClick = { push(ContentScreen.SeriesDetails(it.seriesId)) }
                    )
                    is ContentScreen.Search -> saveableStateHolder.SaveableStateProvider("search") {
                        SearchScreen(
                            catalogState = catalogState,
                            onMovieClick = { push(ContentScreen.MovieDetails(it.streamId)) },
                            onSeriesClick = { push(ContentScreen.SeriesDetails(it.seriesId)) }
                        )
                    }
                    is ContentScreen.Settings -> {
                        val autoPlayEnabled by graph.autoplaySettingsStore.autoPlayEnabled.collectAsState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.safeVertical),
                            verticalArrangement = Arrangement.spacedBy(FlixSpacing.rowHeaderGap)
                        ) {
                            SectionHeader(title = "Playback")
                            SettingsToggleRow(
                                title = "Auto Play Next Episode",
                                description = "Automatically play the next episode when one is available.",
                                checked = autoPlayEnabled,
                                onCheckedChange = { graph.autoplaySettingsStore.setAutoPlayEnabled(it) }
                            )
                        }
                    }
                    is ContentScreen.Player -> Unit // handled by the fullscreen branch above
                }
            }
        }
    }

    if (showExitDialog) {
        ExitConfirmationDialog(
            onCancel = { showExitDialog = false },
            onExit = { activity?.finish() }
        )
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

// ---- Home content rows -----------------------------------------------------

private data class RowItem(
    val key: String,
    val title: String,
    val posterUrl: String?,
    val subtitle: String?,
    val backdropUrl: String?,
    val onClick: () -> Unit
)

private sealed class HomeRowSpec(val key: String) {
    class Posters(key: String, val title: String, val items: List<RowItem>) : HomeRowSpec(key)
    class Continuing(key: String, val items: List<ContinueWatchingEntry>) : HomeRowSpec(key)
}

/**
 * Home's outer shell: one continuous LazyColumn — the cinematic hero is its
 * first item (so it scrolls away naturally, like it would on any premium
 * streaming app), followed by each carousel as its own item. Nothing here
 * overlaps: LazyColumn lays items out strictly sequentially, each measured
 * with unbounded height, so a row can never be squeezed or bleed into its
 * neighbor the way a fixed-height Column could.
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
    // recomposes this function or the rows. Only HomeHero, which actually
    // reads `.value`, recomposes on focus change.
    val focused = remember { mutableStateOf<RowItem?>(null) }

    // The hand-rolled back stack fully disposes Home's composition while
    // another screen is showing (it's a different `when` branch, not just
    // hidden), so plain `remember` state for "has this device ever focused
    // Home's content before" wouldn't survive a details screen visit —
    // rememberSaveable does, via the same "home" SaveableStateProvider that
    // already restores each row's scroll position.
    var everFocusedContent by rememberSaveable { mutableStateOf(false) }
    val contentFocusRequester = remember { FocusRequester() }
    val homeListState = rememberLazyListState()

    // Precise "focus the exact poster that launched Details" state: which
    // row, which item within it. Saveable so it survives the dispose/
    // recompose that happens while Details is on screen. Cleared by
    // whichever row's effect actually finds and focuses the item (or, if the
    // row/item no longer exists by the time we're back, by the fallback
    // path below instead of being left dangling forever).
    var pendingFocusRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFocusItemKey by rememberSaveable { mutableStateOf<String?>(null) }

    when (catalogState) {
        is CatalogUiState.Loading -> HomeLoadingSkeleton()
        is CatalogUiState.Error -> Box(modifier = Modifier.fillMaxSize().padding(horizontal = FlixSpacing.safeHorizontal)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Couldn't load your catalog: ${catalogState.message}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
                SecondaryActionButton(text = "Retry", onClick = onRetry)
            }
        }
        is CatalogUiState.Loaded -> {
            val snapshot = catalogState.snapshot

            // All four were previously plain `val`s evaluated eagerly on
            // every recomposition of this whole branch (e.g. whenever
            // catalogState's identity changes upstream) — the
            // remember(...) further down only memoized the small
            // buildList wrapper around them, not the actual sort/map
            // passes over the full catalog feeding it. Keyed on snapshot
            // alone (not the click lambdas, which are cheap to recreate
            // and don't affect what these lists contain).
            val (recentlyAdded, trending, latestMovies, latestSeries) = remember(snapshot) {
                val recentlyAddedList = (snapshot.movies.map { it to it.addedEpochSeconds } + snapshot.series.map { it to it.addedEpochSeconds })
                    .sortedByDescending { it.second }
                    .take(ROW_ITEM_LIMIT)
                    .map { (item, _) -> item.toRowItem(onMovieClick, onSeriesClick) }

                // Deterministic trending: rating (60%) + recency over a
                // 90-day falloff (40%). Not TMDB-backed this pass — see
                // project notes.
                val trendingList = (snapshot.movies.map { it to trendingScore(it.rating, it.addedEpochSeconds) } +
                    snapshot.series.map { it to trendingScore(it.rating, it.addedEpochSeconds) })
                    .sortedByDescending { it.second }
                    .take(ROW_ITEM_LIMIT)
                    .map { (item, _) -> item.toRowItem(onMovieClick, onSeriesClick) }

                val latestMoviesList = snapshot.movies
                    .sortedByDescending { it.addedEpochSeconds }
                    .take(ROW_ITEM_LIMIT)
                    .map { it.toRowItem(onMovieClick) }

                val latestSeriesList = snapshot.series
                    .sortedByDescending { it.addedEpochSeconds }
                    .take(ROW_ITEM_LIMIT)
                    .map { it.toRowItem(onSeriesClick) }

                HomeCatalogRows(recentlyAddedList, trendingList, latestMoviesList, latestSeriesList)
            }

            // Whatever the user last focused stays featured; before any
            // focus event, feature Continue Watching's top item, else the
            // most recently added title — never a blank hero.
            val heroFallback = continueWatching.firstOrNull()?.let { entry ->
                entry.toHeroRowItem { onContinueWatchingClick(entry) }
            } ?: recentlyAdded.firstOrNull()

            val onRowFocus: (RowItem) -> Unit = { item -> focused.value = item; everFocusedContent = true }

            val rowSpecs = remember(continueWatching, recentlyAdded, trending, latestMovies, latestSeries) {
                buildList {
                    if (continueWatching.isNotEmpty()) add(HomeRowSpec.Continuing("row-continue-watching", continueWatching))
                    if (recentlyAdded.isNotEmpty()) add(HomeRowSpec.Posters("row-recently-added", "Recently Added", recentlyAdded))
                    if (trending.isNotEmpty()) add(HomeRowSpec.Posters("row-trending", "Trending", trending))
                    if (latestMovies.isNotEmpty()) add(HomeRowSpec.Posters("row-latest-movies", "Latest Movies", latestMovies))
                    if (latestSeries.isNotEmpty()) add(HomeRowSpec.Posters("row-latest-series", "Latest Series", latestSeries))
                }
            }

            // Precise restoration: scroll the outer column to the row that
            // launched Details (1 = the hero item before any row — there's no
            // separate hero-spacer item; the gap comes from the LazyColumn's
            // own verticalArrangement below). If that row no longer exists by
            // the time we're back (catalog changed under us), fall through to
            // the generic "focus somewhere in content" fallback instead of
            // leaving focus stuck on a target that will never resolve.
            LaunchedEffect(pendingFocusRowKey, rowSpecs) {
                val targetRowKey = pendingFocusRowKey ?: return@LaunchedEffect
                val rowIndex = rowSpecs.indexOfFirst { it.key == targetRowKey }
                if (rowIndex >= 0) {
                    homeListState.scrollToItem(rowIndex + 1)
                } else {
                    pendingFocusRowKey = null
                    pendingFocusItemKey = null
                    if (everFocusedContent) contentFocusRequester.requestFocus()
                }
            }

            // Generic fallback: re-entering Home after a details/browse
            // visit should never leave focus unset — but on the very
            // first-ever launch (nothing focused yet), the nav rail should
            // keep taking initial focus as before, so this only fires on a
            // genuine return trip, and only when there's no precise target
            // (that case is handled by the row/item effects instead, so this
            // never fights them for focus).
            LaunchedEffect(Unit) {
                if (everFocusedContent && pendingFocusItemKey == null) contentFocusRequester.requestFocus()
            }

            LazyColumn(
                state = homeListState,
                // A single consistent gap after every item (hero included) —
                // previously a one-off "hero-spacer" item handled the hero
                // case and every row butted straight up against the next
                // row's heading with no gap at all, which is what read as
                // "Recently Added sitting right under Continue Watching."
                verticalArrangement = Arrangement.spacedBy(FlixSpacing.sectionGap),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocusRequester)
                    .focusRestorer()
            ) {
                item(key = "hero") {
                    HomeHero(
                        state = focused,
                        fallback = heroFallback,
                        accountStatusStore = accountStatusStore,
                        modifier = Modifier.fillParentMaxHeight(0.56f)
                    )
                }

                // contentType lets Compose pool/reuse layout state across
                // recompositions the way RecyclerView pools ViewHolders by
                // type — this list mixes two structurally different row
                // shapes (Continuing vs Posters), so without it Compose
                // can't assume adjacent items are interchangeable.
                items(rowSpecs, key = { it.key }, contentType = { it::class }) { spec ->
                    when (spec) {
                        is HomeRowSpec.Continuing -> ContinueWatchingRow(
                            rowKey = spec.key,
                            items = spec.items,
                            pendingFocusItemKey = if (pendingFocusRowKey == spec.key) pendingFocusItemKey else null,
                            onFocusedItemChange = onRowFocus,
                            onItemLaunch = { rowKey, itemKey ->
                                pendingFocusRowKey = rowKey
                                pendingFocusItemKey = itemKey
                            },
                            onFocusRestored = {
                                pendingFocusRowKey = null
                                pendingFocusItemKey = null
                            },
                            onClick = onContinueWatchingClick
                        )
                        is HomeRowSpec.Posters -> PosterRow(
                            rowKey = spec.key,
                            title = spec.title,
                            items = spec.items,
                            pendingFocusItemKey = if (pendingFocusRowKey == spec.key) pendingFocusItemKey else null,
                            onFocusedItemChange = onRowFocus,
                            onItemLaunch = { rowKey, itemKey ->
                                pendingFocusRowKey = rowKey
                                pendingFocusItemKey = itemKey
                            },
                            onFocusRestored = {
                                pendingFocusRowKey = null
                                pendingFocusItemKey = null
                            }
                        )
                    }
                }
                item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(FlixSpacing.safeVertical)) }
            }
        }
    }
}

/**
 * The cinematic hero: full-bleed backdrop of whichever item currently has
 * D-pad focus (falling back to a sensible default), a compact subscription
 * label in the corner, and title/metadata/action over a readable gradient.
 * Reads `state.value` itself so a focus change only recomposes this one
 * composable, never the rows below it.
 */
@Composable
private fun HomeHero(
    state: State<RowItem?>,
    fallback: RowItem?,
    accountStatusStore: AccountStatusStore,
    modifier: Modifier = Modifier
) {
    val item = state.value ?: fallback
    val expiresAtEpochSeconds by accountStatusStore.expiresAtEpochSeconds.collectAsState()

    Box(modifier = modifier.fillMaxWidth()) {
        BackdropLayer(state = remember(item) { derivedStateOf { item?.backdropUrl } }, modifier = Modifier.fillMaxSize())

        val expires = expiresAtEpochSeconds
        if (expires != null) {
            val formatted = remember(expires) {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(expires * 1000))
            }
            Text(
                text = "Active until $formatted",
                style = MaterialTheme.typography.labelMedium,
                color = FtTextMuted,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.safeVertical)
            )
        }

        if (item != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .widthIn(max = 760.dp)
                    .padding(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.safeVertical),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Fixed-height boxes, not just maxLines: a shorter title or a
                // missing subtitle must never shrink this Column's measured
                // height, because it's BottomStart-anchored — a height change
                // here shifts every line (including the button) up or down,
                // and since HomeHero re-renders on every focus change across
                // any row, an un-reserved height was reflowing on nearly
                // every D-pad press. This is the actual mechanism behind the
                // "whole screen shakes on LEFT/RIGHT" report.
                Box(modifier = Modifier.height(112.dp), contentAlignment = Alignment.BottomStart) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.displayLarge,
                        color = FtTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FtTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                PrimaryActionButton(text = "More Info", onClick = item.onClick)
            }
        }
    }
}

@Composable
private fun HomeLoadingSkeleton() {
    Column(modifier = Modifier.fillMaxSize().padding(top = FlixSpacing.safeVertical)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(horizontal = FlixSpacing.safeHorizontal)
                .clip(RoundedCornerShape(16.dp))
                .background(FtSurfaceElevated)
        )
        Spacer(modifier = Modifier.height(FlixSpacing.sectionGap))
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.padding(start = FlixSpacing.safeHorizontal)) {
                    Text(text = "Loading…", style = MaterialTheme.typography.titleMedium, color = FtTextSecondary)
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = FlixSpacing.safeHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap)
                ) {
                    items(6) { PosterSkeletonCard() }
                }
                Spacer(modifier = Modifier.height(FlixSpacing.sectionGap - 12.dp))
            }
        }
    }
}

@Composable
private fun PosterRow(
    rowKey: String,
    title: String,
    items: List<RowItem>,
    pendingFocusItemKey: String?,
    onFocusedItemChange: (RowItem) -> Unit,
    onItemLaunch: (rowKey: String, itemKey: String) -> Unit,
    onFocusRestored: () -> Unit
) {
    val railFocusRequester = LocalRailRevealFocusRequester.current
    Column(verticalArrangement = Arrangement.spacedBy(FlixSpacing.rowHeaderGap)) {
        SectionHeader(title = title, modifier = Modifier.padding(start = FlixSpacing.safeHorizontal))
        val listState = rememberLazyListState()

        // Scrolls this row's own LazyRow to the exact item that launched
        // Details, once the outer LazyColumn has brought this row itself
        // into view (see the matching effect in HomeContent).
        LaunchedEffect(pendingFocusItemKey, items) {
            val targetKey = pendingFocusItemKey ?: return@LaunchedEffect
            val index = items.indexOfFirst { it.key == targetKey }
            if (index >= 0) listState.scrollToItem(index) else onFocusRestored()
        }

        LazyRow(
            state = listState,
            // LazyRow clips its content to its own bounds, and this row's
            // own height (contributed to the outer LazyColumn) is this
            // padding plus the tallest visible card — both fixed regardless
            // of focus. A focused card's scale+lift growth (up to ~1.045x +
            // 6dp lift) must land entirely inside focusReserveTop/Bottom, or
            // it both clips against the LazyRow's edge AND can poke past
            // space the outer LazyColumn already considers this row's own,
            // which is what triggers an unwanted compensating vertical
            // scroll on ordinary LEFT/RIGHT navigation.
            contentPadding = PaddingValues(
                start = FlixSpacing.safeHorizontal,
                end = FlixSpacing.safeHorizontal,
                top = FlixSpacing.focusReserveTop,
                bottom = FlixSpacing.focusReserveBottom
            ),
            horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap)
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                val itemFocusRequester = remember(item.key) { FocusRequester() }
                LaunchedEffect(Unit) {
                    if (pendingFocusItemKey == item.key) {
                        itemFocusRequester.requestFocus()
                        onFocusRestored()
                    }
                }
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    subtitle = item.subtitle,
                    onClick = {
                        onItemLaunch(rowKey, item.key)
                        item.onClick()
                    },
                    modifier = Modifier
                        .width(DEFAULT_POSTER_WIDTH)
                        .focusRequester(itemFocusRequester)
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

/**
 * Continue Watching's own row — deliberately not [PosterRow]: resume cards
 * are 16:9 landscape ([ContinueWatchingCard]), not the 2:3 poster shape, so
 * this is a parallel implementation of the exact same focus-restoration
 * wiring (scroll-into-view + per-item requestFocus on recomposition) rather
 * than trying to force both card shapes through one generic row.
 */
@Composable
private fun ContinueWatchingRow(
    rowKey: String,
    items: List<ContinueWatchingEntry>,
    pendingFocusItemKey: String?,
    onFocusedItemChange: (RowItem) -> Unit,
    onItemLaunch: (rowKey: String, itemKey: String) -> Unit,
    onFocusRestored: () -> Unit,
    onClick: (ContinueWatchingEntry) -> Unit
) {
    val railFocusRequester = LocalRailRevealFocusRequester.current
    Column(verticalArrangement = Arrangement.spacedBy(FlixSpacing.rowHeaderGap)) {
        SectionHeader(title = "Continue Watching", modifier = Modifier.padding(start = FlixSpacing.safeHorizontal))
        val listState = rememberLazyListState()

        LaunchedEffect(pendingFocusItemKey, items) {
            val targetKey = pendingFocusItemKey ?: return@LaunchedEffect
            val index = items.indexOfFirst { continueWatchingKey(it) == targetKey }
            if (index >= 0) listState.scrollToItem(index) else onFocusRestored()
        }

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(
                start = FlixSpacing.safeHorizontal,
                end = FlixSpacing.safeHorizontal,
                top = FlixSpacing.focusReserveTop,
                bottom = FlixSpacing.focusReserveBottom
            ),
            horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap)
        ) {
            itemsIndexed(items, key = { _, entry -> continueWatchingKey(entry) }) { index, entry ->
                val key = continueWatchingKey(entry)
                val itemFocusRequester = remember(key) { FocusRequester() }
                LaunchedEffect(Unit) {
                    if (pendingFocusItemKey == key) {
                        itemFocusRequester.requestFocus()
                        onFocusRestored()
                    }
                }
                ContinueWatchingCard(
                    entry = entry,
                    onClick = {
                        onItemLaunch(rowKey, key)
                        onClick(entry)
                    },
                    modifier = Modifier
                        .focusRequester(itemFocusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) onFocusedItemChange(entry.toHeroRowItem { onClick(entry) })
                        }
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

private fun continueWatchingKey(entry: ContinueWatchingEntry) = "cw-${entry.mediaType}-${entry.streamId}"

private fun ContinueWatchingEntry.toHeroRowItem(onClick: () -> Unit): RowItem {
    val display = continueWatchingDisplay(this)
    return RowItem(continueWatchingKey(this), display.primary, posterUrl, display.secondary, posterUrl, onClick)
}

private const val ROW_ITEM_LIMIT = 15

private data class HomeCatalogRows(
    val recentlyAdded: List<RowItem>,
    val trending: List<RowItem>,
    val latestMovies: List<RowItem>,
    val latestSeries: List<RowItem>
)

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
