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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun DetailsScreen(item: TvTitle, repo: FlixRepository, account: TvAccount,
                  onPlay: (TvTitle) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var episodes by remember(item.id) { mutableStateOf<Map<Int, List<TvTitle>>>(emptyMap()) }
    var selectedSeason by remember(item.id) { mutableIntStateOf(0) }
    var seasonPickerOpen by remember(item.id) { mutableStateOf(false) }
    val playFocus = remember(item.id) { FocusRequester() }
    var error by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id) {
        playFocus.requestFocus()
        if (item.kind == "series") {
            try {
                episodes = repo.episodes(account, item)
                selectedSeason = episodes.keys.firstOrNull() ?: 0
            } catch (e: Exception) { error = e.message ?: "Episodes unavailable" }
        }
    }
    Column(Modifier.fillMaxSize().background(CinemaColor.Background)
        .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))
            .background(CinemaColor.Surface)) {
            AsyncImage(model = backdropRequest(item.backdropUrl), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                CinemaColor.Background, CinemaColor.Background.copy(alpha = 0.88f), Color.Transparent))))
            Column(Modifier.fillMaxHeight().widthIn(max = 680.dp).padding(28.dp),
                verticalArrangement = Arrangement.Center) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 35.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(item.tags, color = CinemaColor.Muted, fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                Text(item.overview, color = Color(0xFFE2E6EF), fontSize = 17.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (item.streamUrl.isNotBlank()) PremiumButton(onClick = { onPlay(item) },
                        modifier = Modifier.focusRequester(playFocus)) {
                        Text("▶  Play movie", fontSize = 18.sp)
                    }
                    PremiumButton(onClick = onBack,
                        modifier = if (item.streamUrl.isBlank()) Modifier.focusRequester(playFocus)
                        else Modifier) { Text("Back", fontSize = 18.sp) }
                }
            }
        }
        if (item.kind == "series") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Episodes", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (episodes.isNotEmpty()) PremiumButton(onClick = { seasonPickerOpen = true }) {
                    Text("Season $selectedSeason  ▾", fontSize = 16.sp)
                }
            }
            if (episodes.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(episodes[selectedSeason].orEmpty(), key = { it.id }) { episode ->
                        Card(onClick = { onPlay(episode) },
                            modifier = Modifier.width(260.dp).height(150.dp),
                            border = CardDefaults.border(focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(2.dp, CinemaColor.Accent),
                                shape = RoundedCornerShape(12.dp))),
                            scale = CardDefaults.scale(focusedScale = 1.06f)) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF252E40))) {
                                AsyncImage(model = backdropRequest(episode.posterUrl), contentDescription = null,
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
    if (seasonPickerOpen) {
        val firstSeasonFocus = remember(item.id) { FocusRequester() }
        LaunchedEffect(seasonPickerOpen) { firstSeasonFocus.requestFocus() }
        Dialog(onDismissRequest = { seasonPickerOpen = false }) {
            Column(Modifier.width(330.dp)
                .background(CinemaColor.Surface, RoundedCornerShape(14.dp))
                .padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select season", color = Color.White, fontSize = 23.sp,
                    fontWeight = FontWeight.Bold)
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(episodes.keys.toList(), key = { it }) { season ->
                        PremiumButton(onClick = {
                            selectedSeason = season
                            seasonPickerOpen = false
                        }, modifier = Modifier.fillMaxWidth().then(
                            if (season == episodes.keys.first()) Modifier.focusRequester(firstSeasonFocus)
                            else Modifier)) {
                            Text("Season $season" + if (season == selectedSeason) "  ✓" else "",
                                fontSize = 17.sp)
                        }
                    }
                }
            }
        }
    }
}
