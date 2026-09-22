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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FlixMotion
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtAccentDim
import com.flixtown.tv.ui.theme.FtFocusSurfaceDeep
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtTextPrimary

/**
 * The one focus treatment used across every interactive element in the app —
 * deliberately IBO-Player-restrained on motion (no scale/lift/glow layer),
 * but a genuinely RED focused state, not a thin outline: a 2dp red border
 * plus [FtFocusSurfaceDeep] as the focused container color, so a focused
 * item reads as red-selected from across a room without physically growing.
 * Behind an opaque image (a poster/thumbnail) this container color only
 * shows through wherever that image doesn't cover it (e.g. the title strip
 * below a poster) — image-covered regions need their own overlay if they
 * also need to look focused, which PosterCard/ContinueWatchingCard add
 * themselves. [focusScale] defaults to 1.0 (no scale at all); pass a value
 * up to ~1.02 only where color/border alone can't read clearly (buttons/
 * chips, via [FlixMotion.ButtonFocusScale]). No shadow/glow layer, no
 * vertical lift, no zIndex reordering — those were real per-frame rendering
 * cost and are unnecessary once nothing actually grows past its own bounds.
 */
@Composable
fun FlixFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    selected: Boolean = false,
    focusScale: Float = FlixMotion.FocusScale,
    focusDurationMs: Int = FlixMotion.FocusDurationMs,
    content: @Composable () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1f,
        animationSpec = tween(durationMillis = focusDurationMs),
        label = "focusScale"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape, focusedShape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FtAccentDim.copy(alpha = 0.4f) else FtSurface,
            contentColor = FtTextPrimary,
            focusedContainerColor = FtFocusSurfaceDeep,
            focusedContentColor = FtTextPrimary,
            pressedContainerColor = FtFocusSurfaceDeep,
            pressedContentColor = FtTextPrimary
        ),
        // We drive scale ourselves above; leave tv-material3's own
        // scale/glow at identity/none/default so there's no double
        // animation stacked on top of ours — ClickableSurfaceDefaults.glow()
        // (left untouched below, since Surface's own default parameter
        // value is what's in effect) draws nothing unless a non-default
        // Glow is explicitly requested, so there is no separate glow/shadow
        // animation running here either.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) {
                Border(border = BorderStroke(1.dp, FtAccent.copy(alpha = 0.7f)), shape = shape)
            } else {
                Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape)
            },
            focusedBorder = Border(
                border = BorderStroke(2.dp, FtAccent),
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
