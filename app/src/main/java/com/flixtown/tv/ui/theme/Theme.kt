package com.flixtown.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

// Flix Town brand palette: near-black cinematic canvas, one red accent used
// almost exclusively for focus/selection, three text tones instead of a
// single flat white everywhere.
val FtBackground = Color(0xFF08090C)
val FtSurface = Color(0xFF16171C)
val FtSurfaceElevated = Color(0xFF212228)
val FtAccent = Color(0xFFF01423)
val FtAccentDim = Color(0xFF7A1620)
val FtTextPrimary = Color(0xFFF5F5F5)
val FtTextSecondary = Color(0xFFC2C2C6)
val FtTextMuted = Color(0xFF8A8A92)
val FtRatingGold = Color(0xFFE8B93A)

private val FlixTownColorScheme = darkColorScheme(
    primary = FtAccent,
    onPrimary = FtTextPrimary,
    secondary = FtAccentDim,
    onSecondary = FtTextPrimary,
    background = FtBackground,
    onBackground = FtTextPrimary,
    surface = FtSurface,
    onSurface = FtTextPrimary,
    surfaceVariant = FtSurfaceElevated,
    onSurfaceVariant = FtTextSecondary,
    error = FtAccent,
    onError = FtTextPrimary
)

private val FlixTownTypography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = FtTextPrimary),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = FtTextPrimary),
    headlineMedium = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = FtTextPrimary),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = FtTextPrimary),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = FtTextPrimary),
    bodyLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, color = FtTextPrimary),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = FtTextSecondary),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = FtTextPrimary),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = FtTextSecondary)
)

/** Shared spacing scale so every screen uses the same TV-safe margins and gaps. */
object FlixSpacing {
    val safeHorizontal = 56.dp
    val safeVertical = 40.dp
    val sectionGap = 32.dp
    val rowHeaderGap = 16.dp
    val cardGap = 20.dp
}

/**
 * One place for every motion timing/scale value in the app — nothing
 * animation-related should hardcode its own ms/scale/dp constant elsewhere.
 * Values are deliberately restrained (short durations, small scales, no
 * spring overshoot): this is a movie/TV app, not a game launcher, and every
 * focus animation here is a draw-phase transform only (scale/translation/
 * alpha) — never a layout-affecting property — so it can never itself cause
 * a relayout of surrounding content.
 */
object FlixMotion {
    // Poster/card focus (graphicsLayer scale + translationY only).
    const val FocusDurationMs = 160
    const val FocusScale = 1.045f
    val FocusLift = 6.dp

    // Buttons (Play/Trailer/Secondary/etc) — a smaller, quicker version of
    // the same language since buttons already have strong color contrast.
    const val ButtonFocusDurationMs = 140
    const val ButtonFocusScale = 1.025f

    // Cast portraits.
    const val CastFocusDurationMs = 150
    const val CastFocusScale = 1.04f

    // Hero: how long focus has to "settle" on one item before the backdrop
    // swaps, and how long that swap's crossfade takes.
    const val HeroDebounceMs = 220L
    const val HeroCrossfadeMs = 300

    // LazyRow scroll-into-view when the carousel actually needs to move.
    const val CarouselScrollMs = 220

    // Details screen entry / Home<->Details transition.
    const val ScreenTransitionMs = 200
}

@Composable
fun FlixTownTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlixTownColorScheme,
        typography = FlixTownTypography,
        content = content
    )
}
