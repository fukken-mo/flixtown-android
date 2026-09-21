package com.flixtown.tv.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.AppGraph
import com.flixtown.tv.core.SafeLog
import com.flixtown.tv.data.ContinueWatchingEntry
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary
import java.util.Locale
import kotlinx.coroutines.delay

private const val TAG = "PlayerScreen"
private const val SEEK_STEP_MS = 10_000L
private const val AUTO_HIDE_DELAY_MS = 4_000L
private const val SAVE_INTERVAL_MS = 5_000L
private const val PROGRESS_POLL_MS = 500L
private const val COMPLETE_THRESHOLD = 0.92
private const val SCRUB_BASE_MIN_MS = 30_000L
private const val SCRUB_STREAK_WINDOW_MS = 900L
private const val SCRUB_MAX_STREAK = 4

private data class TrackChoice(val label: String, val groupIndex: Int, val trackIndex: Int)

/**
 * The real movie/episode player: Media3/ExoPlayer with hardware decoding
 * first and automatic decoder fallback (no user-facing "fix video" switch),
 * a custom dark/translucent control overlay (red focus only, matching the
 * rest of the app), fully remote-navigable subtitle/audio menus built on the
 * same [SelectorMenu] used for Category/Sort, and periodic Continue Watching
 * saves.
 */
