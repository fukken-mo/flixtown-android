package com.flixtown.tv.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.flixtown.tv.ui.components.BackdropLayer
import com.flixtown.tv.ui.components.PosterSkeletonCard
import com.flixtown.tv.ui.components.SecondaryActionButton
import com.flixtown.tv.ui.theme.FlixSpacing
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtFocusSurfaceDeep
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextMuted
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

const val GRID_COLUMNS = 5

@Composable
fun CatalogGridSkeleton() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        contentPadding = PaddingValues(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.sectionGap - 8.dp),
        horizontalArrangement = Arrangement.spacedBy(FlixSpacing.cardGap),
        verticalArrangement = Arrangement.spacedBy(FlixSpacing.sectionGap)
    ) {
        items(15) { PosterSkeletonCard() }
    }
}

@Composable
fun CatalogErrorRetry(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = FlixSpacing.safeHorizontal, vertical = FlixSpacing.sectionGap - 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Couldn't load: $message",
                style = MaterialTheme.typography.bodyLarge,
                color = FtTextSecondary
            )
            SecondaryActionButton(text = "Retry", onClick = onRetry)
        }
    }
}

/**
 * The cinematic backdrop for Movies/Series: [BackdropLayer] (the exact same
 * debounced-focus-URL + Crossfade machinery already used on Home — nothing
 * new here, so "don't re-decode/recompose on rapid D-pad presses" comes for
 * free from that existing, already-tuned implementation) underneath a much
 * heavier uniform darkening than Home uses, plus a static vertical vignette
 * that's present even with no backdrop at all. Home's own hero needs its
 * backdrop bright enough to read text over; a browse grid doesn't — the
 * posters themselves are the focus, this is only meant to remove the flat
 * black emptiness behind them.
 */
@Composable
fun CatalogBrowseBackground(backdropUrlState: State<String?>, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(FtBackground)) {
        BackdropLayer(state = backdropUrlState, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().background(FtBackground.copy(alpha = 0.78f)))
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(colors = listOf(FtSurfaceElevated.copy(alpha = 0.35f), FtBackground))
            )
        )
    }
}

/**
 * The pill-shaped "Label: value" trigger used for Category/Sort — a
 * dedicated, smaller-weight look distinct from
 * [com.flixtown.tv.ui.components.FlixFocusSurface]'s app-wide default (kept
 * local to Movies/Series rather than changing that shared primitive, which
 * Details/Player/every other button also use): a subtle always-visible
 * border at rest so it never reads as a flat/generic Android button, full
 * red border + deep red fill on focus, no scale/Animatable — a plain
 * [Surface] color/border swap is the same effectively-free focus mechanism
 * FlixFocusSurface itself uses whenever it skips its own scale machinery.
 */
@Composable
fun CatalogFilterButton(
    label: String,
    valueLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FtSurfaceElevated.copy(alpha = 0.85f),
            contentColor = FtTextPrimary,
            focusedContainerColor = FtFocusSurfaceDeep,
            focusedContentColor = FtTextPrimary,
            pressedContainerColor = FtFocusSurfaceDeep,
            pressedContentColor = FtTextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, FtTextMuted.copy(alpha = 0.35f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(2.dp, FtAccent), shape = shape)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = FtTextSecondary)
            Text(text = valueLabel, style = MaterialTheme.typography.labelMedium, color = FtTextPrimary)
        }
    }
}

/**
 * Same pill family as [CatalogFilterButton] (single label, no "value" half)
 * so Search reads as one of the header's filter controls instead of an
 * unrelated floating action on the far side of the screen.
 */
@Composable
fun CatalogSearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FtSurfaceElevated.copy(alpha = 0.85f),
            contentColor = FtTextPrimary,
            focusedContainerColor = FtFocusSurfaceDeep,
            focusedContentColor = FtTextPrimary,
            pressedContainerColor = FtFocusSurfaceDeep,
            pressedContentColor = FtTextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, FtTextMuted.copy(alpha = 0.35f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(2.dp, FtAccent), shape = shape)
        )
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
            Text(text = "Search", style = MaterialTheme.typography.labelMedium, color = FtTextPrimary)
        }
    }
}
