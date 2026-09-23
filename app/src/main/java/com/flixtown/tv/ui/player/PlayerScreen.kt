package com.flixtown.tv.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.flixtown.tv.data.TrickPlayManifest
import com.flixtown.tv.data.model.Episode
import com.flixtown.tv.data.model.SeriesDetails
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.SelectorMenu
import com.flixtown.tv.ui.components.UpNextOverlay
import com.flixtown.tv.ui.nav.ContentScreen
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtSurfaceElevated
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
private const val UP_NEXT_TRIGGER_MS = 25_000L
private const val UP_NEXT_COUNTDOWN_SECONDS = 15
private const val SCRUB_PREVIEW_IDLE_COMMIT_MS = 2_000L
// Fallback spacing between preview slots when no trick-play manifest is
// available yet (or ever) for this item — purely cosmetic in that case,
// since there are no real frames to align to.
private const val SCRUB_PREVIEW_FALLBACK_SPACING_MS = 30_000L
private const val SCRUB_PREVIEW_WINDOW_COUNT = 2

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
fun PlayerScreen(
    graph: AppGraph,
    screen: ContentScreen.Player,
    onExit: () -> Unit,
    onNextEpisode: (ContentScreen.Player) -> Unit
) {
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
    // Kept as named State objects (not just `by`-delegated locals) so the
    // seek bar and time labels — the only things that need to redraw on
    // every 500ms poll tick — can read `.value` themselves inside their own
    // narrow composable scope (see PlaybackProgressSection below) instead of
    // PlayerScreen's own top-level body reading it directly, which used to
    // put the whole screen (buttons, subtitle/audio menus, everything) in
    // the same recomposition scope as the position poll.
    val positionMsState = remember { mutableStateOf(screen.resumePositionMs) }
    val durationMsState = remember { mutableStateOf(0L) }
    var positionMs by positionMsState
    var durationMs by durationMsState
    var controlsVisible by remember { mutableStateOf(true) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableStateOf(0) }
    var seekBarHasFocus by remember { mutableStateOf(false) }
    var scrubStreak by remember { mutableStateOf(0) }
    var lastScrubDirection by remember { mutableStateOf(0) }
    var lastScrubAtMs by remember { mutableStateOf(0L) }

    // Netflix-style seek preview: separate state from the seek-bar-focused
    // scrubSeek() above (different trigger — general playback area LEFT/
    // RIGHT, not the visible seek bar gaining D-pad focus) and deliberately
    // NOT committed to the player on every press. scrubTargetMs is the
    // pending, uncommitted position; the player itself stays paused at its
    // real position until commitScrub() (OK/center or the idle timeout)
    // actually calls seekTo — cancelScrub() (BACK) then needs no seek at
    // all, since playback position never actually moved.
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableStateOf(0L) }
    var scrubWasPlaying by remember { mutableStateOf(true) }
    var previewScrubStreak by remember { mutableStateOf(0) }
    var previewScrubLastDirection by remember { mutableStateOf(0) }
    var previewScrubLastAtMs by remember { mutableStateOf(0L) }

    // Server-generated trick-play preview frames (see TrickPlayRepository /
    // SEEK-PREVIEW-FUTURE.md) — DORMANT by default. trickPlayManifestUrl()
    // returns null unless the backend has actually configured a real
    // trickplay_base_url, in which case this whole block is a no-op: no
    // manifest request is ever constructed, trickPlayManifest simply stays
    // null, and the seek overlay renders its plain timestamp-only look
    // immediately, with zero preview-related network activity. The moment a
    // real trickplay_base_url IS configured, this starts working with no
    // app changes — a single small JSON GET per item, never blocking
    // playback start.
    var trickPlayManifest by remember(screen.contentId) { mutableStateOf<TrickPlayManifest?>(null) }
    LaunchedEffect(screen.contentId) {
        trickPlayManifest = null
        val url = trickPlayManifestUrl(screen, graph.configRepository.getCached()?.trickplayBaseUrl) ?: return@LaunchedEffect
        trickPlayManifest = graph.trickPlayRepository.fetchManifest(url)
    }

    // Decoded-sprite cache for this item (see TrickPlaySpriteCache) — one
    // instance per playing item, released below on teardown. Preloading
    // (not waiting for the first LEFT/RIGHT press) is what actually makes
    // the overlay feel instant: as soon as the manifest resolves, and again
    // every time playback crosses into a new sprite's time range, this
    // quietly warms the sprite covering the CURRENT position plus the next
    // one in sequence, entirely off the UI thread and without touching
    // PlayerScreen's own recomposition scope (snapshotFlow, same zero-cost
    // pattern as the Up Next near-end check below).
    val trickPlaySpriteCache = remember(screen.contentId) { TrickPlaySpriteCache(context) }
    DisposableEffect(screen.contentId) {
        onDispose { trickPlaySpriteCache.release() }
    }
    LaunchedEffect(trickPlayManifest) {
        val manifest = trickPlayManifest ?: return@LaunchedEffect
        var lastSpriteFile: String? = null
        snapshotFlow { positionMsState.value }.collect { pos ->
            val bucket = (pos / manifest.intervalMs) * manifest.intervalMs
            val spriteFile = manifest.frames[bucket]?.spriteFile ?: return@collect
            if (spriteFile == lastSpriteFile) return@collect
            lastSpriteFile = spriteFile
            trickPlaySpriteCache.preload(manifest.spriteUrl(spriteFile))
            manifest.nextSpriteFile(spriteFile)?.let { trickPlaySpriteCache.preload(manifest.spriteUrl(it)) }
        }
    }

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

    // Entering scrub PAUSES the player rather than seeking — the actual
    // playback position never moves until commitScrub(), so cancelScrub()
    // (BACK) needs no seek at all to "return to the original position": it
    // never left. Same 10s-base/accelerating-streak feel as scrubSeek()
    // above (reuses SCRUB_STREAK_WINDOW_MS/SCRUB_MAX_STREAK), just starting
    // from SEEK_STEP_MS (10s) per the "as it does now" base increment.
    fun beginOrContinueScrub(direction: Int) {
        val now = System.currentTimeMillis()
        if (!isScrubbing) {
            isScrubbing = true
            controlsVisible = false
            scrubTargetMs = positionMs
            scrubWasPlaying = isPlaying
            if (isPlaying) player.pause()
            previewScrubStreak = 0
        } else {
            previewScrubStreak = if (direction == previewScrubLastDirection && (now - previewScrubLastAtMs) < SCRUB_STREAK_WINDOW_MS) {
                (previewScrubStreak + 1).coerceAtMost(SCRUB_MAX_STREAK)
            } else {
                0
            }
        }
        previewScrubLastDirection = direction
        previewScrubLastAtMs = now

        val raw = when (previewScrubStreak) {
            0 -> SEEK_STEP_MS
            1 -> SEEK_STEP_MS * 2
            else -> SEEK_STEP_MS * (1L shl previewScrubStreak.coerceAtMost(SCRUB_MAX_STREAK))
        }
        val increment = if (durationMs > 0) raw.coerceAtMost(durationMs / 5) else raw
        val cap = if (durationMs > 0) durationMs else Long.MAX_VALUE
        scrubTargetMs = if (direction > 0) {
            (scrubTargetMs + increment).coerceAtMost(cap)
        } else {
            (scrubTargetMs - increment).coerceAtLeast(0)
        }
    }

    fun commitScrub() {
        if (!isScrubbing) return
        player.seekTo(scrubTargetMs)
        positionMs = scrubTargetMs
        if (scrubWasPlaying) player.play()
        isScrubbing = false
    }

    fun cancelScrub() {
        if (!isScrubbing) return
        if (scrubWasPlaying) player.play()
        isScrubbing = false
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
                completed = pos >= dur * COMPLETE_THRESHOLD,
                seriesName = screen.seriesName,
                episodeTitle = screen.episodeTitle
            )
        )
    }

    // Resolved once per episode (see the LaunchedEffect below), never
    // re-searched/re-sorted on every poll tick. Movies never populate this
    // (mediaType check inside that effect), so Up Next can never appear for
    // a movie.
    var nextEpisodeInfo by remember(screen.contentId) { mutableStateOf<Pair<Int, Episode>?>(null) }

    // Set right before handing off to the next episode so the DisposableEffect
    // below (which runs afterward, on teardown) doesn't overwrite the
    // definitive "completed" Continue Watching save made in startNextEpisode()
    // with a stale in-progress one computed from the raw player position.
    val autoplayTransitionInProgress = remember(screen.contentId) { mutableStateOf(false) }

    fun startNextEpisode() {
        val (nextSeason, nextEpisode) = nextEpisodeInfo ?: return
        val streamUrl = graph.catalogRepository.buildEpisodeStreamUrl(nextEpisode) ?: return
        autoplayTransitionInProgress.value = true
        // The outgoing episode is done as far as the user's watch history is
        // concerned (whether they pressed Play Now early or the countdown
        // ran out near the real end) — mark it completed outright rather
        // than leaving it sitting in Continue Watching at ~99% forever.
        graph.continueWatchingStore.save(
            ContinueWatchingEntry(
                streamId = screen.contentId,
                mediaType = screen.mediaType,
                seriesId = screen.seriesId,
                season = screen.season,
                episode = screen.episodeNumber,
                title = screen.title,
                posterUrl = screen.posterUrl,
                positionMs = durationMsState.value,
                durationMs = durationMsState.value,
                updatedAtMillis = System.currentTimeMillis(),
                completed = true,
                seriesName = screen.seriesName,
                episodeTitle = screen.episodeTitle
            )
        )
        onNextEpisode(
            ContentScreen.Player(
                contentId = nextEpisode.id.toIntOrNull() ?: nextEpisode.id.hashCode(),
                mediaType = "episode",
                title = screen.seriesName ?: screen.title,
                posterUrl = nextEpisode.thumbnailUrl ?: screen.posterUrl,
                streamUrl = streamUrl,
                seriesId = screen.seriesId,
                season = nextSeason,
                episodeNumber = nextEpisode.episodeNumber,
                resumePositionMs = 0L,
                seriesName = screen.seriesName,
                episodeTitle = nextEpisode.title
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
            if (!autoplayTransitionInProgress.value) {
                saveProgress(player.currentPosition.coerceAtLeast(0), player.duration.let { if (it > 0) it else 0L })
            }
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

    // Runs exactly once per episode (keyed on contentId, not on any
    // position/duration state), a single get_series_info fetch plus one
    // sort/search pass over that season's episode list — never repeated on
    // every poll tick. Movies (mediaType != "episode") never reach the
    // network call at all.
    LaunchedEffect(screen.contentId) {
        nextEpisodeInfo = null
        if (screen.mediaType != "episode" || screen.seriesId == null || screen.season == null || screen.episodeNumber == null) {
            return@LaunchedEffect
        }
        val series = graph.catalogRepository.getCachedSnapshot()?.series?.firstOrNull { it.seriesId == screen.seriesId }
            ?: return@LaunchedEffect
        val details = graph.catalogRepository.getSeriesDetails(series) ?: return@LaunchedEffect
        nextEpisodeInfo = findNextEpisode(details, screen.season, screen.episodeNumber)
    }

    val autoPlayEnabled by graph.autoplaySettingsStore.autoPlayEnabled.collectAsState()
    var showUpNext by remember(screen.contentId) { mutableStateOf(false) }
    var upNextDismissedForThisEpisode by remember(screen.contentId) { mutableStateOf(false) }
    var upNextSecondsRemaining by remember(screen.contentId) { mutableStateOf(UP_NEXT_COUNTDOWN_SECONDS) }

    fun cancelUpNext() {
        showUpNext = false
        upNextDismissedForThisEpisode = true
    }

    // Zero recomposition cost: snapshotFlow reacts to positionMsState/
    // durationMsState changes inside a coroutine, not during composition, so
    // this near-end check running every 500ms poll tick never touches
    // PlayerScreen's own recomposition scope the way a direct top-level read
    // of positionMs/durationMs would.
    LaunchedEffect(screen.contentId, nextEpisodeInfo, autoPlayEnabled) {
        if (nextEpisodeInfo == null || !autoPlayEnabled) return@LaunchedEffect
        snapshotFlow { positionMsState.value to durationMsState.value }.collect { (pos, dur) ->
            if (!showUpNext && !upNextDismissedForThisEpisode && dur > 0 && (dur - pos) in 0..UP_NEXT_TRIGGER_MS) {
                showUpNext = true
            }
        }
    }

    // The countdown is its own fixed-length timer once the overlay appears
    // (not tied to real remaining playback time past that point) — reaching
    // 0 is what actually triggers the switch, matching how most streaming
    // apps auto-advance over the outgoing episode's end credits.
    LaunchedEffect(showUpNext) {
        if (!showUpNext) return@LaunchedEffect
        controlsVisible = false
        upNextSecondsRemaining = UP_NEXT_COUNTDOWN_SECONDS
        while (upNextSecondsRemaining > 0) {
            delay(1_000L)
            upNextSecondsRemaining--
        }
        startNextEpisode()
    }

    // Auto-commit after a short idle period. Keyed on (isScrubbing,
    // scrubTargetMs), so every new LEFT/RIGHT press (which changes
    // scrubTargetMs) cancels the previous instance of this effect and starts
    // a fresh one — Compose's own LaunchedEffect key-change semantics handle
    // the "cancel the old timer, start a new one" job bookkeeping, so rapid
    // repeated presses never accumulate pending commit jobs.
    LaunchedEffect(isScrubbing, scrubTargetMs) {
        if (!isScrubbing) return@LaunchedEffect
        delay(SCRUB_PREVIEW_IDLE_COMMIT_MS)
        commitScrub()
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

    // Netflix-style seek preview: Back cancels the pending seek and resumes
    // from the real (unchanged) position — see cancelScrub().
    BackHandler(enabled = isScrubbing) { cancelScrub() }

    // Composed last, so it wins over every handler above while the Up Next
    // overlay is open: Back cancels the overlay (same behavior as the
    // Cancel button) instead of hiding controls, un-scrubbing, or exiting.
    BackHandler(enabled = showUpNext) { cancelUpNext() }

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
            },
            update = { view ->
                // Push subtitles up out from under whichever bottom overlay
                // is currently showing, using Media3's own SubtitleView
                // padding API (its documented mechanism for exactly this)
                // rather than trying to reposition/clip subtitle rendering
                // ourselves. 0.08f is SubtitleView's own unpadded default.
                val bottomPaddingFraction = when {
                    isScrubbing -> 0.34f
                    controlsVisible -> 0.22f
                    else -> 0.08f
                }
                view.subtitleView?.setBottomPaddingFraction(bottomPaddingFraction)
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(surfaceFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    // Up Next owns focus/input while its own card is up —
                    // never let the general surface start a seek preview or
                    // hijack OK underneath it.
                    if (showUpNext) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { beginOrContinueScrub(-1); true }
                        Key.DirectionRight -> { beginOrContinueScrub(1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (isScrubbing) commitScrub() else controlsVisible = true
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

                PlaybackProgressSection(
                    positionMsState = positionMsState,
                    durationMsState = durationMsState,
                    seekBarFocusRequester = seekBarFocusRequester,
                    seekBarHasFocus = seekBarHasFocus,
                    onSeekBarFocusChanged = { seekBarHasFocus = it },
                    onScrubLeft = { scrubSeek(-1) },
                    onScrubRight = { scrubSeek(1) }
                )

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

        // Series-only (nextEpisodeInfo is never populated for a movie),
        // bottom-right so it never competes with the subtitle/audio menus'
        // bottom-start position. Controls are hidden the moment this shows
        // (see the LaunchedEffect(showUpNext) above), so there's no
        // possibility of it overlapping the normal controls bar either.
        val currentNextEpisodeInfo = nextEpisodeInfo
        if (showUpNext && currentNextEpisodeInfo != null) {
            val (nextSeason, nextEpisode) = currentNextEpisodeInfo
            UpNextOverlay(
                seasonNumber = nextSeason,
                episode = nextEpisode,
                secondsRemaining = upNextSecondsRemaining,
                onPlayNow = { startNextEpisode() },
                onCancel = { cancelUpNext() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 48.dp)
            )
        }

        // Anchored to the very bottom edge only (same as the normal controls
        // bar it replaces while active) — subtitles render higher up, in the
        // lower-middle of the frame, so this compact bottom strip never
        // covers them.
        if (isScrubbing) {
            SeekPreviewOverlay(
                targetMs = scrubTargetMs,
                durationMs = durationMs,
                manifest = trickPlayManifest,
                spriteCache = trickPlaySpriteCache,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * The seek bar plus its elapsed/duration time labels — the only part of the
 * controls bar that changes every [PROGRESS_POLL_MS] tick. Reading
 * `positionMsState.value`/`durationMsState.value` HERE, inside this small
 * composable's own body, rather than in [PlayerScreen]'s top-level body,
 * scopes the recomposition caused by each poll tick to just this function —
 * the buttons, subtitle/audio rows, and everything else in the controls
 * Column are passed none of this state and never recompose because of it.
 */
@Composable
private fun PlaybackProgressSection(
    positionMsState: State<Long>,
    durationMsState: State<Long>,
    seekBarFocusRequester: FocusRequester,
    seekBarHasFocus: Boolean,
    onSeekBarFocusChanged: (Boolean) -> Unit,
    onScrubLeft: () -> Unit,
    onScrubRight: () -> Unit
) {
    val positionMs = positionMsState.value
    val durationMs = durationMsState.value

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(seekBarFocusRequester)
            .onFocusChanged { state -> onSeekBarFocusChanged(state.isFocused) }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onScrubLeft(); true }
                    Key.DirectionRight -> { onScrubRight(); true }
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
}

/**
 * Netflix-style seek preview. When a server-generated trick-play [manifest]
 * exists for this item (see TrickPlayRepository / SEEK-PREVIEW-FUTURE.md —
 * frames are extracted once, offline, by a GitHub Actions job running
 * FFmpeg, never on-device) shows a filmstrip of real preview frames around
 * the selected position, cropped client-side out of whatever sprite sheet
 * [spriteCache] already has decoded (see [PreviewCard]) — usually already
 * true by the time the user first presses LEFT/RIGHT, since [PlayerScreen]
 * preloads the sprite covering the current playback position continuously,
 * not on demand. No manifest yet (title not generated), or a sprite not yet
 * loaded for a given slot, falls back per-card to the plain timestamp-only
 * look — seeking itself never depends on any of this succeeding. On-device
 * frame decoding (MediaMetadataRetriever, tried in 0.9.9) is deliberately
 * not used again: it was too slow on real TV hardware.
 */
@Composable
private fun SeekPreviewOverlay(
    targetMs: Long,
    durationMs: Long,
    manifest: TrickPlayManifest?,
    spriteCache: TrickPlaySpriteCache?,
    modifier: Modifier = Modifier
) {
    val cappedDuration = if (durationMs > 0) durationMs else Long.MAX_VALUE
    val spacingMs = manifest?.intervalMs?.takeIf { it > 0 } ?: SCRUB_PREVIEW_FALLBACK_SPACING_MS
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
            .padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            for (offset in -SCRUB_PREVIEW_WINDOW_COUNT..SCRUB_PREVIEW_WINDOW_COUNT) {
                val slotMs = (targetMs + offset * spacingMs).coerceIn(0L, cappedDuration)
                PreviewCard(slotMs = slotMs, isSelected = offset == 0, manifest = manifest, spriteCache = spriteCache)
            }
        }

        Text(
            text = "${formatTime(targetMs)} / ${formatTime(durationMs)}",
            style = MaterialTheme.typography.titleMedium,
            color = FtTextPrimary
        )

        SeekBar(
            progress = if (durationMs > 0) targetMs.toFloat() / durationMs.toFloat() else 0f,
            isFocused = true
        )
    }
}

/**
 * One card in the seek-preview filmstrip. [slotMs] is snapped to the
 * manifest's own bucket grid so it maps to a real generated frame's sprite
 * location; a slot whose sprite isn't decoded yet (or no manifest at all)
 * falls back to the timestamp-only look for that card alone, matching the
 * required real-frame -> loading -> timestamp fallback hierarchy — and
 * transitions to the real frame automatically once [TrickPlaySpriteCache.
 * getSprite] resolves, without ever blocking D-pad input. The frame itself
 * is drawn as a cropped region of the already-decoded sprite sheet via
 * [Canvas]/[androidx.compose.ui.graphics.drawscope.DrawScope.drawImage] —
 * no per-frame Bitmap allocation, no extra network request: up to all 5
 * visible cards commonly share one already-cached sprite.
 */
@Composable
private fun PreviewCard(
    slotMs: Long,
    isSelected: Boolean,
    manifest: TrickPlayManifest?,
    spriteCache: TrickPlaySpriteCache?
) {
    val frame = remember(slotMs, manifest) {
        manifest?.let { m -> m.frames[(slotMs / m.intervalMs) * m.intervalMs] }
    }
    val spriteUrl = remember(frame, manifest) {
        if (frame != null && manifest != null) manifest.spriteUrl(frame.spriteFile) else null
    }
    var sprite by remember(spriteUrl, spriteCache) {
        mutableStateOf(spriteUrl?.let { spriteCache?.cached(it) })
    }
    LaunchedEffect(spriteUrl, spriteCache) {
        if (sprite == null && spriteUrl != null && spriteCache != null) {
            sprite = spriteCache.getSprite(spriteUrl)
        }
    }
    val resolvedSprite = sprite

    Box(
        modifier = Modifier
            .width(if (isSelected) 148.dp else 108.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(FtSurfaceElevated)
            .let { m -> if (isSelected) m.border(3.dp, FtAccent, RoundedCornerShape(8.dp)) else m },
        contentAlignment = Alignment.Center
    ) {
        if (resolvedSprite != null && frame != null && manifest != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .let { m -> if (isSelected) m else m.alpha(0.7f) }
            ) {
                drawImage(
                    image = resolvedSprite,
                    srcOffset = IntOffset(frame.x, frame.y),
                    srcSize = IntSize(manifest.frameWidth, manifest.frameHeight),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            }
            // Timestamp as a small overlay pill so it stays readable over an
            // arbitrary bright/dark video frame, instead of the standalone
            // centered text used when no frame is available.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatTime(slotMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) FtAccent else FtTextSecondary
                )
            }
        } else {
            // No manifest yet, or this slot's sprite isn't decoded yet — the
            // 0.9.10 timestamp-only look, unchanged. Never an empty box.
            Text(
                text = formatTime(slotMs),
                style = if (isSelected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                color = if (isSelected) FtAccent else FtTextSecondary
            )
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

/**
 * The trick-play content id this item's preview thumbnails are published
 * under (see .github/workflows/generate-trickplay.yml, which an admin runs
 * manually per title with this exact naming scheme). Null when the item
 * can't be identified this way (e.g. a movie/episode missing the ids it
 * needs) — the caller treats that exactly like "no manifest available yet".
 */
private fun trickPlayContentId(screen: ContentScreen.Player): String? = when (screen.mediaType) {
    "movie" -> "movie_${screen.contentId}"
    "episode" -> {
        val seriesId = screen.seriesId
        val season = screen.season
        val episode = screen.episodeNumber
        if (seriesId == null || season == null || episode == null) {
            null
        } else {
            "series_${seriesId}_s%02de%02d".format(season, episode)
        }
    }
    else -> null
}

/**
 * Resolves the full manifest URL for this item, or null when the feature is
 * effectively dormant. There is deliberately NO built-in default host
 * anymore (an earlier jsDelivr/GitHub Actions proof-of-concept default was
 * removed): [configuredBaseUrl] must come from the backend
 * (RemoteConfig.trickplayBaseUrl) and be non-blank, or this returns null —
 * which is the caller's signal to skip the manifest fetch entirely, never
 * construct a request, never preload a sprite, and render the plain
 * timestamp-only seek UI immediately. The trick-play code path (manifest
 * parsing, sprite cache, sprite-crop rendering) stays fully implemented and
 * activates automatically — no app changes needed — the moment a real
 * `trickplay_base_url` is configured server-side. See SEEK-PREVIEW-FUTURE.md.
 */
private fun trickPlayManifestUrl(screen: ContentScreen.Player, configuredBaseUrl: String?): String? {
    val baseUrl = configuredBaseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val contentId = trickPlayContentId(screen) ?: return null
    return "${baseUrl.trimEnd('/')}/$contentId/manifest.json"
}

/**
 * The episode immediately after [currentEpisodeNumber] within
 * [currentSeasonNumber] — or, if that was the season's last episode, the
 * first episode of the next-numbered season that actually has one. Returns
 * null when there's nothing to play next (series finale, or a season with no
 * episodes), which is exactly when Up Next must never appear. Episodes
 * within a season are sorted defensively by number rather than trusting
 * Xtream's array order, since the API doesn't guarantee it.
 */
private fun findNextEpisode(
    details: SeriesDetails,
    currentSeasonNumber: Int,
    currentEpisodeNumber: Int
): Pair<Int, Episode>? {
    val currentSeason = details.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber } ?: return null
    val sortedEpisodes = currentSeason.episodes.sortedBy { it.episodeNumber }
    val currentIndex = sortedEpisodes.indexOfFirst { it.episodeNumber == currentEpisodeNumber }
    if (currentIndex == -1) return null

    sortedEpisodes.getOrNull(currentIndex + 1)?.let { return currentSeasonNumber to it }

    val nextSeason = details.seasons.filter { it.seasonNumber > currentSeasonNumber }.minByOrNull { it.seasonNumber }
        ?: return null
    val nextSeasonFirstEpisode = nextSeason.episodes.sortedBy { it.episodeNumber }.firstOrNull() ?: return null
    return nextSeason.seasonNumber to nextSeasonFirstEpisode
}
