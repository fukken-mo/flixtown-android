package com.flixtown.tv.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FlixMotion
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtTextPrimary

/**
 * A single category/sort/season chip: dark Flix Town surface at rest, a
 * subtle red accent when selected-but-not-focused, and the full red-stroke
 * scale+glow focus treatment when focused — never a generic/blue pill. Uses
 * the smaller, quicker button-class motion (see [FlixMotion.ButtonFocusScale])
 * rather than the larger poster-card scale, since a chip is closer in size
 * and role to a button than to a poster.
 */
@Composable
fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlixFocusSurface(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        selected = isSelected,
        focusScale = FlixMotion.ButtonFocusScale,
        focusDurationMs = FlixMotion.ButtonFocusDurationMs
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) FtAccent else FtTextPrimary
        )
    }
}
