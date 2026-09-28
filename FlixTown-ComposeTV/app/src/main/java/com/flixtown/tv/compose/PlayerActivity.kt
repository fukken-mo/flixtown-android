package com.flixtown.tv.compose

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale

private data class TrackChoice(val label: String, val group: Tracks.Group?,
    val trackIndex: Int = -1)

class PlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "stream_url"
        const val EXTRA_TITLE = "stream_title"
    }
    private var player: ExoPlayer? = null
    private val controlsVisible = mutableStateOf(true)
    private val controlsEpoch = mutableIntStateOf(0)
    private val menuType = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank() || Uri.parse(url).scheme !in listOf("http", "https")) {
            finish(); return
        }
        val exo = ExoPlayer.Builder(this,
            DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().also {
                it.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                it.prepare()
                it.playWhenReady = true
            }
        player = exo
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent {
            FlixTownTheme {
                PlayerScreen(exo, title, controlsVisible.value,
                    controlsEpoch.intValue, menuType.value,
                    onShowControls = ::showControls,
                    onHideControls = { controlsVisible.value = false },
                    onMenu = { menuType.value = it })
            }
        }
    }

    private fun showControls() {
        controlsVisible.value = true
        controlsEpoch.intValue++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && player != null && menuType.value == null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    player?.seekBack(); showControls(); return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    player?.seekForward(); showControls(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    showControls(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    menuType.value = -1; return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() { player?.pause(); super.onStop() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}

@Composable
private fun PlayerScreen(player: ExoPlayer, title: String, visible: Boolean,
    epoch: Int, menuType: Int?, onShowControls: () -> Unit,
    onHideControls: () -> Unit, onMenu: (Int?) -> Unit) {
    LaunchedEffect(visible, epoch, menuType) {
        if (visible && menuType == null) {
            delay(3_000)
            onHideControls()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context -> PlayerView(context).apply {
            this.player = player
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        } }, modifier = Modifier.fillMaxSize())
        if (visible) {
            Text(title, color = Color.White, fontSize = 20.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = 48.dp, top = 27.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp))
            PlayerTimeline(player, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (menuType != null) {
        val type = menuType
        if (type == -1) {
            val menuFocus = remember { FocusRequester() }
            LaunchedEffect(type) { menuFocus.requestFocus() }
            Dialog(onDismissRequest = { onMenu(null); onShowControls() }) {
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()
                    .background(CinemaColor.Surface, RoundedCornerShape(16.dp))
                    .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Playback options", color = CinemaColor.Text, fontSize = 23.sp)
                    PremiumButton(onClick = { onMenu(C.TRACK_TYPE_TEXT) },
                        modifier = Modifier.fillMaxWidth().focusRequester(menuFocus)) {
                        Text("Subtitles", fontSize = 17.sp)
                    }
                    PremiumButton(onClick = { onMenu(C.TRACK_TYPE_AUDIO) },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Audio tracks", fontSize = 17.sp)
                    }
                    Text("Back to video", color = CinemaColor.Muted, fontSize = 14.sp)
                }
            }
        } else {
        val choices = remember(type, player.currentTracks) {
            buildList {
                if (type == C.TRACK_TYPE_TEXT) add(TrackChoice("Off", null))
                for (group in player.currentTracks.groups) {
                    if (group.type != type) continue
                    for (index in 0 until group.length) {
                        if (!group.isTrackSupported(index)) continue
                        val format = group.getTrackFormat(index)
                        val language = format.language?.let {
                            Locale.forLanguageTag(it).getDisplayLanguage(Locale.US)
                        }?.takeIf { it.isNotBlank() } ?: "Unknown"
                        val label = format.label?.takeIf { it.isNotBlank() } ?: language
                        add(TrackChoice(label +
                            if (group.isTrackSelected(index)) "  ✓" else "", group, index))
                    }
                }
            }
        }
        val optionFocus = remember(type) { FocusRequester() }
        LaunchedEffect(type, choices.size) {
            if (choices.isNotEmpty()) optionFocus.requestFocus()
        }
        Dialog(onDismissRequest = { onMenu(null); onShowControls() }) {
            Column(Modifier.widthIn(max = 410.dp).fillMaxWidth()
                .background(CinemaColor.Surface, RoundedCornerShape(18.dp))
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio tracks",
                    color = CinemaColor.Text, fontSize = 24.sp)
                Text("Choose with your remote", color = CinemaColor.Muted, fontSize = 15.sp)
                if (choices.isEmpty()) Text("No other tracks in this video",
                    color = CinemaColor.Muted, fontSize = 16.sp)
                else LazyColumn(modifier = Modifier.heightIn(max = 310.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(choices) { index, choice ->
                        PremiumButton(onClick = {
                            val params = player.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(type)
                            if (choice.group == null) params.setTrackTypeDisabled(type, true)
                            else {
                                params.setTrackTypeDisabled(type, false)
                                params.addOverride(TrackSelectionOverride(
                                    choice.group.mediaTrackGroup, choice.trackIndex))
                            }
                            player.trackSelectionParameters = params.build()
                            onMenu(null); onShowControls()
                        }, modifier = Modifier.fillMaxWidth().then(
                            if (index == 0) Modifier.focusRequester(optionFocus) else Modifier)) {
                            Text(choice.label, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PlayerTimeline(player: ExoPlayer, modifier: Modifier = Modifier) {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
            delay(500)
        }
    }
    val progress = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
        .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 27.dp)) {
        Canvas(Modifier.fillMaxWidth().height(13.dp)) {
            val y = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.42f), Offset(0f, y), Offset(size.width, y),
                strokeWidth = 2.dp.toPx())
            if (progress > 0f) {
                val end = Offset(size.width * progress, y)
                drawLine(Color(0x66D33244), Offset(0f, y), end, strokeWidth = 7.dp.toPx())
                drawLine(CinemaColor.Accent, Offset(0f, y), end, strokeWidth = 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(position), color = Color.White, fontSize = 14.sp)
            Text(if (duration > 0L) clock(duration) else "LIVE",
                color = Color.White, fontSize = 14.sp)
        }
    }
}

private fun clock(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000
    return if (seconds >= 3600)
        String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
