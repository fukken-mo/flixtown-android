package com.nimertv.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nimertv.app.BuildConfig
import com.nimertv.app.NimerApp
import com.nimertv.app.PlayRequest
import com.nimertv.app.R
import com.nimertv.app.VodItem
import com.nimertv.app.data.Catalog
import com.nimertv.app.data.Channel
import com.nimertv.app.data.Library
import com.nimertv.app.data.Movie
import com.nimertv.app.data.Progress
import com.nimertv.app.data.ProgressRules
import com.nimertv.app.data.Series
import com.nimertv.app.data.UpdateLogic
import com.nimertv.app.data.UpdateState
import java.text.NumberFormat
import java.util.Locale

private class LiveFeed(val channels: List<Channel>, val art: List<String>)
private class MovieFeed(val all: List<Movie>, val recent: List<Movie>, val art: List<String>)
private class SeriesFeed(val all: List<Series>, val recent: List<Series>, val art: List<String>)

/** Movie or series picked for the Featured row from the provider's own ratings. */
private class Featured(val movie: Movie?, val series: Series?, val rating: Double)

private fun count(n: Int, noun: String) = NumberFormat.getIntegerInstance(Locale.US).format(n) + " " + noun

@Composable
fun HomeScreen(
    app: NimerApp,
    screen: Screen.Home,
    nav: Nav,
    catalog: Catalog,
    library: Library,
    reload: Int,
    onShowUpdate: () -> Unit,
) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }

    val live by rememberLoadable(reload, retry) {
        val channels = catalog.channels()
        LiveFeed(channels, channels.asSequence().map { it.logo }.filter { it.isNotBlank() }.distinct().take(8).toList())
    }
    val movies by rememberLoadable(reload, retry) {
        val all = catalog.movies()
        val recent = all.sortedByDescending { it.added }.take(RECENT_POOL)
        MovieFeed(all, recent, recent.asSequence().map { it.poster }.filter { it.isNotBlank() }.take(5).toList())
    }
    val series by rememberLoadable(reload, retry) {
        val all = catalog.series()
        val recent = all.sortedByDescending { it.lastModified }.take(RECENT_POOL)
        SeriesFeed(all, recent, recent.asSequence().map { it.cover }.filter { it.isNotBlank() }.take(5).toList())
    }

    val failure = listOf(live, movies, series).filterIsInstance<Loadable.Failed>().firstOrNull()
    val continueWatching = ProgressRules.continueWatching(library.progress.values)
    val favoriteChannels = remember(live, library.favoriteChannels.toList()) {
        val byId = live.value?.channels?.associateBy { it.id }.orEmpty()
        library.favoriteChannels.mapNotNull { byId[it] }
    }
    val featured = remember(movies, series) {
        val pool = movies.value?.recent.orEmpty().map { Featured(it, null, it.rating) } +
            series.value?.recent.orEmpty().map { Featured(null, it, it.rating) }
        pool.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(20).takeIf { it.size >= 5 }.orEmpty()
    }
    val config = app.panel?.config
    val now = System.currentTimeMillis()
    val banners = config?.announcements.orEmpty().filter { !it.popup && it.activeAt(now) }
    val updateAvailable = UpdateLogic.state(config?.update, BuildConfig.VERSION_CODE, context.packageName) != UpdateState.NONE

    LazyColumn(
        state = screen.listState("home"),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item("top") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.nimer_logo), "Nimer TV", Modifier.size(46.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("NIMER TV", style = Type.Section.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp))
                    Text("Live TV · Movies · Series", style = Type.Caption)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (updateAvailable) PillButton("Update available", onShowUpdate, Modifier.rememberFocus(screen.focus, "update"), primary = true)
                    PillButton("Search", { nav.push(Screen.Search()) }, Modifier.rememberFocus(screen.focus, "search"), icon = Icons.Filled.Search)
                    PillButton("Account", { nav.push(Screen.Account()) }, Modifier.rememberFocus(screen.focus, "account"), icon = Icons.Filled.AccountCircle)
                }
            }
        }

        if (banners.isNotEmpty()) {
            item("banners") {
                Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    banners.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (a.priority) Palette.Gold.copy(alpha = 0.16f) else Palette.Purple.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("NOTICE", style = Type.Overline.copy(color = if (a.priority) Palette.Gold else Palette.PurpleLight))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                if (a.title.isNotEmpty()) Text(a.title, style = Type.Label.copy(fontWeight = FontWeight.Bold))
                                if (a.message.isNotEmpty()) Text(a.message, style = Type.Caption.copy(color = Palette.Text), maxLines = 3)
                            }
                        }
                    }
                }
            }
        }

        item("hero") {
            Row(Modifier.fillMaxWidth().height(196.dp).padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HeroCard(
                    title = "LIVE TV",
                    caption = live.value?.let { count(it.channels.size, "channels") } ?: statusText(live),
                    art = live.value?.art.orEmpty(), logos = true,
                    onClick = { nav.push(Screen.Live()) },
                    modifier = Modifier.weight(1f).rememberFocus(screen.focus, "hero:live"),
                )
                HeroCard(
                    title = "MOVIES",
                    caption = movies.value?.let { count(it.all.size, "movies") } ?: statusText(movies),
                    art = movies.value?.art.orEmpty(), logos = false,
                    onClick = { nav.push(Screen.Movies()) },
                    modifier = Modifier.weight(1f).rememberFocus(screen.focus, "hero:movies"),
                )
                HeroCard(
                    title = "SERIES",
                    caption = series.value?.let { count(it.all.size, "series") } ?: statusText(series),
                    art = series.value?.art.orEmpty(), logos = false,
                    onClick = { nav.push(Screen.SeriesList()) },
                    modifier = Modifier.weight(1f).rememberFocus(screen.focus, "hero:series"),
                )
            }
        }

        if (failure != null) {
            item("failure") {
                Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(failure.message, style = Type.Body, color = Palette.Danger, modifier = Modifier.weight(1f))
                    PillButton("Try again", { retry++ }, Modifier.rememberFocus(screen.focus, "retry"), icon = Icons.Filled.Refresh)
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item("continue") {
                HomeRow("Continue Watching", "Hold OK to remove") {
                    items(continueWatching, key = { it.key }) { p ->
                        PosterCard(
                            title = p.title,
                            poster = p.poster,
                            badge = p.subtitle.takeIf { it.isNotEmpty() && p.kind == Progress.EPISODE },
                            progress = p.fraction,
                            onClick = {
                                play(context, PlayRequest.Vod(listOf(VodItem(p.key, p.kind, p.id, p.title, p.subtitle, p.poster, p.ext, p.seriesId)), 0, startOver = false))
                            },
                            onLongClick = { library.clearProgress(p.key) },
                            modifier = Modifier.width(POSTER_WIDTH).rememberFocus(screen.focus, "cw:${p.key}"),
                        )
                    }
                }
            }
        }

        if (favoriteChannels.isNotEmpty()) {
            item("favorites") {
                HomeRow("Favorite Channels") {
                    items(favoriteChannels, key = { it.id }) { ch ->
                        ChannelTile(ch, Modifier.rememberFocus(screen.focus, "fav:${ch.id}")) {
                            play(context, PlayRequest.Live(favoriteChannels, favoriteChannels.indexOf(ch)))
                        }
                    }
                }
            }
        }

        if (featured.isNotEmpty()) {
            item("featured") {
                HomeRow("Featured", "Top rated among new arrivals") {
                    items(featured, key = { if (it.movie != null) "m${it.movie.id}" else "s${it.series!!.id}" }) { f ->
                        val badge = "★ " + String.format(Locale.US, "%.1f", f.rating)
                        if (f.movie != null) {
                            PosterCard(f.movie.name, f.movie.poster, { nav.push(Screen.MovieDetail(f.movie)) },
                                Modifier.width(POSTER_WIDTH).rememberFocus(screen.focus, "feat:m${f.movie.id}"), badge = badge)
                        } else if (f.series != null) {
                            PosterCard(f.series.name, f.series.cover, { nav.push(Screen.SeriesDetail(f.series)) },
                                Modifier.width(POSTER_WIDTH).rememberFocus(screen.focus, "feat:s${f.series.id}"), badge = badge)
                        }
                    }
                }
            }
        }

        movies.value?.recent?.take(ROW_SIZE)?.takeIf { it.isNotEmpty() }?.let { list ->
            item("recentMovies") {
                HomeRow("Recently Added Movies") {
                    items(list, key = { it.id }) { m ->
                        PosterCard(m.name, m.poster, { nav.push(Screen.MovieDetail(m)) },
                            Modifier.width(POSTER_WIDTH).rememberFocus(screen.focus, "rm:${m.id}"))
                    }
                }
            }
        }

        series.value?.recent?.take(ROW_SIZE)?.takeIf { it.isNotEmpty() }?.let { list ->
            item("recentSeries") {
                HomeRow("Recently Added Series") {
                    items(list, key = { it.id }) { s ->
                        PosterCard(s.name, s.cover, { nav.push(Screen.SeriesDetail(s)) },
                            Modifier.width(POSTER_WIDTH).rememberFocus(screen.focus, "rs:${s.id}"))
                    }
                }
            }
        }
    }
}

