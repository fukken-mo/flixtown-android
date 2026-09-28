package com.flixtown.tv.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun posterRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return androidx.compose.runtime.remember(context, url) {
        ImageRequest.Builder(context).data(url).size(260, 390).crossfade(true).build()
    }
}

@Composable
fun backdropRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return androidx.compose.runtime.remember(context, url) {
        ImageRequest.Builder(context).data(url).size(1280, 720).crossfade(true).build()
    }
}

// Compose foundation in this project has no TvLazyRow beyondBoundsItemCount argument.
// Queue only the next four small poster decodes as focus approaches the row edge.
@Composable
fun PrefetchPosters(state: LazyListState, titles: List<TvTitle>) {
    val context = LocalContext.current
    LaunchedEffect(state, titles) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.distinctUntilChanged().collectLatest { lastVisible ->
            if (lastVisible < 0) return@collectLatest
            (lastVisible + 1..minOf(lastVisible + 4, titles.lastIndex)).forEach { index ->
                val url = titles[index].posterUrl
                if (url.isNotBlank()) context.imageLoader.enqueue(
                    ImageRequest.Builder(context).data(url).size(260, 390).crossfade(true).build())
            }
        }
    }
}
