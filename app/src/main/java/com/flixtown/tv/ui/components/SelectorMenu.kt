package com.flixtown.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

private val MENU_WIDTH = 400.dp
private val MENU_ROW_HEIGHT = 48.dp
private const val MENU_VISIBLE_ROWS = 5

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
            .width(MENU_WIDTH)
            .clip(RoundedCornerShape(10.dp))
            .background(FtSurfaceElevated)
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = FtTextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = MENU_ROW_HEIGHT * MENU_VISIBLE_ROWS)
        ) {
            itemsIndexed(options, key = { index, _ -> index }) { index, option ->
                val isSelected = option == selected
                Surface(
                    onClick = {
                        onSelect(option)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MENU_ROW_HEIGHT)
                        .focusRequester(focusRequesters[index]),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = FtTextPrimary,
                        focusedContainerColor = FtAccent.copy(alpha = 0.2f),
                        focusedContentColor = FtTextPrimary,
                        pressedContainerColor = FtAccent.copy(alpha = 0.28f),
                        pressedContentColor = FtTextPrimary
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = optionLabel(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) FtAccent else FtTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isSelected) {
                            Text(text = "✓", style = MaterialTheme.typography.bodyMedium, color = FtAccent)
                        }
                    }
                }
            }
        }
    }
}
