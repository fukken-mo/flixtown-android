package com.flixtown.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

val DEFAULT_POSTER_WIDTH: Dp = 190.dp

/**
 * A real poster card: Coil-loaded image over a gradient tile (which also
 * doubles as the loading/no-image state, so there's never a blank flash),
 * title, and an optional subtitle (year, episode info, etc). Same
 * FlixFocusSurface red-glow/scale focus treatment used everywhere else.
 *
 * Sizing is entirely up to [modifier] — pass `Modifier.width(DEFAULT_POSTER_WIDTH)`
 * for a fixed-width card in a horizontal row, or `Modifier.fillMaxWidth()`
 * to let a grid cell (e.g. `LazyVerticalGrid(GridCells.Fixed(5))`) size it.
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    FlixFocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(FtSurfaceElevated, FtSurface)))
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = FtTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Fixed-height box, not a conditional Text: a card with no
            // subtitle must be exactly as tall as one with a subtitle, or
            // every card in a row that mixes both ends up a different
            // height — same class of bug as the Home hero's title/subtitle
            // reflow, just at the card level instead of the hero level.
            Box(modifier = Modifier.height(18.dp)) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = FtTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
