package com.flixtown.tv.compose

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Player
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
    private val playing = mutableStateOf(false)
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
                it.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing.value = isPlaying
                    }
                })
                it.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                it.prepare()
                it.playWhenReady = true
            }
        player = exo
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent {
            FlixTownTheme {
                PlayerScreen(exo, title, controlsVisible.value, playing.value,
                    controlsEpoch.intValue, menuType.value,
                    onShowControls = ::showControls,
                    onHideControls = { controlsVisible.value = false },
                    onMenu = { menuType.value = it },
                    onExit = ::finish)
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
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player?.seekBack(); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player?.seekForward(); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    showControls(); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!controlsVisible.value) {
                    player?.seekBack(); showControls(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsVisible.value) {
                    player?.seekForward(); showControls(); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP ->
                    if (!controlsVisible.value) { showControls(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() { player?.pause(); super.onStop() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}

@Composable
private fun PlayerScreen(player: ExoPlayer, title: String, visible: Boolean, playing: Boolean,
    epoch: Int, menuType: Int?, onShowControls: () -> Unit,
    onHideControls: () -> Unit, onMenu: (Int?) -> Unit, onExit: () -> Unit) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(visible, epoch, playing, menuType) {
        if (visible && menuType == null) {
            playFocus.requestFocus()
            if (playing) { delay(7_000); onHideControls() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context -> PlayerView(context).apply {
            this.player = player
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        } }, modifier = Modifier.fillMaxSize())
        if (visible) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent,
                    CinemaColor.Background.copy(alpha = 0.92f), CinemaColor.Background)))
                .padding(horizontal = 48.dp, vertical = 27.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(title, color = CinemaColor.Text, fontSize = 19.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumButton(onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        onShowControls()
                    }, modifier = Modifier.focusRequester(playFocus)) {
                        Text(if (playing) "Pause" else "Play", fontSize = 16.sp)
                    }
                    PremiumButton(onClick = { player.seekBack(); onShowControls() }) {
                        Text("−10s", fontSize = 16.sp)
                    }
                    PremiumButton(onClick = { player.seekForward(); onShowControls() }) {
                        Text("+10s", fontSize = 16.sp)
                    }
                    PremiumButton(onClick = { onMenu(C.TRACK_TYPE_TEXT) }) {
                        Text("Subtitles", fontSize = 16.sp)
                    }
                    PremiumButton(onClick = { onMenu(C.TRACK_TYPE_AUDIO) }) {
                        Text("Audio", fontSize = 16.sp)
                    }
                    PremiumButton(onClick = onExit) { Text("Exit", fontSize = 16.sp) }
                }
            }
        }
    }
    if (menuType != null) {
        val type = menuType
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
