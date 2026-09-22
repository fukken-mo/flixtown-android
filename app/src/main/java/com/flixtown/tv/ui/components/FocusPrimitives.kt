package com.flixtown.tv.ui.components

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FlixMotion
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary

/**
 * The one focus treatment used across every interactive element in the app:
 * a restrained scale-up (see [FlixMotion.FocusScale]), a small upward lift,
 * a thin red border, and a soft red-tinted shadow standing in for a glow —
 * no white focus box, no bounce. Every one of these is a draw-phase-only
 * transform (graphicsLayer scale/translation, Modifier.shadow's elevation),
 * so focusing a card never triggers a recomposition or remeasure of this
 * card, let alone its row/grid — the measured size is identical focused or
 * not. tv-material3's own scale/glow are left at identity so there's no
 * double animation.
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
    val focusAnim = tween<Float>(durationMillis = FlixMotion.FocusDurationMs)
    val scale by animateFloatAsState(
        targetValue = if (isFocused) FlixMotion.FocusScale else 1f,
        animationSpec = focusAnim,
        label = "focusScale"
    )
    val liftFraction by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = focusAnim,
        label = "focusLift"
    )
    val glowElevation by animateDpAsState(
        targetValue = if (isFocused) 14.dp else 0.dp,
        animationSpec = tween(durationMillis = FlixMotion.FocusDurationMs),
        label = "focusGlow"
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            // A scaled-up card must paint over its unscaled neighbors, not
            // underneath them — sibling draw order in a Row/LazyRow is by
            // layout index, not by scale, so without this the enlarged edge
            // of a focused card gets clipped behind the next item in line.
            .zIndex(if (isFocused) 1f else 0f)
            .shadow(elevation = glowElevation, shape = shape, ambientColor = FtAccent, spotColor = FtAccent)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = -FlixMotion.FocusLift.toPx() * liftFraction
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
                border = BorderStroke(1.5.dp, FtAccent),
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
