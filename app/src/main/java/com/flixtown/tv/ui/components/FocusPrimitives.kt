package com.flixtown.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary

/**
 * The one focus treatment used across every interactive element in the app:
 * a ~1.08x scale-up, a subtle red glow, and a red border ring on focus. No
 * white focus box, no jarring snap — tv-material animates this transition
 * for us.
 */
@Composable
fun FlixFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape, focusedShape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FtSurface,
            contentColor = FtTextPrimary,
            focusedContainerColor = FtSurfaceElevated,
            focusedContentColor = FtTextPrimary,
            pressedContainerColor = FtSurfaceElevated,
            pressedContentColor = FtTextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f, pressedScale = 1.03f),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = FtAccent, elevation = 14.dp)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, FtAccent),
                shape = shape
            )
        )
    ) {
        Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
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
