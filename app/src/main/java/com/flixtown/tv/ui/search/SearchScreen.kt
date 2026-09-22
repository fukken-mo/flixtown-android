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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.data.model.Movie
import com.flixtown.tv.data.model.Series
import com.flixtown.tv.ui.catalog.CatalogUiState
import com.flixtown.tv.ui.catalog.GRID_COLUMNS
import com.flixtown.tv.ui.components.FlixTextField
import com.flixtown.tv.ui.components.PosterCard
import com.flixtown.tv.ui.nav.LocalRailRevealFocusRequester
import com.flixtown.tv.ui.theme.FlixSpacing
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

    val railFocusRequester = LocalRailRevealFocusRequester.current
    val gridState = rememberLazyGridState()
    // Opening a result and coming back should refocus that exact poster, the
    // same pattern used on Home/Movies/Series — keyed by result key since
    // search results mix movies and series.
    var pendingFocusKey by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(top = FlixSpacing.rowHeaderGap + 12.dp)) {
        Box(modifier = Modifier.padding(horizontal = FlixSpacing.safeHorizontal)) {
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

        Spacer(modifier = Modifier.height(FlixSpacing.sectionGap - 8.dp))

        // buildResults does a full filter+map+sort over the whole catalog —
        // was previously a plain statement re-run on every recomposition of
        // this composable (e.g. every pendingFocusKey change after a
        // result click), not just when the query or catalog actually change.
        val results = remember(catalogState, debouncedQuery) {
            if (catalogState is CatalogUiState.Loaded && debouncedQuery.isNotBlank()) {
                buildResults(catalogState, debouncedQuery, onMovieClick, onSeriesClick)
            } else {
                emptyList()
            }
        }

        LaunchedEffect(pendingFocusKey, results) {
            val targetKey = pendingFocusKey ?: return@LaunchedEffect
            val index = results.indexOfFirst { it.key == targetKey }
            if (index >= 0) gridState.scrollToItem(index)
        }

        when {
            debouncedQuery.isBlank() -> Box(modifier = Modifier.fillMaxWidth().padding(horizontal = FlixSpacing.safeHorizontal)) {
                Text(
                    text = "Start typing to search your Movies and Series library.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
            }
            results.isEmpty() -> Box(modifier = Modifier.fillMaxWidth().padding(horizontal = FlixSpacing.safeHorizontal)) {
                Text(
                    text = "No results for \"$debouncedQuery\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
            }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(GRID_COLUMNS),
                contentPadding = PaddingValues(
                    start = FlixSpacing.safeHorizontal,
                    end = FlixSpacing.safeHorizontal,
                    // Same headroom reasoning as MoviesScreen's grid.
                    top = FlixSpacing.focusReserveTop,
                    bottom = FlixSpacing.safeVertical
                ),
                horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap),
                verticalArrangement = Arrangement.spacedBy(FlixSpacing.focusReserveTop * 2),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(results, key = { _, result -> result.key }) { index, result ->
                    val isLeftEdge = index % GRID_COLUMNS == 0
                    val itemFocusRequester = remember(result.key) { FocusRequester() }
                    LaunchedEffect(Unit) {
                        if (pendingFocusKey == result.key) {
                            itemFocusRequester.requestFocus()
                            pendingFocusKey = null
                        }
                    }
                    PosterCard(
                        title = result.title,
                        posterUrl = result.posterUrl,
                        subtitle = result.subtitle,
                        onClick = {
                            pendingFocusKey = result.key
                            result.onClick()
                        },
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
