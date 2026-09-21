package com.flixtown.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.flixtown.tv.ui.theme.FtBackground

private const val CROSSFADE_MS = 220

/**
 * The cinematic full-bleed background used behind Home/Movies/Series and
 * details: whatever backdrop URL the caller resolved for the currently
 * focused title (falling back through poster art to the plain dark
 * background upstream — this composable only draws whatever URL it's given),
 * crossfaded in quickly when it changes and left completely alone when it
 * doesn't (Coil caches by URL, and `Crossfade` no-ops on an unchanged
 * `targetState`, so staying on one item never re-triggers a decode).
 *
 * A strong vertical + left-side gradient sits on top so foreground text/UI
 * stays readable over any backdrop.
 */
@Composable
fun BackdropBackground(imageUrl: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(FtBackground)) {
        Crossfade(
            targetState = imageUrl,
            animationSpec = tween(durationMillis = CROSSFADE_MS),
            label = "backdrop"
        ) { url ->
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Left-to-right darken so the rail/hero text area always reads clearly.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(FtBackground, FtBackground.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
        // Bottom-up darken so row content never fights the image for contrast.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, FtBackground.copy(alpha = 0.35f), FtBackground)
                    )
                )
        )
    }
}
