package com.nimertv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nimertv.app.PlayRequest
import com.nimertv.app.data.Catalog
import com.nimertv.app.data.Channel
import com.nimertv.app.data.Library
import com.nimertv.app.data.Movie
import com.nimertv.app.data.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class SearchResults(val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/** Case-insensitive match on every word of the query; works for Arabic and Latin titles. */
fun matchesQuery(name: String, words: List<String>) = words.all { name.contains(it, ignoreCase = true) }

@Composable
fun SearchScreen(screen: Screen.Search, nav: Nav, catalog: Catalog, library: Library) {
    val context = LocalContext.current
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(screen.query) {
        val words = screen.query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || screen.query.trim().length < 2) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        delay(350) // wait for typing to pause
        searching = true
        error = null
        try {
            // Each section is optional: a provider without series still returns channels and movies.
            val channels = runCatching { catalog.channels() }
            val movies = runCatching { catalog.movies() }
            val series = runCatching { catalog.series() }
            if (channels.isFailure && movies.isFailure && series.isFailure) {
                error = friendlyError(channels.exceptionOrNull()!!)
            }
            results = withContext(Dispatchers.Default) {
                SearchResults(
                    channels.getOrDefault(emptyList()).asSequence().filter { matchesQuery(it.name, words) }.take(LIMIT).toList(),
                    movies.getOrDefault(emptyList()).asSequence().filter { matchesQuery(it.name, words) }.take(LIMIT).toList(),
                    series.getOrDefault(emptyList()).asSequence().filter { matchesQuery(it.name, words) }.take(LIMIT).toList(),
                )
            }
        } finally {
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", style = Type.Title.copy(fontWeight = FontWeight.Black))
            Spacer(Modifier.width(24.dp))
            TvTextField(
                screen.query, { screen.query = it }, "Channels, movies and series — press OK to type",
                Modifier.rememberFocus(screen.focus, "query"), imeAction = ImeAction.Search, width = 480.dp,
            )
            Spacer(Modifier.width(16.dp))
            if (searching) Text("Searching…", style = Type.Caption)
        }
        Spacer(Modifier.height(10.dp))

        val r = results
        when {
            error != null -> Message("Search is unavailable", error!!, Modifier.fillMaxSize())
            r == null -> Message("Find something to watch", "Type at least two letters. Arabic and English titles are both supported.", Modifier.fillMaxSize())
            r.isEmpty -> Message("No results", "Nothing matches \"${screen.query.trim()}\".", Modifier.fillMaxSize())
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (r.channels.isNotEmpty()) {
                    item("channels") {
                        ResultRow("Live TV", r.channels.size) {
                            items(r.channels, key = { it.id }) { ch ->
                                ChannelTile(ch, Modifier.rememberFocus(screen.focus, "ch:${ch.id}")) {
                                    play(context, PlayRequest.Live(r.channels, r.channels.indexOf(ch)))
                                }
                            }
                        }
                    }
                }
                if (r.movies.isNotEmpty()) {
                    item("movies") {
                        ResultRow("Movies", r.movies.size) {
                            items(r.movies, key = { it.id }) { m ->
                                PosterCard(m.name, m.poster, { nav.push(Screen.MovieDetail(m)) },
                                    Modifier.width(POSTER_WIDTH).rememberFocus(screen.focus, "m:${m.id}"), favorite = m.id in library.favoriteMovies)
                            }
                        }
                    }
                }
                if (r.series.isNotEmpty()) {
                    item("series") {
                        ResultRow("Series", r.series.size) {
                            items(r.series, key = { it.id }) { s ->
                                PosterCard(s.name, s.cover, { nav.push(Screen.SeriesDetail(s)) },
                                    Modifier.width(POSTER_WIDTH).rememberFocus(screen.focus, "s:${s.id}"), favorite = s.id in library.favoriteSeries)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, count: Int, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        SectionHeader(title, Modifier.padding(horizontal = 48.dp), if (count >= LIMIT) "Showing the first $LIMIT matches" else "$count found")
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

private const val LIMIT = 60
