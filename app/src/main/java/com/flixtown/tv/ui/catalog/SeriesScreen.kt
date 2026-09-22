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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.data.model.Category
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.components.BackdropLayer
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.SecondaryActionButton
import com.flixtown.tv.ui.components.SelectorButton
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.nav.LocalRailRevealFocusRequester
import com.flixtown.tv.ui.theme.FlixSpacing

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
    val focusedBackdropState = remember { mutableStateOf<String?>(null) }

    val gridState = rememberLazyGridState()
    var pendingFocusSeriesId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusSeriesId, visibleSeries) {
        val targetId = pendingFocusSeriesId ?: return@LaunchedEffect
        val index = visibleSeries.indexOfFirst { it.seriesId == targetId }
        if (index >= 0) gridState.scrollToItem(index)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BackdropLayer(state = focusedBackdropState)
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.rowHeaderGap + 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Series", style = MaterialTheme.typography.headlineMedium)
                SecondaryActionButton(text = "Search", onClick = onSearchClick)
            }

            Row(
                modifier = Modifier.padding(horizontal = FlixSpacing.safeHorizontal),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SelectorButton<Category?>(
                    label = "Category",
                    valueLabel = selectedCategory?.name ?: "All",
                    onClick = { showCategoryMenu = true }
                )
                SelectorButton<SortOption>(
                    label = "Sort",
                    valueLabel = sort.label,
                    onClick = { showSortMenu = true }
                )
            }

            Spacer(modifier = Modifier.height(FlixSpacing.sectionGap - 8.dp))

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(GRID_COLUMNS),
                contentPadding = PaddingValues(
                    start = FlixSpacing.safeHorizontal,
                    end = FlixSpacing.safeHorizontal,
                    // Same headroom reasoning as MoviesScreen's grid.
                    top = FlixSpacing.rowHeaderGap,
                    bottom = FlixSpacing.safeVertical
                ),
                horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap),
                verticalArrangement = Arrangement.spacedBy(FlixSpacing.sectionGap),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(visibleSeries, key = { _, show -> show.seriesId }) { index, show ->
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(itemFocusRequester)
                            .onFocusChanged { s ->
                                if (s.isFocused) focusedBackdropState.value = show.backdropUrl ?: show.posterUrl
                            }
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
            Box(modifier = Modifier.padding(start = FlixSpacing.safeHorizontal, top = 96.dp)) {
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
            Box(modifier = Modifier.padding(start = FlixSpacing.safeHorizontal + 170.dp, top = 96.dp)) {
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
