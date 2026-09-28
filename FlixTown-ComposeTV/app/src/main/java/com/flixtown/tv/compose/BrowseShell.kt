package com.flixtown.tv.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest

private enum class Page(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Search("Search", Icons.Default.Search),
    Movies("Movies", Icons.Default.Movie),
    Series("Series", Icons.Default.Tv),
    Watchlist("Watchlist", Icons.Default.Star),
    Settings("Settings", Icons.Default.Settings)
}

@Composable
fun BrowseShell(catalog: BrowseCatalog, repo: FlixRepository,
    onPlay: (TvTitle) -> Unit, onDetails: (TvTitle) -> Unit,
    onRefresh: () -> Unit, onSignOut: () -> Unit) {
    var page by remember { mutableStateOf(Page.Home) }
    var expanded by remember { mutableStateOf(true) }
    val homeFocus = remember { FocusRequester() }
    val railWidth by animateDpAsState(if (expanded) 204.dp else 76.dp, label = "TV rail width")
    LaunchedEffect(Unit) { homeFocus.requestFocus() }
    Row(Modifier.fillMaxSize().background(Color(0xFF090C16))
        .padding(horizontal = 48.dp, vertical = 27.dp)) {
        Column(Modifier.width(railWidth).fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF202338), Color(0xFF111522))))
            .padding(horizontal = 10.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(if (expanded) "FLIX TOWN" else "FT", color = Color(0xFFFF6C90),
                fontSize = if (expanded) 21.sp else 19.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, modifier = Modifier.padding(start = 10.dp, bottom = 16.dp))
            Page.entries.forEach { item ->
                val selected = page == item
                Card(onClick = { page = item; expanded = true },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                        .then(if (item == Page.Home) Modifier.focusRequester(homeFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) expanded = true },
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.colors(
                        containerColor = if (selected) Color(0xFF712642) else Color.Transparent,
                        focusedContainerColor = Color(0xFFD9295A)),
                    scale = CardDefaults.scale(focusedScale = 1.03f)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(item.icon, contentDescription = null, modifier = Modifier.size(27.dp),
                            tint = Color.White)
                        if (expanded) Text(item.label, color = Color.White, fontSize = 17.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Box(Modifier.weight(1f).fillMaxHeight().onFocusChanged { if (it.hasFocus) expanded = false }) {
            when (page) {
                Page.Home -> HomeScreen(onPlay, onDetails, catalog, autoFocusHero = false,
                    includeSafePadding = false)
                Page.Movies -> CatalogPage("Movies", catalog.movies, catalog.movieCategories, onDetails)
                Page.Series -> CatalogPage("Series", catalog.series, catalog.seriesCategories, onDetails)
                Page.Search -> SearchPage(catalog.movies + catalog.series, onDetails)
                Page.Watchlist -> WatchlistPage(catalog, repo, onDetails)
                Page.Settings -> SettingsPage(onRefresh, onSignOut)
            }
        }
    }
}

@Composable
private fun CatalogPage(title: String, source: List<TvTitle>, categories: List<TvCategory>,
    onDetails: (TvTitle) -> Unit) {
    var category by remember(title) { mutableStateOf<TvCategory?>(null) }
    var sort by remember(title) { mutableStateOf("Recently added") }
    var picker by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val filtered = remember(source, category, sort) {
        val selected = if (category == null) source else source.filter { it.categoryId == category?.id }
        when (sort) {
            "Title A–Z" -> selected.sortedBy { it.name.lowercase() }
            "Top rated" -> selected.sortedByDescending { it.rating }
            else -> selected.sortedByDescending { it.added }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker = "category" }) {
                Text("Categories  •  ${category?.name ?: "All"}", fontSize = 16.sp)
            }
            Button(onClick = { picker = "sort" }) { Text("Sort by  •  $sort", fontSize = 16.sp) }
        }
        Text("${filtered.size} titles", color = Color(0xFFB7C0D0), fontSize = 15.sp)
        if (filtered.isEmpty()) Text("No titles in this category.", color = Color.White, fontSize = 18.sp)
        else LazyVerticalGrid(columns = GridCells.Fixed(4), state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth().graphicsLayer { clip = false },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(filtered, key = { it.id }) { item -> GridPoster(item) { onDetails(item) } }
        }
    }
    if (picker == "category") PickerDialog("Categories",
        listOf("All" to "") + categories.map { it.name to it.id },
        onDismiss = { picker = "" }) { id ->
        category = categories.firstOrNull { it.id == id }; picker = ""
    }
    if (picker == "sort") PickerDialog("Sort by",
        listOf("Recently added" to "recent", "Title A–Z" to "name", "Top rated" to "rated"),
        onDismiss = { picker = "" }) { id ->
        sort = when (id) { "name" -> "Title A–Z"; "rated" -> "Top rated"; else -> "Recently added" }
        picker = ""
    }
}