private fun statusText(l: Loadable<*>) = when (l) {
    is Loadable.Failed -> "Unavailable"
    else -> "Loading…"
}

@Composable
private fun HomeRow(title: String, hint: String? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        SectionHeader(title, Modifier.padding(horizontal = 48.dp), hint)
        Spacer(Modifier.height(4.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** One of the three main destinations: real provider artwork under a cinematic purple wash. */
@Composable
private fun HeroCard(title: String, caption: String, art: List<String>, logos: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(20.dp), focusedScale = 1.05f) { focused ->
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.PurpleDeep, Palette.BackgroundPurple))))
        if (art.isNotEmpty()) {
            Row(Modifier.fillMaxSize().alpha(if (focused) 0.85f else 0.5f), horizontalArrangement = Arrangement.spacedBy(if (logos) 10.dp else 0.dp)) {
                art.take(if (logos) 4 else 5).forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = if (logos) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = if (logos) 44.dp else 0.dp),
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Palette.Background.copy(alpha = 0.25f),
                    0.45f to Palette.BackgroundPurple.copy(alpha = if (focused) 0.35f else 0.6f),
                    1f to Palette.Background.copy(alpha = 0.96f),
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(if (focused) "PRESS OK TO OPEN" else "BROWSE", style = Type.Overline.copy(color = if (focused) Palette.Gold else Palette.PurpleLight))
            Spacer(Modifier.height(2.dp))
            Text(title, style = Type.Display, color = Color.White)
            Text(caption, style = Type.Label, color = Palette.TextDim)
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                .background(Brush.horizontalGradient(listOf(Palette.Purple, if (focused) Palette.PurpleLight else Color.Transparent))),
        )
    }
}

/** 16:9 channel tile used on the home screen and in search results. */
@Composable
fun ChannelTile(channel: Channel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(150.dp)) {
        FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().height(84.dp)) {
            LogoArt(channel.logo, channel.name, Modifier.fillMaxSize(), inset = 14.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(channel.name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

private const val RECENT_POOL = 300
private const val ROW_SIZE = 24
val POSTER_WIDTH = 118.dp
