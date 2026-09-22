package com.flixtown.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary

private const val FOCUS_ANIM_MS = 90
private const val FOCUSED_SCALE = 1.015f

/**
 * The one focus treatment used across every interactive element in the app:
 * a quick ~1.06x scale-up and a red border ring, no white focus box. Scale is
 * driven by our own [animateFloatAsState] + [graphicsLayer] (a draw-phase-only
 * transform, so it never triggers a recomposition or remeasure of this card,
 * let alone its row/grid) instead of tv-material3's built-in scale/glow,
 * which also drops the elevation-shadow "glow" — real Android shadow
 * rendering is expensive to redo every focus change on weaker TV boxes, so a
 * crisp border reads as the same premium red accent for a fraction of the
 * cost.
 */
@Composable
fun FlixFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    content: @Composable () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) FOCUSED_SCALE else 1f,
        animationSpec = tween(durationMillis = FOCUS_ANIM_MS),
        label = "focusScale"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape, focusedShape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FtSurface,
            contentColor = FtTextPrimary,
            focusedContainerColor = FtSurfaceElevated,
            focusedContentColor = FtTextPrimary,
            pressedContainerColor = FtSurfaceElevated,
            pressedContentColor = FtTextPrimary
        ),
        // We drive scale ourselves above; leave tv-material3's own
        // scale/glow at identity/none so there's no double animation and no
        // elevation-shadow recomposition.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, FtAccent),
                shape = shape
            )
        )
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

@Composable
fun FlixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlixFocusSurface(onClick = onClick, modifier = modifier) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
