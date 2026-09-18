package com.flixtown.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun IntroScreen(url: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var finished by remember { mutableStateOf(false) }
    val finish = { if (!finished) { finished = true; onFinished() } }

    LaunchedEffect(Unit) { focus.requestFocus(); if (url.isBlank()) { delay(1400); finish() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) { finish(); true } else true },
        contentAlignment = Alignment.Center
    ) {
        if (url.isBlank()) {
            Image(painterResource(R.drawable.flix_logo), "Flix Town", Modifier.width(250.dp))
        } else {
            val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); playWhenReady = true; prepare() } }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) finish() }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { finish() }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener); player.release() }
            }
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize())
        }
    }
}
