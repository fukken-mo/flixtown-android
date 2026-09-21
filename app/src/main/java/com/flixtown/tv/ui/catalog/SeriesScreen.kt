package com.flixtown.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.data.model.Category
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.components.FilterChip
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.theme.FtTextPrimary

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

    val hasRatings = remember(series) { series.any { (it.rating ?: 0.0) > 0.0 } }
    val sortOptions = remember(hasRatings) {
        if (hasRatings) SortOption.entries else SortOption.entries.filter { it != SortOption.Rating }
    }

    val visibleSeries = remember(series, selectedCategoryId, sort) {
        val base = if (selectedCategoryId == null) series else series.filter { it.categoryId == selectedCategoryId }
        sortSeries(base, sort)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Series", style = MaterialTheme.typography.headlineMedium)
            FlixFocusSurface(onClick = onSearchClick) { Text("Search") }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FilterChip(label = "All", isSelected = selectedCategoryId == null, onClick = { selectedCategoryId = null })
            }
            items(categories, key = { it.id }) { category ->
                FilterChip(
                    label = category.name,
                    isSelected = selectedCategoryId == category.id,
                    onClick = { selectedCategoryId = category.id }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Sort:",
                style = MaterialTheme.typography.labelLarge,
                color = FtTextPrimary,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sortOptions, key = { it.name }) { option ->
                FilterChip(label = option.label, isSelected = sort == option, onClick = { sort = option })
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(visibleSeries, key = { it.seriesId }) { show ->
                PosterCard(
                    title = show.name,
                    posterUrl = show.posterUrl,
                    subtitle = show.year?.toString(),
                    onClick = { onSeriesClick(show) },
                    modifier = Modifier.fillMaxWidth()
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
