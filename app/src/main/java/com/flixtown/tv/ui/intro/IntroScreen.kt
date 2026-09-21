package com.flixtown.tv.ui.intro

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.flixtown.tv.ui.theme.FtBackground
import kotlinx.coroutines.delay

/** How long we'll wait for the intro to actually start playing before giving up on it. */
private const val LOAD_TIMEOUT_MS = 5000L

/**
 * Full-screen, controls-free intro video. Built to never be the thing that
 * makes startup feel slow: a stuck/slow-loading source is skipped after
 * [LOAD_TIMEOUT_MS], any playback error skips immediately, and the player is
 * released the moment this leaves composition. [onFinished] is guaranteed to
 * be called exactly once.
 */
@Composable
fun IntroScreen(videoUrl: String, onFinished: () -> Unit) {
    val context = LocalContext.current
    val hasFinished = remember { mutableStateOf(false) }
    val latestOnFinished by rememberUpdatedState(onFinished)
    fun finishOnce() {
        if (!hasFinished.value) {
            hasFinished.value = true
            latestOnFinished()
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finishOnce()
            }

            override fun onPlayerError(error: PlaybackException) {
                finishOnce()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        delay(LOAD_TIMEOUT_MS)
        val state = player.playbackState
        if (state != Player.STATE_READY && state != Player.STATE_ENDED) {
            // Never loaded in a reasonable time: don't let a broken intro block startup.
            finishOnce()
        }
    }

    BackHandler { finishOnce() }

    Box(modifier = Modifier.fillMaxSize().background(FtBackground)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    this.player = player
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            }
        )
    }
}
