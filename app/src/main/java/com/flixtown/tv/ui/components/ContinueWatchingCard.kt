package com.flixtown.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.flixtown.tv.data.ContinueWatchingEntry
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

val CONTINUE_WATCHING_CARD_WIDTH = 320.dp
val CONTINUE_WATCHING_CARD_HEIGHT = 180.dp

/** What actually gets shown — resolved once from either structured data or the raw-title parser below. */
data class ContinueWatchingDisplay(val primary: String, val secondary: String?)

/**
 * A dedicated 16:9 resume card — never the tall 2:3 [PosterCard] shape, and
 * never the raw Xtream episode title verbatim. Same [FlixFocusSurface] focus
 * language as every other card (scale/lift/border/glow), just a different
 * silhouette and its own bottom-gradient text overlay + thin progress bar.
 */
@Composable
fun ContinueWatchingCard(
    entry: ContinueWatchingEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val display = remember(entry) { continueWatchingDisplay(entry) }
    val progress = remember(entry) {
        if (entry.durationMs > 0) (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }

    FlixFocusSurface(
        onClick = onClick,
        modifier = modifier.width(CONTINUE_WATCHING_CARD_WIDTH).height(CONTINUE_WATCHING_CARD_HEIGHT),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!entry.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.posterUrl,
                    contentDescription = display.primary,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(FtSurfaceElevated))
            }

            // Bottom ~40% darkens for the text overlay, top stays clear so the
            // thumbnail/backdrop actually reads as the card's main content.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to androidx.compose.ui.graphics.Color.Transparent,
                            0.6f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to FtBackground.copy(alpha = 0.92f)
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = display.primary,
                    style = MaterialTheme.typography.titleMedium,
                    color = FtTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (display.secondary != null) {
                    Text(
                        text = display.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        color = FtTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (progress > 0.02f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(FtBackground.copy(alpha = 0.5f))
                ) {
                    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(FtAccent))
                }
            }
        }
    }
}

fun continueWatchingDisplay(entry: ContinueWatchingEntry): ContinueWatchingDisplay {
    if (entry.mediaType != "episode") {
        val percent = if (entry.durationMs > 0) {
            (entry.positionMs * 100 / entry.durationMs).coerceIn(0, 100)
        } else null
        return ContinueWatchingDisplay(entry.title, percent?.let { "$it% watched" })
    }

    val parsed = if (entry.seriesName == null || entry.episodeTitle == null) parseRawEpisodeTitle(entry.title) else null
    val seriesName = entry.seriesName?.takeIf { it.isNotBlank() } ?: parsed?.seriesName ?: entry.title
    val episodeTitle = entry.episodeTitle?.takeIf { it.isNotBlank() } ?: parsed?.episodeTitle

    val seasonEpisode = listOfNotNull(
        entry.season?.let { "S%02d".format(it) },
        entry.episode?.let { "E%02d".format(it) }
    ).joinToString("")

    val secondary = listOfNotNull(seasonEpisode.takeIf { it.isNotBlank() }, episodeTitle)
        .joinToString(" • ")
        .takeIf { it.isNotBlank() }

    return ContinueWatchingDisplay(seriesName, secondary)
}

private data class ParsedEpisodeTitle(val seriesName: String?, val episodeTitle: String?)

private val SEASON_EPISODE_CODE = Regex("""^S\d{1,2}E\d{1,3}$""", RegexOption.IGNORE_CASE)

/**
 * Fallback only — used when an entry predates [ContinueWatchingEntry.seriesName]
 * /[ContinueWatchingEntry.episodeTitle] (saved before this pass) and the raw
 * title is whatever Xtream sent, e.g. "MobLand - MobLand - S02E01 - I Wanna
 * Be Your Dog" (some panels already bake the series name and season/episode
 * code into the episode title itself, hence the duplicate). Splits on " - ",
 * drops immediately-repeated segments, and treats everything before the
 * first SxxExx-shaped segment as the series name and everything after as the
 * episode title.
 */
private fun parseRawEpisodeTitle(raw: String): ParsedEpisodeTitle {
    val parts = raw.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
    val deduped = parts.filterIndexed { index, part -> index == 0 || part != parts[index - 1] }
    val codeIndex = deduped.indexOfFirst { SEASON_EPISODE_CODE.matches(it) }

    return if (codeIndex >= 0) {
        ParsedEpisodeTitle(
            seriesName = deduped.take(codeIndex).joinToString(" - ").takeIf { it.isNotBlank() },
            episodeTitle = deduped.drop(codeIndex + 1).joinToString(" - ").takeIf { it.isNotBlank() }
        )
    } else {
        ParsedEpisodeTitle(seriesName = null, episodeTitle = raw)
    }
}
