package com.nimertv.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nimertv.app.data.Channel
import com.nimertv.app.data.EpgEntry
import com.nimertv.app.data.Progress
import com.nimertv.app.data.Session
import com.nimertv.app.ui.LogoArt
import com.nimertv.app.ui.Palette
import com.nimertv.app.ui.ProgressBar
import com.nimertv.app.ui.Type
import com.nimertv.app.ui.formatClock
import com.nimertv.app.ui.timeRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private class TrackOption(val label: String, val selected: Boolean, val apply: () -> Unit)
private class TrackMenu(val title: String, val options: List<TrackOption>, val index: Int)

/**
 * Full-screen player for live channels, movies and episodes, driven entirely by the remote.
 *
 * Live:  ▲/▼ or CH+/CH- switch channel · OK shows the info bar · ◀/▶ pick an option.
 * VOD:   OK play/pause · ◀/▶ seek (hold to accelerate) · ▼ options (start over, audio, subtitles, next).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val app get() = application as NimerApp
    private var player by mutableStateOf<ExoPlayer?>(null)

    private var live: PlayRequest.Live? = null
    private var vod: PlayRequest.Vod? = null
    private var index = 0
    private var startOverPending = false

    // UI state
    private var title by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var logo by mutableStateOf("")
    private var channelNumber by mutableStateOf("")
    private var buffering by mutableStateOf(true)
    private var playing by mutableStateOf(false)
    private var positionMs by mutableLongStateOf(0)
    private var durationMs by mutableLongStateOf(0)
    private var overlay by mutableStateOf(true)
    private var optionIndex by mutableIntStateOf(-1)
    private var options by mutableStateOf(listOf<String>())
    private var menu by mutableStateOf<TrackMenu?>(null)
    private var error by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<String?>(null)
    private var guide by mutableStateOf<List<EpgEntry>>(emptyList())

    private var hideJob: Job? = null
    private var guideJob: Job? = null
    private var retryJob: Job? = null
    private var retries = 0
    private var liveFormat = 0
    private var bufferingSince = 0L
    private var lastSave = 0L
    private var resumeAt = C.TIME_UNSET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (val request = app.playRequest) {
            is PlayRequest.Live -> {
                live = request
                index = request.index.coerceIn(0, request.channels.lastIndex)
            }
            is PlayRequest.Vod -> {
                vod = request
                index = request.index.coerceIn(0, request.items.lastIndex)
                startOverPending = request.startOver
            }
            null -> Unit
        }
        if (app.client.session == null || (live == null && vod == null) || live?.channels?.isEmpty() == true || vod?.items?.isEmpty() == true) {
            // The process was restarted behind this screen; return to the app.
            finish()
            return
        }
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { PlayerUi() }
        }
        lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(500)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null && (live != null || vod != null)) {
            createPlayer()
            startCurrent(fromSavedPosition = true)
        }
    }

    override fun onStop() {
        saveProgress(force = true)
        player?.let { resumeAt = it.currentPosition }
        retryJob?.cancel()
        player?.release()
        player = null
        super.onStop()
    }

    private fun createPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("NimerTV/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                bufferingSince = if (buffering) System.currentTimeMillis() else 0
                if (state == Player.STATE_READY) {
                    retries = 0
                    error = null
                    notice = null
                }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(e: PlaybackException) = onError(e)
        })
        player = p
    }

    /* ------------------------------------------------------------ what to play */

    private fun session(): Session? = app.client.session

    private fun startCurrent(fromSavedPosition: Boolean) {
        val p = player ?: return
        val s = session() ?: return finish()
        error = null
        notice = null
        menu = null
        retryJob?.cancel()
        live?.let { request ->
            val ch = request.channels[index]
            title = ch.name
            subtitle = ""
            logo = ch.logo
            channelNumber = (if (ch.number > 0) ch.number else index + 1).toString()
            options = listOf("Audio", "Subtitles", if (ch.id in app.library?.favoriteChannels.orEmpty()) "Remove favorite" else "Add favorite")
            app.library?.addRecentChannel(ch.id)
            val formats = s.liveFormats
            val format = formats[liveFormat % formats.size]
            p.setMediaItem(mediaItem(s.liveUrl(ch.id, format), format))
            loadGuide(ch)
        }
        vod?.let { request ->
            val item = request.items[index]
            title = item.title
            subtitle = item.subtitle
            options = listOfNotNull("Start over", "Audio", "Subtitles", "Next episode".takeIf { index < request.items.lastIndex })
            val url = if (item.kind == Progress.EPISODE) s.episodeUrl(item.id, item.ext) else s.movieUrl(item.id, item.ext)
            val saved = app.library?.progress?.get(item.key)
            val start = when {
                // Returning from the background continues where playback stopped.
                fromSavedPosition && resumeAt != C.TIME_UNSET -> resumeAt
                startOverPending -> 0L
                saved != null && saved.resumable -> saved.positionMs
                else -> 0L
            }
            startOverPending = false
            resumeAt = C.TIME_UNSET
            p.setMediaItem(mediaItem(url, item.ext), start)
        }
        p.playWhenReady = true
        p.prepare()
        showOverlay()
    }

    private fun mediaItem(url: String, format: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (format.equals("m3u8", true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private fun loadGuide(ch: Channel) {
        guide = emptyList()
        guideJob?.cancel()
        guideJob = lifecycleScope.launch {
            delay(900) // skip the request while the customer is zapping
            guide = runCatching { app.catalog?.epg(ch.id) }.getOrNull().orEmpty()
        }
    }

    private fun switchChannel(delta: Int) {
        val request = live ?: return
        if (request.channels.size < 2) return
        index = (index + delta + request.channels.size) % request.channels.size
        retries = 0
        liveFormat = 0
        startCurrent(fromSavedPosition = false)
    }

    private fun playVodIndex(newIndex: Int) {
        val request = vod ?: return
        if (newIndex !in request.items.indices) return
        saveProgress(force = true)
        index = newIndex
        startCurrent(fromSavedPosition = false)
    }

    /* ------------------------------------------------------------ progress, end, errors */

    private fun tick() {
        val p = player ?: return
        positionMs = p.currentPosition.coerceAtLeast(0)
        durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        if (vod != null && p.isPlaying && System.currentTimeMillis() - lastSave > 10_000) saveProgress(force = false)
        // A live stream that stays stuck is reconnected instead of spinning forever.
        if (live != null && buffering && bufferingSince > 0 && System.currentTimeMillis() - bufferingSince > 20_000 && retryJob?.isActive != true) {
            bufferingSince = System.currentTimeMillis()
            recoverLive()
        }
    }

    private fun saveProgress(force: Boolean) {
        val request = vod ?: return
        val p = player ?: return
        val library = app.library ?: return
        val item = request.items[index]
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        if (pos <= 0 && !force) return
        if (pos < 5_000 && library.progress[item.key] == null) return
        lastSave = System.currentTimeMillis()
        library.saveProgress(
            Progress(item.key, item.kind, item.id, item.title, item.subtitle, item.poster, item.ext, item.seriesId, pos, dur, lastSave),
        )
    }

    private fun onEnded() {
        val request = vod ?: return recoverLive() // a live stream "ending" means the connection dropped
        val p = player ?: return
        val item = request.items[index]
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: p.currentPosition
        app.library?.saveProgress(
            Progress(item.key, item.kind, item.id, item.title, item.subtitle, item.poster, item.ext, item.seriesId, dur, dur, System.currentTimeMillis()),
        )
        if (index < request.items.lastIndex) {
            index++
            startCurrent(fromSavedPosition = false)
        } else {
            finish()
        }
    }

    private fun onError(e: PlaybackException) {
        if (live != null) {
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player?.seekToDefaultPosition()
                player?.prepare()
            } else {
                recoverLive()
            }
            return
        }
        saveProgress(force = true)
        resumeAt = player?.currentPosition ?: C.TIME_UNSET
        error = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The connection was lost."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This title is not available from the provider right now."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This device cannot play this video format."
            else -> "Playback stopped unexpectedly."
        } + "\nPress OK to try again or Back to return."
        showOverlay(sticky = true)
    }

    /** Reconnects a live channel with back-off, alternating between the provider's stream formats. */
    private fun recoverLive() {
        if (live == null || retryJob?.isActive == true) return
        if (retries >= MAX_LIVE_RETRIES) {
            error = "This channel is not available right now.\nPress OK to try again, or ▲ ▼ to change channel."
            notice = null
            showOverlay(sticky = true)
            return
        }
        retries++
        liveFormat++
        notice = "Reconnecting…"
        retryJob = lifecycleScope.launch {
            delay((retries * 1000L).coerceAtMost(5000))
            val p = player ?: return@launch
            val s = session() ?: return@launch
            val ch = live!!.channels[index]
            val formats = s.liveFormats
            val format = formats[liveFormat % formats.size]
            p.setMediaItem(mediaItem(s.liveUrl(ch.id, format), format))
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun retryNow() {
        retries = 0
        error = null
        if (player == null) createPlayer()
        startCurrent(fromSavedPosition = true)
    }

    /* ------------------------------------------------------------ overlay, options, tracks */

    private fun showOverlay(sticky: Boolean = false) {
        overlay = true
        hideJob?.cancel()
        if (sticky) return
        hideJob = lifecycleScope.launch {
            delay(5000)
            // Stay visible while paused, while an option is highlighted, or on an error.
            if (error == null && menu == null && (playing || live != null)) {
                overlay = false
                optionIndex = -1
            }
        }
    }

    private fun hideOverlay() {
        overlay = false
        optionIndex = -1
        hideJob?.cancel()
    }

    private fun activateOption(name: String) {
        when (name) {
            "Start over" -> {
                player?.seekTo(0)
                player?.play()
                optionIndex = -1
            }
            "Next episode" -> playVodIndex(index + 1)
            "Audio" -> openTrackMenu(C.TRACK_TYPE_AUDIO)
            "Subtitles" -> openTrackMenu(C.TRACK_TYPE_TEXT)
            "Add favorite", "Remove favorite" -> {
                val ch = live?.channels?.get(index) ?: return
                val added = app.library?.toggleChannel(ch.id) ?: return
                options = options.dropLast(1) + if (added) "Remove favorite" else "Add favorite"
            }
        }
    }

    private fun openTrackMenu(type: Int) {
        val p = player ?: return
        val list = ArrayList<TrackOption>()
        val disabled = type in p.trackSelectionParameters.disabledTrackTypes
        if (type == C.TRACK_TYPE_TEXT) {
            list.add(TrackOption("Off", disabled) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
            })
        }
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }.orEmpty()
                val label = listOfNotNull(
                    language.takeIf { it.isNotEmpty() },
                    f.label?.takeIf { it.isNotBlank() && !it.equals(language, true) },
                    "5.1".takeIf { type == C.TRACK_TYPE_AUDIO && f.channelCount == 6 },
                ).joinToString(" · ").ifEmpty { "Track ${list.size + if (type == C.TRACK_TYPE_TEXT) 0 else 1}" }
                list.add(TrackOption(label, !disabled && group.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                })
            }
        }
        val name = if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
        val nothing = list.isEmpty() || (type == C.TRACK_TYPE_TEXT && list.size == 1)
        if (nothing) {
            notice = if (type == C.TRACK_TYPE_AUDIO) "No alternative audio tracks in this stream." else "This stream has no subtitles."
            showOverlay()
            return
        }
        menu = TrackMenu(name, list, list.indexOfFirst { it.selected }.coerceAtLeast(0))
        showOverlay(sticky = true)
    }

    /* ------------------------------------------------------------ remote */

    // ComponentActivity marks this override as library-restricted; handling remote keys here is the intended use.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return handledKeys.contains(code) || super.dispatchKeyEvent(event)
        return onKey(code, event.repeatCount) || super.dispatchKeyEvent(event)
    }

    private fun onKey(code: Int, repeat: Int): Boolean {
        val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        val p = player

        menu?.let { m ->
            when {
                code == KeyEvent.KEYCODE_BACK -> if (repeat == 0) {
                    menu = null
                    showOverlay()
                }
                code == KeyEvent.KEYCODE_DPAD_UP -> menu = TrackMenu(m.title, m.options, (m.index - 1).coerceAtLeast(0))
                code == KeyEvent.KEYCODE_DPAD_DOWN -> menu = TrackMenu(m.title, m.options, (m.index + 1).coerceAtMost(m.options.lastIndex))
                confirm && repeat == 0 -> {
                    m.options[m.index].apply()
                    menu = null
                    showOverlay()
                }
            }
            return true
        }

        if (code == KeyEvent.KEYCODE_BACK) {
            if (repeat > 0) return true
            if (overlay && error == null && (optionIndex >= 0 || live != null || playing)) hideOverlay() else finish()
            return true
        }

        if (error != null) {
            when {
                confirm && repeat == 0 -> retryNow()
                live != null && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) -> switchChannel(-1)
                live != null && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_UP) -> switchChannel(1)
            }
            return true
        }

        // An option pill is highlighted: ◀ ▶ move, OK activates, ▲ leaves.
        if (optionIndex >= 0 && overlay) {
            when {
                code == KeyEvent.KEYCODE_DPAD_LEFT -> optionIndex = (optionIndex - 1).coerceAtLeast(0)
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> optionIndex = (optionIndex + 1).coerceAtMost(options.lastIndex)
                code == KeyEvent.KEYCODE_DPAD_UP -> optionIndex = -1
                code == KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                confirm -> if (repeat == 0) options.getOrNull(optionIndex)?.let(::activateOption)
                else -> return false
            }
            if (menu == null) showOverlay()
            return true
        }

        if (live != null) {
            when {
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (repeat % 4 == 0) switchChannel(-1)
                code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_MEDIA_NEXT -> if (repeat % 4 == 0) switchChannel(1)
                confirm || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_MENU -> if (repeat == 0) {
                    if (overlay) hideOverlay() else showOverlay()
                }
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    optionIndex = 0
                    showOverlay()
                }
                else -> return false
            }
            return true
        }

        p ?: return false
        when {
            confirm || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE -> if (repeat == 0) {
                if (p.isPlaying) p.pause() else p.play()
                showOverlay()
            }
            code == KeyEvent.KEYCODE_MEDIA_PLAY -> p.play()
            code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                p.pause()
                showOverlay()
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-seekStep(repeat))
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(seekStep(repeat))
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_MENU -> {
                if (overlay && code != KeyEvent.KEYCODE_DPAD_UP) optionIndex = 0
                showOverlay()
            }
            code == KeyEvent.KEYCODE_MEDIA_NEXT -> if (repeat == 0) playVodIndex(index + 1)
            code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (repeat == 0) playVodIndex(index - 1)
            else -> return false
        }
        return true
    }

    /** Holding ◀ / ▶ accelerates: 10 s steps, then 30 s, then 60 s. */
    private fun seekStep(repeat: Int): Long = when {
        repeat > 30 -> 60_000L
        repeat > 10 -> 30_000L
        else -> SEEK_STEP_MS
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable) {
            notice = "This video cannot be skipped."
            showOverlay()
            return
        }
        val max = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + deltaMs).coerceIn(0, (max - 2000).coerceAtLeast(0))
        p.seekTo(target)
        positionMs = target
        showOverlay()
    }

    /* ------------------------------------------------------------ UI */

    @Composable
    private fun PlayerUi() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            if (buffering && error == null) {
                CircularProgressIndicator(color = Palette.PurpleLight, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(46.dp))
            }

            AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut()) {
                if (live != null) LiveOverlay() else VodOverlay()
            }

            notice?.let {
                Text(
                    it, style = Type.Label, color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }

            error?.let {
                Column(
                    Modifier.align(Alignment.Center).width(460.dp).background(Color(0xE6140C24), RoundedCornerShape(18.dp)).padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Can't play this right now", style = Type.Title.copy(textAlign = TextAlign.Center))
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = Type.Body.copy(textAlign = TextAlign.Center), color = Palette.TextDim)
                }
            }

            menu?.let { TrackPanel(it) }
        }
    }

    @Composable
    private fun OptionPills() {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEachIndexed { i, name ->
                val selected = i == optionIndex
                Text(
                    name,
                    style = Type.Label.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) Color.White else Palette.TextDim,
                    modifier = Modifier
                        .background(if (selected) Palette.Purple else Color(0x33FFFFFF), RoundedCornerShape(50))
                        .border(if (selected) 2.dp else 0.dp, if (selected) Palette.PurpleLight else Color.Transparent, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun LiveOverlay() {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF207040D))))
                    .padding(start = 48.dp, end = 48.dp, top = 70.dp, bottom = 30.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoArt(logo, "", Modifier.size(width = 110.dp, height = 66.dp).background(Color(0x33FFFFFF), RoundedCornerShape(12.dp)), inset = 8.dp)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LIVE  ·  CH $channelNumber", style = Type.Overline.copy(color = Palette.Gold))
                        Text(title, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val now = System.currentTimeMillis()
                        val current = guide.firstOrNull { it.startMs <= now && now < it.endMs }
                        val next = guide.firstOrNull { it.startMs > now }
                        if (current != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("${timeRange(current)}   ${current.title}", style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(5.dp))
                            ProgressBar((now - current.startMs).toFloat() / (current.endMs - current.startMs), Modifier.width(320.dp).height(4.dp))
                        }
                        if (next != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("Next  ${timeRange(next)}   ${next.title}", style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptionPills()
                    Spacer(Modifier.weight(1f))
                    Text("▲ ▼  Change channel      ◀ ▶  Options      OK  Hide", style = Type.Caption, color = Palette.TextFaint)
                }
            }
        }
    }

    @Composable
    private fun VodOverlay() {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xE607040D), Color.Transparent)))
                    .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 60.dp),
            ) {
                Text(title, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, style = Type.Label, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF207040D))))
                    .padding(start = 48.dp, end = 48.dp, top = 70.dp, bottom = 30.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (playing) "PLAYING" else if (buffering) "LOADING" else "PAUSED", style = Type.Overline.copy(color = if (playing) Palette.PurpleLight else Palette.Gold))
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatClock(positionMs) + if (durationMs > 0) "  /  " + formatClock(durationMs) else "",
                        style = Type.Label,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
                    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Palette.Purple))
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptionPills()
                    Spacer(Modifier.weight(1f))
                    Text("OK  Play / Pause      ◀ ▶  Seek      ▼  Options", style = Type.Caption, color = Palette.TextFaint)
                }
            }
        }
    }

    @Composable
    private fun TrackPanel(m: TrackMenu) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.CenterEnd).width(320.dp).fillMaxHeight().background(Color(0xF2140C24)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(m.title.uppercase(Locale.US), style = Type.Overline)
                Spacer(Modifier.height(8.dp))
                // Keep the highlighted entry on screen for long track lists.
                val first = (m.index - 8).coerceAtLeast(0)
                m.options.drop(first).take(12).forEachIndexed { offset, option ->
                    val i = first + offset
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (i == m.index) Palette.Purple else Color.Transparent, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.label, style = Type.Label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (option.selected) Text("✓", style = Type.Label, color = Palette.Gold)
                    }
                }
            }
        }
    }

    private companion object {
        const val SEEK_STEP_MS = 10_000L
        const val MAX_LIVE_RETRIES = 6
        val handledKeys = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_SPACE,
        )
    }
}
