package com.flixtown.tv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.catalog.GRID_COLUMNS
import com.flixtown.tv.ui.components.FlixTextField
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.theme.FtTextSecondary
import kotlinx.coroutines.delay

private data class SearchResult(
    val key: String,
    val title: String,
    val posterUrl: String?,
    val subtitle: String?,
    val onClick: () -> Unit
)

private const val DEBOUNCE_MS = 250L

@Composable
fun SearchScreen(
    catalogState: CatalogUiState,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf(query) }

    LaunchedEffect(query) {
        delay(DEBOUNCE_MS)
        debouncedQuery = query
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 28.dp)) {
        Box(modifier = Modifier.padding(horizontal = 40.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "Search", style = MaterialTheme.typography.headlineMedium)
                FlixTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search movies and series",
                    modifier = Modifier.widthIn(max = 520.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val results = if (catalogState is CatalogUiState.Loaded && debouncedQuery.isNotBlank()) {
            buildResults(catalogState, debouncedQuery, onMovieClick, onSeriesClick)
        } else {
            emptyList()
        }

        when {
            debouncedQuery.isBlank() -> Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                Text(
                    text = "Start typing to search your Movies and Series library.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
            }
            results.isEmpty() -> Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                Text(
                    text = "No results for \"$debouncedQuery\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results, key = { it.key }) { result ->
                    PosterCard(
                        title = result.title,
                        posterUrl = result.posterUrl,
                        subtitle = result.subtitle,
                        onClick = result.onClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun buildResults(
    state: CatalogUiState.Loaded,
    query: String,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit
): List<SearchResult> {
    val needle = query.trim().lowercase()
    val movieResults = state.snapshot.movies
        .filter { it.name.lowercase().contains(needle) }
        .map { movie ->
            SearchResult("movie-${movie.streamId}", movie.name, movie.posterUrl, movie.year?.toString()) {
                onMovieClick(movie)
            }
        }
    val seriesResults = state.snapshot.series
        .filter { it.name.lowercase().contains(needle) }
        .map { series ->
            SearchResult("series-${series.seriesId}", series.name, series.posterUrl, series.year?.toString()) {
                onSeriesClick(series)
            }
        }
    return (movieResults + seriesResults).sortedBy { it.title.lowercase() }
}
