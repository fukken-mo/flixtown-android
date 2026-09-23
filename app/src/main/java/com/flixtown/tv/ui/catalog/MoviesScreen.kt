package com.flixtown.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.data.model.Category
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.nav.LocalRailRevealFocusRequester
import com.flixtown.tv.ui.theme.FlixSpacing
import kotlin.math.roundToInt

@Composable
fun MoviesScreen(
    catalogState: CatalogUiState,
    initialCategoryId: String?,
    onMovieClick: (Movie) -> Unit,
    onSearchClick: () -> Unit,
    onRetry: () -> Unit
) {
    when (catalogState) {
        is CatalogUiState.Loading -> CatalogGridSkeleton()
        is CatalogUiState.Error -> CatalogErrorRetry(catalogState.message, onRetry)
        is CatalogUiState.Loaded -> MoviesLoaded(
            movies = catalogState.snapshot.movies,
            categories = catalogState.snapshot.vodCategories,
            initialCategoryId = initialCategoryId,
            onMovieClick = onMovieClick,
            onSearchClick = onSearchClick
        )
    }
}

@Composable
private fun MoviesLoaded(
    movies: List<Movie>,
    categories: List<Category>,
    initialCategoryId: String?,
    onMovieClick: (Movie) -> Unit,
    onSearchClick: () -> Unit
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf(initialCategoryId) }
    var sort by rememberSaveable { mutableStateOf(SortOption.RecentlyAdded) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val hasRatings = remember(movies) { movies.any { (it.rating ?: 0.0) > 0.0 } }
    val sortOptions = remember(hasRatings) {
        if (hasRatings) SortOption.entries else SortOption.entries.filter { it != SortOption.Rating }
    }
    val categoryOptions = remember(categories) { listOf<Category?>(null) + categories }
    val selectedCategory = categoryOptions.firstOrNull { it?.id == selectedCategoryId }

    val visibleMovies = remember(movies, selectedCategoryId, sort) {
        val base = if (selectedCategoryId == null) movies else movies.filter { it.categoryId == selectedCategoryId }
        sortMovies(base, sort)
    }

    val railFocusRequester = LocalRailRevealFocusRequester.current

    val gridState = rememberLazyGridState()
    // Which poster launched Details, so BACK can put focus back on it
    // (instead of the grid's default top-left item) — cleared by that exact
    // item's own effect once it has re-claimed focus. See HomeShellScreen's
    // matching Home-row version for the same pattern applied to a LazyRow.
    var pendingFocusStreamId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusStreamId, visibleMovies) {
        val targetId = pendingFocusStreamId ?: return@LaunchedEffect
        val index = visibleMovies.indexOfFirst { it.streamId == targetId }
        if (index >= 0) gridState.scrollToItem(index)
    }

    // Ambient backdrop: whichever poster currently has focus, debounced and
    // crossfaded by CatalogBrowseBackground/BackdropLayer exactly like
    // Home's hero — this is a State object, read only inside that leaf
    // composable, so a focus-change write here never recomposes this
    // function or the grid itself. Movies has no separate backdrop field on
    // its list-level model (only MovieDetails, fetched lazily per-title on
    // Details open, does) — reusing the poster art that's already loading
    // for the focused card costs nothing extra over the network.
    val focusedBackdropUrl = remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    // Root-window coordinates, not coordinates relative to this screen's own
    // Box — the content pane sits to the right of the nav rail, whose width
    // itself animates (see HomeShellScreen), so every position captured via
    // onGloballyPositioned below is relative to the whole window and must be
    // offset back by this container's own root position before use.
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    var categoryButtonPosition by remember { mutableStateOf(Offset.Zero) }
    var categoryButtonHeight by remember { mutableStateOf(0) }
    var sortButtonPosition by remember { mutableStateOf(Offset.Zero) }
    var sortButtonHeight by remember { mutableStateOf(0) }
    val menuGapPx = remember(density) { with(density) { 8.dp.roundToPx() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerOrigin = it.positionInRoot() }
    ) {
        CatalogBrowseBackground(backdropUrlState = focusedBackdropUrl, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.rowHeaderGap + 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Movies", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CatalogFilterButton(
                        label = "Category",
                        valueLabel = selectedCategory?.name ?: "All",
                        onClick = { showCategoryMenu = true },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            categoryButtonPosition = coords.positionInRoot()
                            categoryButtonHeight = coords.size.height
                        }
                    )
                    CatalogFilterButton(
                        label = "Sort",
                        valueLabel = sort.label,
                        onClick = { showSortMenu = true },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            sortButtonPosition = coords.positionInRoot()
                            sortButtonHeight = coords.size.height
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    CatalogSearchButton(onClick = onSearchClick)
                }
            }

            Spacer(modifier = Modifier.height(FlixSpacing.rowHeaderGap))

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(GRID_COLUMNS),
                contentPadding = PaddingValues(
                    start = FlixSpacing.safeHorizontal,
                    end = FlixSpacing.safeHorizontal,
                    // Reserves room for a focused top-row card's scale+lift
                    // growth so it can't clip against the grid's own top
                    // edge or poke past what the grid already accounts for
                    // as its own bounds (which is what triggers an unwanted
                    // scroll correction on ordinary LEFT/RIGHT navigation).
                    top = FlixSpacing.focusReserveTop,
                    bottom = FlixSpacing.safeVertical
                ),
                // Local-only +4dp over the shared cardGap token (not a
                // change to FlixSpacing.cardGap itself, which Home/Details/
                // Search also use) — a touch more breathing room here per
                // this pass's visual brief without affecting any other
                // screen.
                horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap + 4.dp),
                // >= 2x focusReserveTop, since spacedBy splits this evenly
                // between each pair of adjacent grid rows — every row needs
                // the same top-edge protection the grid's own contentPadding
                // gives the very first one.
                verticalArrangement = Arrangement.spacedBy(FlixSpacing.focusReserveTop * 2),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    visibleMovies,
                    key = { _, movie -> movie.streamId },
                    contentType = { _, _ -> "poster" }
                ) { index, movie ->
                    val isLeftEdge = index % GRID_COLUMNS == 0
                    val itemFocusRequester = remember(movie.streamId) { FocusRequester() }
                    LaunchedEffect(Unit) {
                        if (pendingFocusStreamId == movie.streamId) {
                            itemFocusRequester.requestFocus()
                            pendingFocusStreamId = null
                        }
                    }
                    PosterCard(
                        title = movie.name,
                        posterUrl = movie.posterUrl,
                        subtitle = movie.year?.toString(),
                        onClick = {
                            pendingFocusStreamId = movie.streamId
                            onMovieClick(movie)
                        },
                        onFocusChanged = { focused -> if (focused) focusedBackdropUrl.value = movie.posterUrl },
                        premiumFocusTreatment = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(itemFocusRequester)
                            .let { m ->
                                if (isLeftEdge && railFocusRequester != null) {
                                    m.focusProperties { left = railFocusRequester }
                                } else {
                                    m
                                }
                            }
                    )
                }
            }
        }

        if (showCategoryMenu) {
            Box(
                modifier = Modifier.offset {
                    val x = categoryButtonPosition.x - containerOrigin.x
                    val y = categoryButtonPosition.y - containerOrigin.y + categoryButtonHeight
                    IntOffset(x.roundToInt(), y.roundToInt() + menuGapPx)
                }
            ) {
                SelectorMenu(
                    title = "Category",
                    options = categoryOptions,
                    selected = selectedCategory,
                    optionLabel = { it?.name ?: "All" },
                    onSelect = { selectedCategoryId = it?.id },
                    onDismiss = { showCategoryMenu = false }
                )
            }
        }
        if (showSortMenu) {
            Box(
                modifier = Modifier.offset {
                    val x = sortButtonPosition.x - containerOrigin.x
                    val y = sortButtonPosition.y - containerOrigin.y + sortButtonHeight
                    IntOffset(x.roundToInt(), y.roundToInt() + menuGapPx)
                }
            ) {
                SelectorMenu(
                    title = "Sort",
                    options = sortOptions,
                    selected = sort,
                    optionLabel = { it.label },
                    onSelect = { sort = it },
                    onDismiss = { showSortMenu = false }
                )
            }
        }
    }
}

private fun sortMovies(movies: List<Movie>, sort: SortOption): List<Movie> = when (sort) {
    SortOption.RecentlyAdded -> movies.sortedByDescending { it.addedEpochSeconds }
    SortOption.AZ -> movies.sortedBy { it.name.lowercase() }
    SortOption.ZA -> movies.sortedByDescending { it.name.lowercase() }
    SortOption.Year -> movies.sortedByDescending { it.year ?: -1 }
    SortOption.Rating -> movies.sortedByDescending { it.rating ?: -1.0 }
}
