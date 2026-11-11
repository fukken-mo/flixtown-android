package com.nimertv.app.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimertv.app.PlayRequest
import com.nimertv.app.data.Catalog
import com.nimertv.app.data.Category
import com.nimertv.app.data.Channel
import com.nimertv.app.data.EpgEntry
import com.nimertv.app.data.Library
import com.nimertv.app.data.Movie
import com.nimertv.app.data.Series
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Section<T>(val categories: List<Category>, val items: List<T>)

@Composable
private fun ScreenTitle(title: String, subtitle: String, onSearch: (() -> Unit)?, searchModifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title.copy(fontWeight = FontWeight.Black))
            Text(subtitle, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onSearch != null) PillButton("Search", onSearch, searchModifier, icon = Icons.Filled.Search)
    }
}

@Composable
private fun CategoryRail(
    screen: Screen,
    fixed: List<Pair<String, String>>,
    categories: List<Category>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyColumn(
        state = screen.listState("rail"),
        modifier = Modifier.width(208.dp).fillMaxHeight(),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(fixed, key = { it.first }) { (id, name) ->
            RailItem(name, selected == id, { onSelect(id) }, Modifier.rememberFocus(screen.focus, "cat:$id"))
        }
        items(categories, key = { it.id }) { c ->
            RailItem(c.name, selected == c.id, { onSelect(c.id) }, Modifier.rememberFocus(screen.focus, "cat:${c.id}"))
        }
    }
}

/* ------------------------------------------------------------------ Live TV */

