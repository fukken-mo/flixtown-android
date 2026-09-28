package com.flixtown.tv.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.tv.material3.Button
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
        catalog.rows.asSequence().flatMap { it.second.asSequence() }
            .filter { it.kind == "movie" && it.id != item.id }
            .distinctBy { it.id }.toList().let { all ->
                val related = all.filter { item.categoryId.isNotBlank() && it.categoryId == item.categoryId }
                if (related.isNotEmpty()) related.take(18) else all.take(18)
            }
    }
    Column(Modifier.fillMaxSize().background(CinemaColor.Background)
        .padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))
            .background(CinemaColor.Surface)) {
            AsyncImage(backdropRequest(item.backdropUrl), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                CinemaColor.Background, CinemaColor.Background.copy(alpha = 0.91f), Color.Transparent))))
            Column(Modifier.align(Alignment.CenterStart).padding(30.dp).widthIn(max = 670.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.name, color = Color.White, fontSize = 36.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(info.year.ifBlank { item.year }, info.duration, info.contentRating,
                    item.rating.takeIf { it > 0 }?.let { "★ $it" }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("  •  "),
                    color = CinemaColor.Muted, fontSize = 16.sp)
                Text(info.synopsis.ifBlank { item.overview }.ifBlank { "No synopsis available." },
                    color = Color(0xFFE4E7EF), fontSize = 17.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PremiumButton(onClick = { onPlay(item) }, modifier = Modifier.focusRequester(playFocus)) {
                        Text("▶  Play Now", fontSize = 18.sp)
                    }
                    PremiumButton(onClick = {
                        if (info.trailer.isBlank()) notice = "Trailer unavailable for this movie"
                        else onTrailer(info.trailer)
                    }) { Text("Trailer", fontSize = 18.sp) }
                    PremiumButton(onClick = { watchlisted = repo.toggleWatchlist(item.id) }) {
                        Text(if (watchlisted) "✓  In Watchlist" else "+  Add to Watchlist", fontSize = 18.sp)
                    }
                }
                if (notice.isNotBlank()) Text(notice, color = CinemaColor.Muted, fontSize = 15.sp)
            }
        }
        if (candidates.isNotEmpty()) {
            Text("More Like This", color = Color.White, fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold)
            val similarRow = rememberLazyListState()
            PrefetchPosters(similarRow, candidates)
            LazyRow(state = similarRow, modifier = Modifier.fillMaxWidth().graphicsLayer { clip = false },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(candidates, key = { it.id }) { title ->
                    Column(Modifier.width(142.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Card(onClick = { onSimilar(title) },
                            modifier = Modifier.width(142.dp).height(174.dp),
                            scale = CardDefaults.scale(focusedScale = 1.07f)) {
                            AsyncImage(posterRequest(title.posterUrl), contentDescription = title.name,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                                    .background(Color(0xFF242B3A)))
                        }
                        Text(title.name, color = Color.White, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
