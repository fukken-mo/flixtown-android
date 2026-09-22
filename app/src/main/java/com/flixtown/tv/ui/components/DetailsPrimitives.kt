package com.flixtown.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FlixMotion
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtOnLightSurface
import com.flixtown.tv.ui.theme.FtRatingGold
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextMuted
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

/**
 * The bright, high-contrast Play button — the one thing on a details screen
 * that should stand out immediately.
 *
 * Both the icon tint and the Text's own `color` are set explicitly to
 * [FtOnLightSurface] rather than trusting the Surface's `contentColor`:
 * every FlixTypography [androidx.compose.ui.text.TextStyle] bakes in its own
 * `color` (FtTextPrimary, near-white), so a Text composed with the default
 * typography renders in that color regardless of what contentColor the
 * Surface declares — on this button's light (FtTextPrimary) container that
 * produced invisible near-white-on-white text.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val anim = tween<Float>(durationMillis = FlixMotion.ButtonFocusDurationMs)
    val scale by animateFloatAsState(if (isFocused) FlixMotion.ButtonFocusScale else 1f, anim, label = "primaryScale")
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FtTextPrimary,
            contentColor = FtOnLightSurface,
            focusedContainerColor = FtTextPrimary,
            focusedContentColor = FtOnLightSurface,
            pressedContainerColor = FtTextPrimary,
            pressedContentColor = FtOnLightSurface
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(1.5.dp, FtAccent), shape = shape)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Image(
                    imageVector = icon,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(FtOnLightSurface),
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, color = FtOnLightSurface)
            )
        }
    }
}

/** Dark translucent secondary action (Trailer, Favorite, ...) — same focus language, lower visual weight. */
@Composable
fun SecondaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    val anim = tween<Float>(durationMillis = FlixMotion.ButtonFocusDurationMs)
    val scale by animateFloatAsState(if (isFocused) FlixMotion.ButtonFocusScale else 1f, anim, label = "secondaryScale")
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FtSurfaceElevated.copy(alpha = 0.72f),
            contentColor = FtTextPrimary,
            focusedContainerColor = FtSurfaceElevated,
            focusedContentColor = FtTextPrimary,
            pressedContainerColor = FtSurfaceElevated,
            pressedContentColor = FtTextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, FtTextMuted.copy(alpha = 0.4f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(1.5.dp, FtAccent), shape = shape)
        )
    ) {
        Box(modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "2026 • 1h 27m • ★ 7.3" — one muted metadata line, star tinted gold. */
@Composable
fun MetadataRow(parts: List<String>, rating: Double?, modifier: Modifier = Modifier) {
    if (parts.isEmpty() && rating == null) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text(text = "•", style = MaterialTheme.typography.bodyMedium, color = FtTextMuted)
            Text(text = part, style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
        }
        if (rating != null) {
            if (parts.isNotEmpty()) Text(text = "•", style = MaterialTheme.typography.bodyMedium, color = FtTextMuted)
            Text(text = "★ ${"%.1f".format(rating)}", style = MaterialTheme.typography.bodyMedium, color = FtRatingGold)
        }
    }
}

/** A section title used above every carousel/cast/similar row — one consistent look app-wide. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = FtTextPrimary, modifier = modifier)
}
