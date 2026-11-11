package com.nimertv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nimertv.app.PlayRequest
import com.nimertv.app.VodItem
import com.nimertv.app.data.Catalog
import com.nimertv.app.data.Episode
import com.nimertv.app.data.Library
import com.nimertv.app.data.Progress
import java.util.Locale

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** Full-bleed provider backdrop, dimmed so text stays readable. */
@Composable
private fun Backdrop(url: String) {
    Box(Modifier.fillMaxSize()) {
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.42f, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Palette.Background.copy(alpha = 0.97f), 0.55f to Palette.Background.copy(alpha = 0.78f), 1f to Palette.Background.copy(alpha = 0.35f)),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Palette.Background)))
    }
}

@Composable
private fun MetaLine(parts: List<String>, rating: Double) {
    val shown = parts.filter { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rating > 0) {
            Text(
                "★ " + String.format(Locale.US, "%.1f", rating),
                style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.Gold,
            )
        }
        Text(shown.joinToString("   ·   "), style = Type.Label, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Credit(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(top = 6.dp)) {
        Text(label, style = Type.Caption.copy(fontWeight = FontWeight.Bold), color = Palette.PurpleLight, modifier = Modifier.width(70.dp))
        Text(value, style = Type.Caption.copy(color = Palette.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/* ------------------------------------------------------------------ Movie */

@Composable
fun MovieDetailScreen(screen: Screen.MovieDetail, catalog: Catalog, library: Library) {
    val context = LocalContext.current
    val movie = screen.movie
    // Details are optional: the movie stays playable when the provider has no metadata for it.
    val info by rememberLoadable(movie.id) { catalog.movieInfo(movie) }
    val details = info.value
    val key = Progress.movieKey(movie.id)
    val saved = library.progress[key]?.takeIf { it.resumable }
    val favorite = movie.id in library.favoriteMovies
    val year = movie.year.ifEmpty { details?.releaseDate?.take(4).orEmpty() }

    fun start(startOver: Boolean) {
        val item = VodItem(key, Progress.MOVIE, movie.id.toString(), movie.name, year, details?.poster ?: movie.poster, details?.ext ?: movie.ext)
        play(context, PlayRequest.Vod(listOf(item), 0, startOver))
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(details?.backdrop.orEmpty().ifBlank { movie.poster })
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(details?.poster ?: movie.poster, movie.name, Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text("MOVIE", style = Type.Overline)
                Spacer(Modifier.height(4.dp))
                Text(movie.name, style = Type.Display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                MetaLine(listOf(year, details?.duration.orEmpty(), details?.genre.orEmpty()), details?.rating ?: movie.rating)
                Spacer(Modifier.height(14.dp))
                when {
                    info is Loadable.Loading -> Text("Loading details…", style = Type.Caption)
                    details?.plot.isNullOrBlank() -> Text("No description is available for this title.", style = Type.Caption)
                    else -> Text(details!!.plot, style = Type.Paragraph, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.92f))
                }
                Credit("CAST", details?.cast.orEmpty())
                Credit("DIRECTOR", details?.director.orEmpty())
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(
                        if (saved != null) "Resume from ${formatClock(saved.positionMs)}" else "Play",
                        { start(startOver = false) },
                        Modifier.rememberFocus(screen.focus, "play"), icon = Icons.Filled.PlayArrow, primary = true,
                    )
                    if (saved != null) {
                        PillButton("Start over", { start(startOver = true) }, Modifier.rememberFocus(screen.focus, "restart"), icon = Icons.Filled.Refresh)
                    }
                    PillButton(
                        if (favorite) "In favorites" else "Add to favorites",
                        { library.toggleMovie(movie.id) },
                        Modifier.rememberFocus(screen.focus, "favorite"),
                        icon = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    )
                }
                if (saved != null) {
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(saved.fraction, Modifier.width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ Series */

private fun episodeLabel(e: Episode) = "S${e.season} E${e.number}"

@Composable
fun SeriesDetailScreen(screen: Screen.SeriesDetail, catalog: Catalog, library: Library) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val info by rememberLoadable(screen.series.id, retry) { catalog.seriesInfo(screen.series) }
    val series = info.value?.series ?: screen.series
    val favorite = series.id in library.favoriteSeries

    Box(Modifier.fillMaxSize()) {
        Backdrop(series.backdrop.ifBlank { series.cover })
        Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 40.dp, top = 28.dp)) {
            val data = info.value
            val all = remember(data) { data?.episodes?.values?.flatten().orEmpty() }
            val queue = remember(all) {
                all.map { e ->
                    VodItem(
                        Progress.episodeKey(e.id), Progress.EPISODE, e.id, series.name,
                        "${episodeLabel(e)} · ${e.title}", e.image.ifBlank { series.cover }, e.ext, series.id,
                    )
                }
            }
            fun playEpisode(e: Episode, startOver: Boolean = false) {
                val index = all.indexOf(e)
                if (index >= 0) play(context, PlayRequest.Vod(queue, index, startOver))
            }

            // Where to continue: the unfinished episode, or the one after the last finished episode.
            val last = library.progress.values.filter { it.kind == Progress.EPISODE && it.seriesId == series.id }.maxByOrNull { it.updatedAt }
            val lastIndex = all.indexOfFirst { it.id == last?.id }
            val next: Episode? = when {
                all.isEmpty() -> null
                last == null || lastIndex < 0 -> all.first()
                last.finished -> all.getOrNull(lastIndex + 1) ?: all.first()
                else -> all[lastIndex]
            }
            val resuming = last != null && lastIndex >= 0 && last.resumable

            Column(Modifier.width(330.dp).fillMaxHeight().padding(bottom = 24.dp)) {
                Row {
                    Artwork(series.cover, series.name, Modifier.width(104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("SERIES", style = Type.Overline)
                        Text(series.name, style = Type.Title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        MetaLine(listOf(series.releaseDate.take(4), series.genre), series.rating)
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (series.plot.isNotBlank()) {
                    Text(series.plot, style = Type.Paragraph, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
                Credit("CAST", series.cast)
                Credit("DIRECTOR", series.director)
                Spacer(Modifier.height(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (next != null) {
                        PillButton(
                            (if (resuming) "Resume " else "Play ") + episodeLabel(next),
                            { playEpisode(next) },
                            Modifier.rememberFocus(screen.focus, "play"), icon = Icons.Filled.PlayArrow, primary = true,
                        )
                    }
                    PillButton(
                        if (favorite) "In favorites" else "Add to favorites",
                        { library.toggleSeries(series.id) },
                        Modifier.rememberFocus(screen.focus, if (next == null) "play" else "favorite"),
                        icon = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    )
                }
            }

            Spacer(Modifier.width(28.dp))

            Column(Modifier.weight(1f).fillMaxHeight()) {
                when (val state = info) {
                    Loadable.Loading -> Loading(Modifier.fillMaxSize(), "Loading episodes…")
                    is Loadable.Failed -> Message("Episodes unavailable", state.message, Modifier.fillMaxSize(), "Try again") { retry++ }
                    is Loadable.Ready -> {
                        val seasons = state.data.seasons
                        if (seasons.isEmpty()) {
                            Message("No episodes yet", "The provider has not published episodes for this series.", Modifier.fillMaxSize())
                            return@Column
                        }
                        // Open on the season that contains the episode to continue with.
                        if (screen.season !in seasons) screen.season = next?.season?.takeIf { it in seasons } ?: seasons.first()
                        val episodes = state.data.episodes[screen.season].orEmpty()

                        LazyRow(
                            state = screen.listState("seasons"),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons, key = { it }) { s ->
                                FocusSurface(
                                    onClick = { screen.season = s },
                                    modifier = Modifier.rememberFocus(screen.focus, "season:$s"),
                                    shape = RoundedCornerShape(50), focusedScale = 1.05f,
                                    background = if (s == screen.season) Palette.Purple.copy(alpha = 0.4f) else Palette.Surface,
                                    focusedBackground = Palette.Purple,
                                ) {
                                    Text(if (s == 0) "Specials" else "Season $s", style = Type.Label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                        }
                        LazyColumn(
                            state = screen.listState("episodes:${screen.season}"),
                            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(episodes, key = { _, e -> e.id }) { _, e ->
                                EpisodeRow(e, series.cover, library.progress[Progress.episodeKey(e.id)], Modifier.rememberFocus(screen.focus, "ep:${e.id}")) { playEpisode(e) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, fallbackImage: String, progress: Progress?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.02f,
        background = Palette.Surface.copy(alpha = 0.7f), focusedBackground = Palette.Purple.copy(alpha = 0.4f),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(8.dp))) {
                Artwork(e.image.ifBlank { fallbackImage }, "", Modifier.fillMaxSize())
                if (progress != null && !progress.finished && progress.positionMs > 0) {
                    ProgressBar(progress.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("E${e.number}", style = Type.Label.copy(fontWeight = FontWeight.Black), color = Palette.PurpleLight)
                    Spacer(Modifier.width(10.dp))
                    Text(e.title, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val status = when {
                        progress?.finished == true -> "Watched"
                        progress?.resumable == true -> "Resume " + formatClock(progress.positionMs)
                        else -> e.duration
                    }
                    if (status.isNotEmpty()) Text(status, style = Type.Caption, color = if (progress?.resumable == true) Palette.Gold else Palette.TextDim)
                }
                if (e.plot.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(e.plot, style = Type.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
