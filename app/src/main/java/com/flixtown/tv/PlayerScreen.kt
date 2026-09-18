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
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class SubtitleChoice(val label: String, val group: TrackGroup? = null, val track: Int = -1)

@Composable
fun PlayerScreen(request: PlayRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focus = remember { FocusRequester() }
    val loadControl = remember { DefaultLoadControl.Builder().setBufferDurationsMs(12_000, 40_000, 1_200, 2_500).build() }
    val exo = remember(request.url) {
        ExoPlayer.Builder(context).setLoadControl(loadControl).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
            setMediaItem(MediaItem.fromUri(request.url)); playWhenReady = true; prepare()
        }
    }

    var controls by remember { mutableStateOf(true) }
    var controlIndex by remember { mutableIntStateOf(0) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var subtitleIndex by remember { mutableIntStateOf(0) }
    var subtitleChoices by remember { mutableStateOf(listOf(SubtitleChoice("Off"))) }
    var subtitleLabel by remember { mutableStateOf("Off") }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var lastAction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekNotice by remember { mutableStateOf<String?>(null) }

    fun refreshSubtitleChoices(tracks: Tracks) {
        val found = mutableListOf(SubtitleChoice("Off"))
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val name = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() }?.uppercase()
                    ?: "Subtitle ${found.size}"
                found += SubtitleChoice(name, group.mediaTrackGroup, i)
                if (group.isTrackSelected(i)) { subtitleLabel = name; subtitleIndex = found.lastIndex }
            }
        }
        subtitleChoices = found
    }

    fun chooseSubtitle() {
        val choice = subtitleChoices.getOrNull(subtitleIndex) ?: return
        val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (choice.group == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); subtitleLabel = "Off"
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(choice.group, listOf(choice.track)))
            subtitleLabel = choice.label
        }
        exo.trackSelectionParameters = builder.build(); subtitleMenu = false; controls = true; lastAction = System.currentTimeMillis()
    }

    fun activateControl() {
        when (controlIndex) { 0 -> if (exo.isPlaying) exo.pause() else exo.play(); 1 -> { subtitleMenu = true; controls = true }; 2 -> onBack() }
        lastAction = System.currentTimeMillis()
    }

    fun seek(delta: Long) {
        val target = (exo.currentPosition + delta).coerceIn(0L, exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        exo.seekTo(target); seekNotice = if (delta < 0) "−10 seconds" else "+10 seconds"; controls = true; lastAction = System.currentTimeMillis()
    }

    DisposableEffect(exo) {
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onTracksChanged(tracks: Tracks) { refreshSubtitleChoices(tracks) }
        }
        exo.addListener(listener)
        val resumeAt = savedPosition(context, request.progressKey)
        if (resumeAt > 0L) exo.seekTo(resumeAt)
        onDispose {
            savePlaybackProgress(context, request, exo.currentPosition.coerceAtLeast(0L), exo.duration.coerceAtLeast(0L))
            exo.removeListener(listener); exo.release()
            if (oldOrientation != null) activity?.requestedOrientation = oldOrientation
        }
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (isActive) {
            position = exo.currentPosition.coerceAtLeast(0L); duration = exo.duration.coerceAtLeast(0L)
            if (seekNotice != null && System.currentTimeMillis() - lastAction > 1100L) seekNotice = null
            if (controls && !subtitleMenu && playing && System.currentTimeMillis() - lastAction > 5000L) controls = false
            delay(350)
        }
    }

    BackHandler { if (subtitleMenu) subtitleMenu = false else onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) true else {
            lastAction = System.currentTimeMillis()
            when (event.key) {
                Key.DirectionLeft, Key.MediaRewind -> { if (!subtitleMenu) seek(-10_000L); true }
                Key.DirectionRight, Key.MediaFastForward -> { if (!subtitleMenu) seek(10_000L); true }
                Key.DirectionUp -> { if (subtitleMenu) subtitleIndex = (subtitleIndex - 1).coerceAtLeast(0) else { controls = true; controlIndex = (controlIndex - 1).coerceAtLeast(0) }; true }
                Key.DirectionDown -> { if (subtitleMenu) subtitleIndex = (subtitleIndex + 1).coerceAtMost(subtitleChoices.lastIndex) else { controls = true; controlIndex = (controlIndex + 1).coerceAtMost(2) }; true }
                Key.Enter, Key.DirectionCenter -> { if (subtitleMenu) chooseSubtitle() else if (!controls) controls = true else activateControl(); true }
                Key.MediaPlayPause -> { if (exo.isPlaying) exo.pause() else exo.play(); controls = true; true }
                Key.MediaPlay -> { exo.play(); true }
                Key.MediaPause -> { exo.pause(); true }
                Key.Back -> { if (subtitleMenu) subtitleMenu = false else onBack(); true }
                else -> true
            }
        }
    }) {
        AndroidView(factory = { PlayerView(it).apply { player = exo; useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } }, update = { it.player = exo }, modifier = Modifier.fillMaxSize())
        seekNotice?.let { notice -> Box(Modifier.align(Alignment.Center).background(Color.Black.copy(.76f), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 15.dp)) { Text(notice, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black) } }

        if (controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.2f), Color.Transparent, Color.Black.copy(.92f)))))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 50.dp, vertical = 28.dp)) {
                Text(request.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { if (duration > 0L) position.toFloat() / duration else 0f }, modifier = Modifier.fillMaxWidth().height(5.dp), color = FlixRed, trackColor = Color.DarkGray)
                Row(Modifier.fillMaxWidth().padding(top = 15.dp), horizontalArrangement = Arrangement.Center) {
                    listOf(if (playing) "PAUSE" else "PLAY", "SUBTITLES: $subtitleLabel", "BACK").forEachIndexed { i, label -> PlayerAction(label, controlIndex == i) }
                }
                Text("Left/Right: skip 10 seconds   •   Up/Down: choose control   •   OK: select", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
            }
        }

        if (subtitleMenu) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.68f)))
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 54.dp).width(330.dp).background(Color(0xFF111217), RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF484B55), RoundedCornerShape(16.dp)).padding(20.dp)) {
                Text("Subtitles", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("Use Up/Down and press OK", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
                subtitleChoices.take(12).forEachIndexed { i, choice ->
                    val chosen = subtitleIndex == i
                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(if (chosen) FlixRed else Color(0xFF25272E), RoundedCornerShape(8.dp)).border(if (chosen) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp)).padding(12.dp)) { Text(choice.label, color = Color.White, fontWeight = if (chosen) FontWeight.Black else FontWeight.Normal) }
                }
                if (subtitleChoices.size == 1) Text("No embedded subtitles were found for this video.", color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun PlayerAction(label: String, focused: Boolean) {
    Box(Modifier.padding(horizontal = 7.dp).background(if (focused) FlixRed else Color(0xFF22242A), RoundedCornerShape(9.dp)).border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.DarkGray, RoundedCornerShape(9.dp)).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(label, color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}
