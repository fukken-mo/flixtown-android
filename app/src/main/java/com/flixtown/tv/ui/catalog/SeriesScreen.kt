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
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.nav.LocalRailRevealFocusRequester
import com.flixtown.tv.ui.theme.FlixSpacing
import kotlin.math.roundToInt

@Composable
fun SeriesScreen(
    catalogState: CatalogUiState,
    initialCategoryId: String?,
    onSeriesClick: (Series) -> Unit,
    onSearchClick: () -> Unit,
    onRetry: () -> Unit
) {
    when (catalogState) {
        is CatalogUiState.Loading -> CatalogGridSkeleton()
        is CatalogUiState.Error -> CatalogErrorRetry(catalogState.message, onRetry)
        is CatalogUiState.Loaded -> SeriesLoaded(
            series = catalogState.snapshot.series,
            categories = catalogState.snapshot.seriesCategories,
            initialCategoryId = initialCategoryId,
            onSeriesClick = onSeriesClick,
            onSearchClick = onSearchClick
        )
    }
}

@Composable
private fun SeriesLoaded(
    series: List<Series>,
    categories: List<Category>,
    initialCategoryId: String?,
    onSeriesClick: (Series) -> Unit,
    onSearchClick: () -> Unit
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf(initialCategoryId) }
    var sort by rememberSaveable { mutableStateOf(SortOption.RecentlyAdded) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val hasRatings = remember(series) { series.any { (it.rating ?: 0.0) > 0.0 } }
    val sortOptions = remember(hasRatings) {
        if (hasRatings) SortOption.entries else SortOption.entries.filter { it != SortOption.Rating }
    }
    val categoryOptions = remember(categories) { listOf<Category?>(null) + categories }
    val selectedCategory = categoryOptions.firstOrNull { it?.id == selectedCategoryId }

    val visibleSeries = remember(series, selectedCategoryId, sort) {
        val base = if (selectedCategoryId == null) series else series.filter { it.categoryId == selectedCategoryId }
        sortSeries(base, sort)
    }

    val railFocusRequester = LocalRailRevealFocusRequester.current

    val gridState = rememberLazyGridState()
    var pendingFocusSeriesId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusSeriesId, visibleSeries) {
        val targetId = pendingFocusSeriesId ?: return@LaunchedEffect
        val index = visibleSeries.indexOfFirst { it.seriesId == targetId }
        if (index >= 0) gridState.scrollToItem(index)
    }

    // Ambient backdrop: whichever poster currently has focus, debounced and
    // crossfaded by CatalogBrowseBackground/BackdropLayer exactly like
    // Home's hero — a State object, read only inside that leaf composable,
    // so a focus-change write here never recomposes this function or the
    // grid itself. Series' list-level model already carries a real
    // backdropUrl (unlike Movie), so that's preferred, falling back to
    // posterUrl when a title has none — same convention Home's own Series
    // row already uses.
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
                Text(text = "Series", style = MaterialTheme.typography.headlineMedium)
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
                    // Same headroom reasoning as MoviesScreen's grid.
                    top = FlixSpacing.focusReserveTop,
                    bottom = FlixSpacing.safeVertical
                ),
                // Local-only +4dp over the shared cardGap token — see
                // MoviesScreen's matching comment.
                horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap + 4.dp),
                verticalArrangement = Arrangement.spacedBy(FlixSpacing.focusReserveTop * 2),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    visibleSeries,
                    key = { _, show -> show.seriesId },
                    contentType = { _, _ -> "poster" }
                ) { index, show ->
                    val isLeftEdge = index % GRID_COLUMNS == 0
                    val itemFocusRequester = remember(show.seriesId) { FocusRequester() }
                    LaunchedEffect(Unit) {
                        if (pendingFocusSeriesId == show.seriesId) {
                            itemFocusRequester.requestFocus()
                            pendingFocusSeriesId = null
                        }
                    }
                    PosterCard(
                        title = show.name,
                        posterUrl = show.posterUrl,
                        subtitle = show.year?.toString(),
                        onClick = {
                            pendingFocusSeriesId = show.seriesId
                            onSeriesClick(show)
                        },
                        onFocusChanged = { focused ->
                            if (focused) focusedBackdropUrl.value = show.backdropUrl ?: show.posterUrl
                        },
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

private fun sortSeries(series: List<Series>, sort: SortOption): List<Series> = when (sort) {
    SortOption.RecentlyAdded -> series.sortedByDescending { it.addedEpochSeconds }
    SortOption.AZ -> series.sortedBy { it.name.lowercase() }
    SortOption.ZA -> series.sortedByDescending { it.name.lowercase() }
    SortOption.Year -> series.sortedByDescending { it.year ?: -1 }
    SortOption.Rating -> series.sortedByDescending { it.rating ?: -1.0 }
}