@Composable
fun PlayerScreen(graph: AppGraph, screen: ContentScreen.Player, onExit: () -> Unit) {
    val context = LocalContext.current

    val player = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredTextLanguages("en", "es")
                .setSelectUndeterminedTextLanguage(false)
                .build()
            setMediaItem(MediaItem.fromUri(screen.streamUrl))
            if (screen.resumePositionMs > 0) seekTo(screen.resumePositionMs)
            playWhenReady = true
            prepare()
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var positionMs by remember { mutableStateOf(screen.resumePositionMs) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableStateOf(0) }
    var seekBarHasFocus by remember { mutableStateOf(false) }
    var scrubStreak by remember { mutableStateOf(0) }
    var lastScrubDirection by remember { mutableStateOf(0) }
    var lastScrubAtMs by remember { mutableStateOf(0L) }

    val surfaceFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val seekBarFocusRequester = remember { FocusRequester() }
    val subtitleFocusRequester = remember { FocusRequester() }
    val audioFocusRequester = remember { FocusRequester() }

    fun bump() {
        interactionTick++
    }

    // Accelerating scrub: each press starts at ~1% of the runtime (floor
    // 30s) and doubles for every consecutive same-direction press within
    // SCRUB_STREAK_WINDOW_MS, capped so one press can never jump more than a
    // fifth of the whole title. Immediate seekTo + local position update, so
    // the thumb and time label move the instant the key is pressed.
    fun scrubSeek(direction: Int) {
        val now = System.currentTimeMillis()
        scrubStreak = if (direction == lastScrubDirection && (now - lastScrubAtMs) < SCRUB_STREAK_WINDOW_MS) {
            (scrubStreak + 1).coerceAtMost(SCRUB_MAX_STREAK)
        } else {
            0
        }
        lastScrubDirection = direction
        lastScrubAtMs = now

        // 30s, then 60s, then doubling from there (120s, 240s, ...) — matches
        // "initial press ~30s, repeated ~1min, continued larger jumps".
        val raw = when (scrubStreak) {
            0 -> SCRUB_BASE_MIN_MS
            1 -> SCRUB_BASE_MIN_MS * 2
            else -> SCRUB_BASE_MIN_MS * 2 * (1L shl (scrubStreak - 1))
        }
        val increment = if (durationMs > 0) raw.coerceAtMost(durationMs / 5) else raw.coerceAtMost(10 * SCRUB_BASE_MIN_MS)
        val cap = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = if (direction > 0) {
            (positionMs + increment).coerceAtMost(cap)
        } else {
            (positionMs - increment).coerceAtLeast(0)
        }
        player.seekTo(target)
        positionMs = target
        bump()
    }

    fun saveProgress(pos: Long, dur: Long) {
        if (dur <= 0) return
        graph.continueWatchingStore.save(
            ContinueWatchingEntry(
                streamId = screen.contentId,
                mediaType = screen.mediaType,
                seriesId = screen.seriesId,
                season = screen.season,
                episode = screen.episodeNumber,
                title = screen.title,
                posterUrl = screen.posterUrl,
                positionMs = pos,
                durationMs = dur,
                updatedAtMillis = System.currentTimeMillis(),
                completed = pos >= dur * COMPLETE_THRESHOLD
            )
        )
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }

            override fun onPlayerError(error: PlaybackException) {
                SafeLog.w(TAG, "Playback error", error)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            saveProgress(player.currentPosition.coerceAtLeast(0), player.duration.let { if (it > 0) it else 0L })
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.let { if (it > 0) it else 0L }
            delay(PROGRESS_POLL_MS)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(SAVE_INTERVAL_MS)
            saveProgress(player.currentPosition.coerceAtLeast(0), player.duration.let { if (it > 0) it else 0L })
        }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            playPauseFocusRequester.requestFocus()
        } else {
            surfaceFocusRequester.requestFocus()
        }
    }

    // Never auto-hide while a track menu is open: the Subtitles/Audio
    // buttons those menus restore focus to live inside this same
    // controls-visible block, so hiding it out from under an open menu was
    // leaving onDismiss's requestFocus() with no target to land on — the
    // "stuck" focus bug.
    LaunchedEffect(controlsVisible, isPlaying, interactionTick, showSubtitleMenu, showAudioMenu) {
        if (controlsVisible && isPlaying && !showSubtitleMenu && !showAudioMenu) {
            delay(AUTO_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    val subtitleChoices = remember(tracks) {
        buildList {
            add(TrackChoice("Off", -1, -1))
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    add(TrackChoice(trackLabel(group.getTrackFormat(trackIndex), trackIndex), groupIndex, trackIndex))
                }
            }
        }
    }
    val selectedSubtitle = remember(tracks, subtitleChoices) {
        subtitleChoices.firstOrNull { choice ->
            if (choice.groupIndex == -1) {
                tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    .none { g -> (0 until g.length).any { g.isTrackSelected(it) } }
            } else {
                tracks.groups.getOrNull(choice.groupIndex)?.isTrackSelected(choice.trackIndex) == true
            }
        } ?: subtitleChoices.first()
    }

    val audioChoices = remember(tracks) {
        buildList {
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    add(TrackChoice(trackLabel(group.getTrackFormat(trackIndex), trackIndex), groupIndex, trackIndex))
                }
            }
        }
    }
    val selectedAudio = remember(tracks, audioChoices) {
        audioChoices.firstOrNull { choice -> tracks.groups.getOrNull(choice.groupIndex)?.isTrackSelected(choice.trackIndex) == true }
            ?: audioChoices.firstOrNull()
    }

    fun selectSubtitle(choice: TrackChoice) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (choice.groupIndex == -1) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = tracks.groups.getOrNull(choice.groupIndex)?.mediaTrackGroup
            if (group != null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setOverrideForType(TrackSelectionOverride(group, choice.trackIndex))
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    fun selectAudio(choice: TrackChoice) {
        val group = tracks.groups.getOrNull(choice.groupIndex)?.mediaTrackGroup ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(TrackSelectionOverride(group, choice.trackIndex))
            .build()
    }

    val episodeLabel = if (screen.mediaType == "episode" && screen.season != null && screen.episodeNumber != null) {
        "Season ${screen.season} • Episode ${screen.episodeNumber}"
    } else null

    BackHandler {
        if (controlsVisible) {
            controlsVisible = false
        } else {
            onExit()
        }
    }

    // Composed after the handler above, so while the seek bar is focused
    // this one wins: Back exits scrubbing back to the normal controls
    // instead of hiding controls or leaving the player.
    BackHandler(enabled = seekBarHasFocus) {
        playPauseFocusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(surfaceFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            val target = (positionMs - SEEK_STEP_MS).coerceAtLeast(0)
                            player.seekTo(target)
                            positionMs = target
                            true
                        }
                        Key.DirectionRight -> {
                            val cap = if (durationMs > 0) durationMs else Long.MAX_VALUE
                            val target = (positionMs + SEEK_STEP_MS).coerceAtMost(cap)
                            player.seekTo(target)
                            positionMs = target
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            controlsVisible = true
                            true
                        }
                        else -> false
                    }
                }
        )

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                    .padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(text = screen.title, style = MaterialTheme.typography.titleLarge, color = FtTextPrimary)
                if (episodeLabel != null) {
                    Text(text = episodeLabel, style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(seekBarFocusRequester)
                        .onFocusChanged { state -> seekBarHasFocus = state.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> { scrubSeek(-1); true }
                                Key.DirectionRight -> { scrubSeek(1); true }
                                else -> false
                            }
                        }
                        .padding(vertical = 8.dp)
                ) {
                    SeekBar(
                        progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f,
                        isFocused = seekBarHasFocus
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = formatTime(positionMs), style = MaterialTheme.typography.labelMedium, color = FtTextSecondary)
                    Text(text = formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = FtTextSecondary)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FlixFocusSurface(
                        onClick = {
                            player.playWhenReady = !player.playWhenReady
                            bump()
                        },
                        modifier = Modifier.focusRequester(playPauseFocusRequester)
                    ) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }

                    if (subtitleChoices.size > 1) {
                        FlixFocusSurface(
                            onClick = {
                                showSubtitleMenu = true
                                bump()
                            },
                            modifier = Modifier.focusRequester(subtitleFocusRequester)
                        ) {
                            Text("Subtitles: ${selectedSubtitle.label}")
                        }
                    }

                    if (audioChoices.isNotEmpty()) {
                        FlixFocusSurface(
                            onClick = {
                                showAudioMenu = true
                                bump()
                            },
                            modifier = Modifier.focusRequester(audioFocusRequester)
                        ) {
                            Text("Audio: ${selectedAudio?.label ?: "Default"}")
                        }
                    }
                }
            }
        }

        if (showSubtitleMenu) {
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 150.dp)) {
                SelectorMenu(
                    title = "Subtitles",
                    options = subtitleChoices,
                    selected = selectedSubtitle,
                    optionLabel = { it.label },
                    onSelect = { selectSubtitle(it) },
                    onDismiss = {
                        showSubtitleMenu = false
                        subtitleFocusRequester.requestFocus()
                    }
                )
            }
        }

        if (showAudioMenu && selectedAudio != null) {
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 220.dp, bottom = 150.dp)) {
                SelectorMenu(
                    title = "Audio",
                    options = audioChoices,
                    selected = selectedAudio,
                    optionLabel = { it.label },
                    onSelect = { selectAudio(it) },
                    onDismiss = {
                        showAudioMenu = false
                        audioFocusRequester.requestFocus()
                    }
                )
            }
        }
    }
}

/**
 * The player's scrub bar: a thin track, a red filled portion up to the
 * current position, and a thumb that grows and gains a white ring when the
 * bar itself has focus (the same red-only focus language as everywhere
 * else, just on a shape instead of a border rect).
 */
@Composable
private fun SeekBar(progress: Float, isFocused: Boolean, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0f, 1f)
    val thumbSize = if (isFocused) 18.dp else 11.dp
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(18.dp)) {
        val trackWidth = maxWidth
        val thumbOffsetX = (trackWidth - thumbSize) * fraction

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.25f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(FtAccent)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffsetX)
                .size(thumbSize)
                .clip(CircleShape)
                .background(FtAccent)
                .let { m -> if (isFocused) m.border(2.dp, Color.White, CircleShape) else m }
        )
    }
}

private fun trackLabel(format: Format, index: Int): String {
    val lang = format.language
    val displayLang = lang?.let {
        runCatching { Locale(it).displayLanguage }.getOrNull()?.takeIf { name -> name.isNotBlank() }
            ?.replaceFirstChar { c -> c.uppercase() }
    }
    return format.label ?: displayLang ?: "Track ${index + 1}"
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
