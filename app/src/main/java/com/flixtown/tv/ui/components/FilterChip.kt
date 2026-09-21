package com.flixtown.tv.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtTextPrimary

/** A single category/sort chip: same red-glow focus treatment, generous TV spacing. */
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
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) FtAccent else FtTextPrimary
        )
    }
}
