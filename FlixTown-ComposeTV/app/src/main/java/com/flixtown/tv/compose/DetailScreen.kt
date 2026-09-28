package com.flixtown.tv.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage

@Composable
fun DetailScreen(item: TvTitle, account: TvAccount, repo: FlixRepository,
    catalog: BrowseCatalog, onPlay: (TvTitle) -> Unit, onTrailer: (String) -> Unit,
    onSimilar: (TvTitle) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val playFocus = remember(item.id) { FocusRequester() }
    var info by remember(item.id) { mutableStateOf(MovieInfo()) }
    var watchlisted by remember(item.id) { mutableStateOf(repo.inWatchlist(item.id)) }
    var notice by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id) {
        playFocus.requestFocus()
        try { info = repo.movieInfo(account, item) }
        catch (_: Exception) { /* Listing metadata remains visible while offline. */ }
    }
    val candidates = remember(item.id, catalog) {
        val all = catalog.movies.filter { it.id != item.id }
        val related = all.filter { item.categoryId.isNotBlank() && it.categoryId == item.categoryId }
        (related.ifEmpty { all }).take(18)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(CinemaColor.Background)) {
        val compact = maxHeight < 600.dp
        AsyncImage(backdropRequest(item.backdropUrl), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            CinemaColor.Background, CinemaColor.Background.copy(alpha = 0.94f),
            CinemaColor.Background.copy(alpha = 0.40f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            CinemaColor.Background.copy(alpha = 0.20f), CinemaColor.Background.copy(alpha = 0.72f),
            CinemaColor.Background))))
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            Text(item.name, color = CinemaColor.Text,
                fontSize = if (compact) 29.sp else 36.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(7.dp))
            Text(listOf(info.year.ifBlank { item.year }, info.duration, info.contentRating,
                item.rating.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString("  •  "),
                color = CinemaColor.Muted, fontSize = 15.sp)
            Spacer(Modifier.height(9.dp))
            Text(info.synopsis.ifBlank { item.overview }.ifBlank { "No synopsis available." },
                color = CinemaColor.Text, fontSize = if (compact) 15.sp else 17.sp,
                maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 710.dp))
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PremiumButton(onClick = { onPlay(item) }, modifier = Modifier.focusRequester(playFocus)) {
                    Text("▶  Play", fontSize = 16.sp)
                }
                PremiumButton(onClick = {
                    if (info.trailer.isBlank()) notice = "Trailer unavailable"
                    else onTrailer(info.trailer)
                }) { Text("Trailer", fontSize = 16.sp) }
                PremiumButton(onClick = { watchlisted = repo.toggleWatchlist(item.id) }) {
                    Text(if (watchlisted) "✓  Watchlisted" else "+  Watchlist", fontSize = 16.sp)
                }
            }
            if (notice.isNotBlank()) Text(notice, color = CinemaColor.Muted, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (candidates.isNotEmpty()) {
                Text("More Like This", color = CinemaColor.Text,
                    fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                val rowState = rememberLazyListState()
                PrefetchPosters(rowState, candidates)
                LazyRow(state = rowState, modifier = Modifier.fillMaxWidth().graphicsLayer { clip = false },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(candidates, key = { it.id }) { title ->
                        Column(Modifier.width(if (compact) 103.dp else 135.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Card(onClick = { onSimilar(title) },
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.69f),
                                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                                border = CardDefaults.border(focusedBorder = Border(
                                    border = androidx.compose.foundation.BorderStroke(2.dp, CinemaColor.Accent),
                                    shape = RoundedCornerShape(10.dp))),
                                scale = CardDefaults.scale(focusedScale = 1.05f)) {
                                AsyncImage(posterRequest(title.posterUrl), contentDescription = title.name,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                                        .background(CinemaColor.Surface))
                            }
                            Text(title.name, color = CinemaColor.Text, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
