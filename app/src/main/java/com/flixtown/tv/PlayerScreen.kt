package com.flixtown.tv

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PlayerScreen(request: PlayRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focus = remember { FocusRequester() }
    val exo = remember(request.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(request.url))
            playWhenReady = true
            prepare()
        }
    }
    var controls by remember { mutableStateOf(true) }
    var selected by remember { mutableIntStateOf(2) }
    var captions by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var lastAction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val labels = listOf("BACK", "−10s", if (playing) "PAUSE" else "PLAY", "+10s", if (captions) "CC ON" else "CC OFF")

    fun act() {
        when (selected) {
            0 -> onBack()
            1 -> exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
            2 -> if (exo.isPlaying) exo.pause() else exo.play()
            3 -> exo.seekTo((exo.currentPosition + 10_000L).coerceAtMost(exo.duration.coerceAtLeast(0L)))
            4 -> {
                captions = !captions
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captions).build()
            }
        }
        controls = true; lastAction = System.currentTimeMillis()
    }

    DisposableEffect(exo) {
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val listener = object : Player.Listener { override fun onIsPlayingChanged(value: Boolean) { playing = value } }
        exo.addListener(listener)
        val resumeAt = savedPosition(context, request.progressKey)
        if (resumeAt > 0L) exo.seekTo(resumeAt)
        onDispose {
            savePlaybackProgress(context, request, exo.currentPosition.coerceAtLeast(0L), exo.duration.coerceAtLeast(0L))
            exo.removeListener(listener); exo.release()
            if (old != null) activity?.requestedOrientation = old
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (isActive) {
            position = exo.currentPosition.coerceAtLeast(0L); duration = exo.duration.coerceAtLeast(0L)
            if (controls && playing && System.currentTimeMillis() - lastAction > 5000L) controls = false
            delay(500)
        }
    }
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else {
            lastAction = System.currentTimeMillis()
            when (event.key) {
                Key.DirectionLeft -> { controls = true; selected = (selected - 1).coerceAtLeast(0); true }
                Key.DirectionRight -> { controls = true; selected = (selected + 1).coerceAtMost(labels.lastIndex); true }
                Key.DirectionUp, Key.DirectionDown -> { controls = true; true }
                Key.Enter, Key.DirectionCenter, Key.MediaPlayPause -> { if (!controls) controls = true else act(); true }
                Key.MediaPlay -> { exo.play(); true }
                Key.MediaPause -> { exo.pause(); true }
                Key.MediaFastForward -> { exo.seekForward(); true }
                Key.MediaRewind -> { exo.seekBack(); true }
                Key.Back -> { onBack(); true }
                else -> false
            }
        }
    }) {
        AndroidView(factory = { PlayerView(it).apply { player = exo; useController = false } }, update = { it.player = exo }, modifier = Modifier.fillMaxSize())
        if (controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.28f), Color.Transparent, Color.Black.copy(.92f)))))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 50.dp, vertical = 32.dp)) {
                Text(request.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(13.dp))
                LinearProgressIndicator(progress = { if (duration > 0L) position.toFloat() / duration else 0f }, modifier = Modifier.fillMaxWidth().height(5.dp), color = FlixRed, trackColor = Color.DarkGray)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    labels.forEachIndexed { i, label ->
                        Box(Modifier.padding(horizontal = 7.dp).background(if (selected == i) FlixRed else Color(0xFF22242A), RoundedCornerShape(9.dp)).border(if (selected == i) 3.dp else 1.dp, if (selected == i) Color.White else Color.DarkGray, RoundedCornerShape(9.dp)).padding(horizontal = 22.dp, vertical = 12.dp)) { Text(label, fontWeight = FontWeight.Black, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