@Composable
private fun SearchPage(all: List<TvTitle>, onDetails: (TvTitle) -> Unit) {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val matches = remember(all, query) {
        if (query.trim().length < 2) emptyList()
        else all.filter { it.name.contains(query.trim(), ignoreCase = true) }.take(120)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Search", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        BasicTextField(query, onValueChange = { query = it }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 21.sp),
            cursorBrush = SolidColor(Color(0xFFFF668B)),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .background(Color(0xFF1D2636), RoundedCornerShape(12.dp))
                .border(if (focused) 2.dp else 1.dp,
                    if (focused) Color(0xFFFF668B) else Color(0xFF48516A), RoundedCornerShape(12.dp))
                .padding(17.dp),
            decorationBox = { inner -> Box {
                if (query.isEmpty()) Text("Search movies and series", color = Color(0xFFAFB8C8), fontSize = 21.sp)
                inner()
            } })
        Text(if (query.length < 2) "Enter at least two letters" else "${matches.size} results",
            color = Color(0xFFC4CBD8), fontSize = 16.sp)
        LazyVerticalGrid(columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f).fillMaxWidth().graphicsLayer { clip = false },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(matches, key = { it.id }) { item -> GridPoster(item) { onDetails(item) } }
        }
    }
}

@Composable
private fun WatchlistPage(catalog: BrowseCatalog, repo: FlixRepository,
    onDetails: (TvTitle) -> Unit) {
    val saved = (catalog.movies + catalog.series).filter { repo.inWatchlist(it.id) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Watchlist", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (saved.isEmpty()) Text("Titles you save will appear here.",
            color = Color(0xFFC4CBD8), fontSize = 18.sp)
        else LazyVerticalGrid(columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f).fillMaxWidth().graphicsLayer { clip = false },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(saved, key = { it.id }) { item -> GridPoster(item) { onDetails(item) } }
        }
    }
}

@Composable
private fun SettingsPage(onRefresh: () -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Button(onClick = onRefresh) { Text("Refresh movies and series", fontSize = 18.sp) }
        Button(onClick = onSignOut) { Text("Sign out", fontSize = 18.sp) }
    }
}

@Composable
private fun GridPoster(item: TvTitle, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(0.69f),
            shape = CardDefaults.shape(RoundedCornerShape(11.dp)),
            border = CardDefaults.border(focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF658D)),
                shape = RoundedCornerShape(11.dp))),
            scale = CardDefaults.scale(focusedScale = 1.06f)) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(item.posterUrl)
                .crossfade(false).build(), contentDescription = item.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    .background(Color(0xFF222B3A)))
        }
        Text(item.name, color = Color.White, fontSize = 16.sp,
            maxLines = 2, lineHeight = 19.sp, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickerDialog(title: String, options: List<Pair<String, String>>,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 500.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF171F31))
            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(options, key = { it.second }) { (label, id) ->
                    Button(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}
