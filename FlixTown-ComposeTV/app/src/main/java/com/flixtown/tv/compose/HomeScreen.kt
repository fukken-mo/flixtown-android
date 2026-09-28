package com.flixtown.tv.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

data class TvTitle(
    val id: String,
    val name: String,
    val year: String = "",
    val overview: String = "",
    val tags: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val streamUrl: String = "",
    val kind: String = "movie", val added: Long = 0, val rating: Double = 0.0,
    val categoryId: String = ""
)

// Replace this empty catalog with the authenticated panel API response.
data class TvCategory(val id: String, val name: String)
data class BrowseCatalog(
    val featured: TvTitle? = null,
    val rows: List<Pair<String, List<TvTitle>>> = emptyList(),
    val movies: List<TvTitle> = emptyList(),
    val series: List<TvTitle> = emptyList(),
    val movieCategories: List<TvCategory> = emptyList(),
    val seriesCategories: List<TvCategory> = emptyList()
)

@Composable
fun HomeScreen(onPlay: (TvTitle) -> Unit, onDetails: (TvTitle) -> Unit,
    catalog: BrowseCatalog = BrowseCatalog(), autoFocusHero: Boolean = true,
    includeSafePadding: Boolean = true) {
    val featured = catalog.featured
    val heroPlayFocus = remember { FocusRequester() }
    val rows = catalog.rows.filter { it.second.isNotEmpty() }
    LaunchedEffect(featured?.id) {
        if (autoFocusHero && featured != null) heroPlayFocus.requestFocus()
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        CinemaColor.Surface, CinemaColor.Background, CinemaColor.Background)))
        .then(if (includeSafePadding) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().graphicsLayer { clip = false },
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            item(key = "hero") {
                Hero(featured, heroPlayFocus, onPlay, onDetails)
            }
            items(rows, key = { it.first }) { (heading, titles) ->
                val rowState = rememberLazyListState()
                PrefetchPosters(rowState, titles)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(heading, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.fillMaxWidth().graphicsLayer { clip = false },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        items(titles, key = { it.id }) { item -> PosterCard(item) { onDetails(item) } }
                    }
                }
            }
            if (featured == null && rows.isEmpty()) {
                item { Text("Your movies and series will appear here after sign in.",
                    color = Color(0xFFB8C1D4), fontSize = 20.sp) }
            }
        }
    }
}

@Composable
private fun Hero(item: TvTitle?, playFocus: FocusRequester,
    onPlay: (TvTitle) -> Unit, onDetails: (TvTitle) -> Unit) {
    Box(Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(20.dp))
        .background(CinemaColor.Surface)) {
        if (item != null) {
            AsyncImage(
                model = backdropRequest(item.backdropUrl),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            CinemaColor.Background, CinemaColor.Background.copy(alpha = 0.78f), Color.Transparent
        ))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Transparent, Color.Transparent, CinemaColor.Background.copy(alpha = 0.94f)
        ))))
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 34.dp).widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("FLIX TOWN  /  FEATURED", color = CinemaColor.Accent, fontSize = 14.sp,
                fontWeight = FontWeight.Bold)
            Text(item?.name ?: "Flix Town", color = Color.White, fontSize = 38.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!item?.tags.isNullOrBlank()) Text(item!!.tags, color = CinemaColor.Muted, fontSize = 16.sp)
            if (!item?.overview.isNullOrBlank()) Text(item!!.overview, color = Color(0xFFE0E3EB),
                fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (item.streamUrl.isNotBlank()) {
                        PremiumButton(onClick = { onPlay(item) }, modifier = Modifier.focusRequester(playFocus)) {
                            Text("▶  Play", fontSize = 18.sp)
                        }
                    }
                    PremiumButton(onClick = { onDetails(item) },
                        modifier = if (item.streamUrl.isBlank()) Modifier.focusRequester(playFocus) else Modifier) {
                        Text("Details", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(item: TvTitle, onClick: () -> Unit) {
    Column(Modifier.width(174.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.width(174.dp).height(252.dp),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
            border = CardDefaults.border(
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, CinemaColor.Accent),
                    shape = RoundedCornerShape(12.dp)
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1.07f)
        ) {
            AsyncImage(
                model = posterRequest(item.posterUrl),
                contentDescription = item.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(Color(0xFF252B39))
            )
        }
        Text(item.name, color = Color.White, fontSize = 16.sp, maxLines = 2,
            lineHeight = 20.sp, overflow = TextOverflow.Ellipsis)
    }
}
