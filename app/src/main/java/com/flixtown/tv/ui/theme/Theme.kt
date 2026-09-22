package com.flixtown.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.flixtown.tv.R

// Flix Town brand palette: near-black cinematic canvas, one red accent used
// almost exclusively for focus/selection, three text tones instead of a
// single flat white everywhere.
val FtBackground = Color(0xFF08090C)
val FtSurface = Color(0xFF16171C)
val FtSurfaceElevated = Color(0xFF212228)
val FtAccent = Color(0xFFF01423)
val FtAccentDim = Color(0xFF7A1620)
val FtTextPrimary = Color(0xFFF5F5F5)
// Dark text/icon color for content drawn on a light surface (e.g. the Play
// button's white container) — every FlixTypography TextStyle below bakes in
// its own `color`, so it must be set explicitly per-Text, never assumed from
// a Surface's contentColor.
val FtOnLightSurface = Color(0xFF111111)
val FtTextSecondary = Color(0xFFC2C2C6)
val FtTextMuted = Color(0xFF8A8A92)
val FtRatingGold = Color(0xFFE8B93A)
// Deep red focused-surface fill — every FlixFocusSurface uses this as its
// focusedContainerColor so the selected state reads as genuinely red from
// across a room, not just a thin border. Behind an opaque poster image this
// only shows through in the title/metadata strip below it; behind plain
// text content (buttons, chips, dialogs) it fills the whole surface.
val FtFocusSurfaceDeep = Color(0xFF4A1015)

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

// A single variable font (weight axis 100-900) bundled locally in
// res/font/ — Inter, OFL-licensed, fetched once from Google's official
// google/fonts repo and committed here, never downloaded at runtime.
// Chosen over the platform default (Roboto via android:fontFamily=
// "sans-serif" in styles.xml, which Compose's own Typography ignores
// entirely anyway — Compose never reads that XML attribute) because it
// reads as a clean, modern, premium streaming-app face rather than the
// generic Android system look, and ships with the full weight range this
// Typography actually needs (400/500/600/700) from one file instead of
// four separate static ones.
@OptIn(ExperimentalTextApi::class)
private val InterFontFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

/**
 * Every role below is deliberately distinct in weight, not just size, so
 * the hierarchy reads clearly from a couch: display/section text is
 * genuinely bold, card titles are semibold but modest, metadata/labels
 * are visibly lighter and smaller, and body copy stays a comfortable
 * regular weight with real line-height rather than the tight single-line
 * default. [titleSmall] is a new role (tv-material3's Typography default
 * only pre-fills it generically) used specifically for poster/row card
 * titles — previously those shared [bodyMedium] with description text,
 * which is why they read as plain, undifferentiated "API value under an
 * image" text rather than an intentionally designed title.
 */
private val FlixTownTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 46.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = FtTextPrimary
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        color = FtTextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        color = FtTextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
        color = FtTextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = FtTextPrimary
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = FtTextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        color = FtTextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
        color = FtTextSecondary
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = FtTextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = FtTextSecondary
    )
)

/** Shared spacing scale so every screen uses the same TV-safe margins and gaps. */
object FlixSpacing {
    // TV safe-area left/right margin — every screen's outermost content
    // (titles, metadata, descriptions, buttons, row headers, grid/row
    // edges) insets from the physical screen edge by exactly this amount,
    // never less, so nothing ever sits flush against or near it. 64dp
    // measured correctly on-device (confirmed via the safe-area debug
    // overlay) but still read as visually too close to the physical bezel
    // on a real TV — 96dp is the deliberately larger standard content edge
    // used everywhere now, closer to the IBO reference's proportions.
    val safeHorizontal = 96.dp
    val safeVertical = 40.dp
    val sectionGap = 32.dp
    val rowHeaderGap = 16.dp
    // More breathing room between posters — 20dp read as cramped once
    // safeHorizontal grew; cards still fit comfortably at 5 per grid row.
    val cardGap = 26.dp
    // Top breathing room above the Details hero block (backdrop + poster +
    // title), so content never sits flush against the screen's top edge.
    val heroTopGap = 56.dp

    // Used as top/bottom contentPadding (and, doubled, as the gap between
    // rows) on every Lazy row/grid of focusable cards, so a focused card's
    // scale+lift growth (up to ~1.045x + 6dp lift, i.e. up to ~14dp for the
    // tallest realistic card here) always lands inside space that row/grid
    // already accounts for as its own — never inside space that suddenly
    // needs to be "brought into view" by an ancestor scrollable. First-/
    // last-column clipping is covered separately by the much larger
    // safeHorizontal edge margin already in use everywhere.
    val focusReserveTop = 24.dp
    val focusReserveBottom = 8.dp
}

/**
 * One place for every motion timing/scale value in the app — nothing
 * animation-related should hardcode its own ms/scale/dp constant elsewhere.
 *
 * Deliberately IBO-Player-restrained, not a "premium" motion showcase: the
 * selected state should read from color/border, not physical growth, so
 * that focus changes feel immediate and rapid D-pad navigation never lags
 * or visibly bounces. Card scale is 1.0 (no scale) everywhere by default;
 * duration values only govern the border/color transition tv-material3
 * already does natively, not a hand-rolled scale/lift animation.
 */
object FlixMotion {
    // Poster/card focus. No scale — the selected state comes from the
    // border + focused container color only. Row/grid scroll-into-view
    // (LazyRow/LazyVerticalGrid's own built-in focus behavior — no custom
    // scroll logic added here) already only moves the minimum distance
    // needed to bring a newly-focused item into view, i.e. it's already a
    // no-op while the next item is fully visible and only shifts once
    // focus reaches an edge, which is the IBO-style behavior asked for
    // without inventing a parallel, riskier hand-rolled scroll system.
    const val FocusDurationMs = 60
    const val FocusScale = 1f

    // Buttons (Play/Trailer/Secondary/etc) — same restraint, kept fractionally
    // larger than posters since a button has less area for color/border alone
    // to read clearly from across a room.
    const val ButtonFocusDurationMs = 60
    const val ButtonFocusScale = 1.02f

    // Hero: how long focus has to "settle" on one item before the backdrop
    // swaps, and how long that swap's crossfade takes. Rapidly passing
    // through several posters must never flash through every backdrop in
    // between — only the item focus actually settles on updates it.
    const val HeroDebounceMs = 350L
    const val HeroCrossfadeMs = 200

    // Home<->Movies<->Series<->Details<->Search transition: a plain fade,
    // no scale/slide/spring — responsiveness matters more than decoration.
    const val ScreenTransitionMs = 90
}

@Composable
fun FlixTownTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlixTownColorScheme,
        typography = FlixTownTypography,
        content = content
    )
}
