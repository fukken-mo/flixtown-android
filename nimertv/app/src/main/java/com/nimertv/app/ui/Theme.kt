package com.nimertv.app.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF07040D)
    val BackgroundPurple = Color(0xFF150A2B)
    val Surface = Color(0xFF140C24)
    val SurfaceHigh = Color(0xFF1F1338)
    val Purple = Color(0xFF8B3DFF)
    val PurpleLight = Color(0xFFB57BFF)
    val PurpleDeep = Color(0xFF4A1D96)
    val Gold = Color(0xFFE9C46A)
    val Text = Color(0xFFF4EEFF)
    val TextDim = Color(0xFFA99CC4)
    val TextFaint = Color(0xFF6F6488)
    val Outline = Color(0x1FFFFFFF)
    val Danger = Color(0xFFFF6B81)

    /** Cinematic backdrop shared by every screen. */
    val Screen = Brush.linearGradient(
        0f to Color(0xFF1A0B36),
        0.45f to Color(0xFF0B0616),
        1f to Background,
    )
}

/**
 * Provider text may be Arabic. Glyph shaping and bidirectional ordering follow the content,
 * while alignment stays on the left so mixed lists line up in the English interface.
 */
object Type {
    private val base = TextStyle(
        fontFamily = FontFamily.SansSerif,
        color = Palette.Text,
        textDirection = TextDirection.Content,
        textAlign = TextAlign.Left,
    )
    val Display = base.copy(fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    val Title = base.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val Section = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
    val Body = base.copy(fontSize = 14.sp, lineHeight = 21.sp)
    val Label = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val Caption = base.copy(fontSize = 11.sp, color = Palette.TextDim)
    val Overline = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Palette.PurpleLight)

    /** Long provider descriptions; Arabic is ordered right-to-left but stays on the layout's left edge. */
    val Paragraph = Body.copy(color = Palette.TextDim)
}
