package com.flixtown.tv.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Composable
fun FlixTownTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = CinemaColor.Accent,
        onPrimary = CinemaColor.Background,
        background = CinemaColor.Background,
        onBackground = CinemaColor.Text,
        surface = CinemaColor.Surface,
        onSurface = CinemaColor.Text,
        onSurfaceVariant = CinemaColor.Muted
    ), content = content)
}

@Composable
fun PremiumButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Button(onClick = onClick, modifier = modifier,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF292D38),
            contentColor = CinemaColor.Text,
            focusedContainerColor = CinemaColor.Accent,
            focusedContentColor = CinemaColor.Text),
        border = ButtonDefaults.border(focusedBorder = Border(
            border = BorderStroke(2.dp, CinemaColor.Accent), shape = shape)),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        content = content)
}