@Composable
fun LiveScreen(screen: Screen.Live, nav: Nav, catalog: Catalog, library: Library, reload: Int) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    val data by rememberLoadable(reload, retry) { Section(catalog.liveCategories(), catalog.channels()) }
    var focused by remember { mutableStateOf<Channel?>(null) }

    Row(Modifier.fillMaxSize().padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        when (val d = data) {
            Loadable.Loading -> Loading(Modifier.fillMaxSize(), "Loading channels…")
            is Loadable.Failed -> Message("Live TV is unavailable", d.message, Modifier.fillMaxSize(), "Try again") { retry++ }
            is Loadable.Ready -> {
                val favorites = library.favoriteChannels.toList()
                val recents = library.recentChannels.toList()
                val channels = remember(d.data, screen.category, favorites, recents) {
                    val all = d.data.items
                    when (screen.category) {
                        Screen.ALL -> all
                        Screen.FAVORITES -> all.associateBy { it.id }.let { m -> favorites.mapNotNull(m::get) }
                        Screen.RECENT -> all.associateBy { it.id }.let { m -> recents.mapNotNull(m::get) }
                        else -> all.filter { it.categoryId == screen.category }
                    }
                }
                val categoryName = when (screen.category) {
                    Screen.ALL -> "All channels"
                    Screen.FAVORITES -> "Favorites"
                    Screen.RECENT -> "Recently watched"
                    else -> d.data.categories.firstOrNull { it.id == screen.category }?.name.orEmpty()
                }

                CategoryRail(
                    screen,
                    listOf(Screen.ALL to "All channels", Screen.FAVORITES to "★  Favorites", Screen.RECENT to "Recently watched"),
                    d.data.categories, screen.category,
                ) { screen.category = it }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ScreenTitle("Live TV", "$categoryName  ·  ${channels.size} channels", { nav.push(Screen.Search()) },
                        Modifier.rememberFocus(screen.focus, "search"))
                    if (channels.isEmpty()) {
                        Message(
                            "Nothing here yet",
                            when (screen.category) {
                                Screen.FAVORITES -> "Hold OK on a channel to add it to your favorites."
                                Screen.RECENT -> "Channels you watch will appear here."
                                else -> "This category has no channels."
                            },
                            Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            state = screen.listState("channels:${screen.category}"),
                            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(channels, key = { _, c -> c.id }) { index, ch ->
                                ChannelRow(
                                    channel = ch,
                                    position = index + 1,
                                    favorite = ch.id in favorites,
                                    onFocused = { focused = ch },
                                    onClick = { play(context, PlayRequest.Live(channels, index)) },
                                    onLongClick = { library.toggleChannel(ch.id) },
                                    modifier = Modifier.rememberFocus(screen.focus, "ch:${ch.id}"),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))
                GuidePanel(focused, catalog, Modifier.width(280.dp).fillMaxHeight().padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    position: Int,
    favorite: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick, onLongClick = onLongClick, onFocused = onFocused,
        modifier = modifier.fillMaxWidth(), focusedScale = 1.02f,
        background = Palette.Surface.copy(alpha = 0.6f), focusedBackground = Palette.Purple.copy(alpha = 0.45f),
    ) { isFocused ->
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (channel.number > 0) channel.number else position).toString(),
                style = Type.Caption, color = if (isFocused) Color.White else Palette.TextFaint, modifier = Modifier.width(40.dp),
            )
            LogoArt(channel.logo, "", Modifier.size(width = 58.dp, height = 36.dp).background(Color(0x22FFFFFF), RoundedCornerShape(6.dp)), inset = 4.dp)
            Spacer(Modifier.width(14.dp))
            Text(channel.name, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (favorite) Text("★", color = Palette.Gold, style = Type.Label)
        }
    }
}

/** Channel details and program guide for the focused channel, when the provider supplies EPG data. */
@Composable
private fun GuidePanel(channel: Channel?, catalog: Catalog, modifier: Modifier = Modifier) {
    var guide by remember { mutableStateOf<List<EpgEntry>?>(null) }
    LaunchedEffect(channel?.id) {
        guide = null
        val id = channel?.id ?: return@LaunchedEffect
        delay(450) // wait until the focus settles before asking the provider
        guide = runCatching { catalog.epg(id) }.getOrDefault(emptyList())
    }
    Column(modifier.background(Palette.Surface.copy(alpha = 0.7f), RoundedCornerShape(16.dp)).padding(18.dp)) {
        if (channel == null) {
            Text("Select a channel to see what's on.", style = Type.Body, color = Palette.TextDim)
            return@Column
        }
        LogoArt(channel.logo, channel.name, Modifier.fillMaxWidth().height(86.dp).background(Color(0x22FFFFFF), RoundedCornerShape(12.dp)), inset = 12.dp)
        Spacer(Modifier.height(12.dp))
        Text(channel.name, style = Type.Section, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Text("PROGRAM GUIDE", style = Type.Overline)
        Spacer(Modifier.height(8.dp))
        val now = System.currentTimeMillis()
        val upcoming = guide?.filter { it.endMs > now }
        when {
            upcoming == null -> Text("Loading guide…", style = Type.Caption)
            upcoming.isEmpty() -> Text("No program guide is available for this channel.", style = Type.Caption)
            else -> upcoming.take(4).forEachIndexed { i, e ->
                val live = e.startMs <= now
                Text(
                    (if (live) "NOW  " else "") + timeRange(e),
                    style = Type.Caption.copy(fontWeight = FontWeight.Bold), color = if (live) Palette.Gold else Palette.TextDim,
                )
                Text(e.title, style = Type.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (live) {
                    Spacer(Modifier.height(5.dp))
                    ProgressBar((now - e.startMs).toFloat() / (e.endMs - e.startMs), Modifier.fillMaxWidth().height(4.dp))
                    if (e.description.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        Text(e.description, style = Type.Caption, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (i < 3) Spacer(Modifier.height(11.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("OK  Watch      Hold OK  Favorite", style = Type.Caption, color = Palette.TextFaint)
    }
}

fun timeRange(e: EpgEntry): String {
    val f = SimpleDateFormat("HH:mm", Locale.US)
    return f.format(Date(e.startMs)) + " – " + f.format(Date(e.endMs))
}

/* ------------------------------------------------------------------ Movies & Series */

@Composable
fun MoviesScreen(screen: Screen.Movies, nav: Nav, catalog: Catalog, library: Library, reload: Int) {
    var retry by remember { mutableIntStateOf(0) }
    val data by rememberLoadable(reload, retry) { Section(catalog.movieCategories(), catalog.movies()) }
    PosterBrowser(
        screen = screen, title = "Movies", noun = "movies", data = data,
        category = screen.category, onCategory = { screen.category = it },
        favorites = library.favoriteMovies.toList(),
        idOf = { it.id }, categoryOf = { it.categoryId }, nameOf = { it.name }, posterOf = { it.poster },
        onOpen = { nav.push(Screen.MovieDetail(it)) },
        onFavorite = { library.toggleMovie(it.id) },
        onSearch = { nav.push(Screen.Search()) },
        onRetry = { retry++ },
    )
}

@Composable
fun SeriesScreen(screen: Screen.SeriesList, nav: Nav, catalog: Catalog, library: Library, reload: Int) {
    var retry by remember { mutableIntStateOf(0) }
    val data by rememberLoadable(reload, retry) { Section(catalog.seriesCategories(), catalog.series()) }
    PosterBrowser(
        screen = screen, title = "Series", noun = "series", data = data,
        category = screen.category, onCategory = { screen.category = it },
        favorites = library.favoriteSeries.toList(),
        idOf = { it.id }, categoryOf = { it.categoryId }, nameOf = { it.name }, posterOf = { it.cover },
        onOpen = { nav.push(Screen.SeriesDetail(it)) },
        onFavorite = { library.toggleSeries(it.id) },
        onSearch = { nav.push(Screen.Search()) },
        onRetry = { retry++ },
    )
}

@Composable
private fun <T : Any> PosterBrowser(
    screen: Screen,
    title: String,
    noun: String,
    data: Loadable<Section<T>>,
    category: String,
    onCategory: (String) -> Unit,
    favorites: List<Int>,
    idOf: (T) -> Int,
    categoryOf: (T) -> String,
    nameOf: (T) -> String,
    posterOf: (T) -> String,
    onOpen: (T) -> Unit,
    onFavorite: (T) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        when (data) {
            Loadable.Loading -> Loading(Modifier.fillMaxSize(), "Loading $noun…")
            is Loadable.Failed -> Message("$title unavailable", data.message, Modifier.fillMaxSize(), "Try again", onRetry)
            is Loadable.Ready -> {
                val section = data.data
                val items = remember(section, category, favorites) {
                    when (category) {
                        Screen.ALL -> section.items
                        Screen.FAVORITES -> section.items.associateBy(idOf).let { m -> favorites.mapNotNull(m::get) }
                        else -> section.items.filter { categoryOf(it) == category }
                    }
                }
                val categoryName = when (category) {
                    Screen.ALL -> "All $noun"
                    Screen.FAVORITES -> "Favorites"
                    else -> section.categories.firstOrNull { it.id == category }?.name.orEmpty()
                }

                CategoryRail(screen, listOf(Screen.ALL to "All $noun", Screen.FAVORITES to "★  Favorites"), section.categories, category, onCategory)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ScreenTitle(title, "$categoryName  ·  ${items.size} titles  ·  Hold OK to favorite", onSearch, Modifier.rememberFocus(screen.focus, "search"))
                    if (items.isEmpty()) {
                        Message(
                            "Nothing here yet",
                            if (category == Screen.FAVORITES) "Hold OK on a title to add it to your favorites." else "This category is empty.",
                            Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            state = screen.gridState("grid:$category"),
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(items, key = { idOf(it) }) { item ->
                                val id = idOf(item)
                                PosterCard(
                                    title = nameOf(item),
                                    poster = posterOf(item),
                                    favorite = id in favorites,
                                    onClick = { onOpen(item) },
                                    onLongClick = { onFavorite(item) },
                                    modifier = Modifier.rememberFocus(screen.focus, "item:$id"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
