package com.flixtown.tv.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun DetailsScreen(item: TvTitle, repo: FlixRepository, account: TvAccount,
                  onPlay: (TvTitle) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var episodes by remember(item.id) { mutableStateOf<Map<Int, List<TvTitle>>>(emptyMap()) }
    var selectedSeason by remember(item.id) { mutableIntStateOf(0) }
    var error by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id) {
        if (item.kind == "series") {
            try {
                episodes = repo.episodes(account, item)
                selectedSeason = episodes.keys.firstOrNull() ?: 0
            } catch (e: Exception) { error = e.message ?: "Episodes unavailable" }
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF090C16))
        .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1A2132))) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(item.backdropUrl)
                .crossfade(false).build(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                Color(0xFF090C16), Color(0xE0090C16), Color(0x24090C16)))))
            Column(Modifier.fillMaxHeight().widthIn(max = 680.dp).padding(28.dp),
                verticalArrangement = Arrangement.Center) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 35.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(item.tags, color = Color(0xFFFFB1C4), fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                Text(item.overview, color = Color(0xFFE2E6EF), fontSize = 17.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (item.streamUrl.isNotBlank()) Button(onClick = { onPlay(item) }) {
                        Text("▶  Play movie", fontSize = 18.sp)
                    }
                    Button(onClick = onBack) { Text("Back", fontSize = 18.sp) }
                }
            }
        }
        if (item.kind == "series") {
            Text("Episodes", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (episodes.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 5.dp)) {
                    items(episodes.keys.toList(), key = { it }) { season ->
                        Button(onClick = { selectedSeason = season }) {
                            Text("Season $season" + if (selectedSeason == season) "  ✓" else "")
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(episodes[selectedSeason].orEmpty(), key = { it.id }) { episode ->
                        Card(onClick = { onPlay(episode) },
                            modifier = Modifier.width(260.dp).height(150.dp),
                            scale = CardDefaults.scale(focusedScale = 1.06f)) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF252E40))) {
                                AsyncImage(model = episode.posterUrl, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Text(episode.name, color = Color.White, fontSize = 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart)
                                        .background(Color(0xCC090C16)).padding(10.dp))
                            }
                        }
                    }
                }
            } else Text(if (error.isNotBlank()) error else "Loading episodes…",
                color = Color(0xFFBDC6D8), fontSize = 16.sp)
        }
    }
}
