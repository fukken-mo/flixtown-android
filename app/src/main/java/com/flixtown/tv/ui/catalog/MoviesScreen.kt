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
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.ui.components.FilterChip
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.theme.FtTextPrimary

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
    categories: List<com.flixtown.tv.data.model.Category>,
    initialCategoryId: String?,
    onMovieClick: (Movie) -> Unit,
    onSearchClick: () -> Unit
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf(initialCategoryId) }
    var sort by rememberSaveable { mutableStateOf(SortOption.RecentlyAdded) }

    val hasRatings = remember(movies) { movies.any { (it.rating ?: 0.0) > 0.0 } }
    val sortOptions = remember(hasRatings) {
        if (hasRatings) SortOption.entries else SortOption.entries.filter { it != SortOption.Rating }
    }

    val visibleMovies = remember(movies, selectedCategoryId, sort) {
        val base = if (selectedCategoryId == null) movies else movies.filter { it.categoryId == selectedCategoryId }
        sortMovies(base, sort)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Movies", style = MaterialTheme.typography.headlineMedium)
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
            items(visibleMovies, key = { it.streamId }) { movie ->
                PosterCard(
                    title = movie.name,
                    posterUrl = movie.posterUrl,
                    subtitle = movie.year?.toString(),
                    onClick = { onMovieClick(movie) },
                    modifier = Modifier.fillMaxWidth()
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
