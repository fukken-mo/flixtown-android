package com.flixtown.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

// Flix Town brand palette: near-black canvas, elevated dark surfaces, one red accent.
val FtBackground = Color(0xFF0A0A0C)
val FtSurface = Color(0xFF161619)
val FtSurfaceElevated = Color(0xFF1F1F24)
val FtAccent = Color(0xFFE3283A)
val FtAccentDim = Color(0xFF7A1620)
val FtTextPrimary = Color(0xFFFFFFFF)
val FtTextSecondary = Color(0xFFAFAFB8)

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
    displayLarge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, color = FtTextPrimary),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = FtTextPrimary),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = FtTextPrimary),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = FtTextPrimary),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = FtTextPrimary),
    bodyLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, color = FtTextPrimary),
    bodyMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = FtTextSecondary),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FtTextPrimary)
)

@Composable
fun FlixTownTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlixTownColorScheme,
        typography = FlixTownTypography,
        content = content
    )
}
