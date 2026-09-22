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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.data.model.Category
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.ui.components.BackdropLayer
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.components.SelectorButton
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.nav.LocalRailRevealFocusRequester

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
    // Movies don't carry a TMDB/Xtream backdrop in list data (only the
    // per-title details call does, and firing that on every focus change
    // would lag remote navigation) — poster art is the practical, instant
    // fallback per the dynamic-background spec.
    // Held as a State object (not `by`) and only ever unwrapped inside
    // BackdropLayer, so a focus-change write here never recomposes this
    // whole screen (grid included) — only that leaf.
    val focusedBackdropState = remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        BackdropLayer(state = focusedBackdropState)
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

            Row(
                modifier = Modifier.padding(horizontal = 40.dp),
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

            Spacer(modifier = Modifier.height(24.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(visibleMovies, key = { _, movie -> movie.streamId }) { index, movie ->
                    val isLeftEdge = index % GRID_COLUMNS == 0
                    PosterCard(
                        title = movie.name,
                        posterUrl = movie.posterUrl,
                        subtitle = movie.year?.toString(),
                        onClick = { onMovieClick(movie) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { s -> if (s.isFocused) focusedBackdropState.value = movie.posterUrl }
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
            Box(modifier = Modifier.padding(start = 40.dp, top = 96.dp)) {
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
            Box(modifier = Modifier.padding(start = 210.dp, top = 96.dp)) {
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
