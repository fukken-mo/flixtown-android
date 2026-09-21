package com.flixtown.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

/**
 * A compact "Label: value" trigger that opens a focused, D-pad navigable
 * option list on OK — used for Category and Sort instead of exposing every
 * option across the top of the screen.
 */
@Composable
fun <T> SelectorButton(
    label: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FlixFocusSurface(onClick = onClick, modifier = modifier) {
        Text(text = "$label: $valueLabel", style = MaterialTheme.typography.labelLarge, color = FtTextPrimary)
    }
}

/**
 * The floating option panel itself. Focuses the currently selected option on
 * open (so OK immediately re-confirms the current choice), Up/Down moves
 * through options, OK selects and closes, Back closes without changing
 * anything.
 */
@Composable
fun <T> SelectorMenu(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val focusRequesters = remember(options) { options.map { FocusRequester() } }
    LaunchedEffect(options) {
        focusRequesters.getOrNull(selectedIndex)?.requestFocus()
    }

    Column(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(FtSurfaceElevated)
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = FtTextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            FlixFocusSurface(
                onClick = {
                    onSelect(option)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequesters[index]),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) FtAccent else FtTextPrimary
                    )
                    if (isSelected) {
                        Text(text = "✓", style = MaterialTheme.typography.bodyLarge, color = FtAccent)
                    }
                }
            }
        }
    }
}
