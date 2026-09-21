package com.flixtown.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtTextSecondary

private enum class NavSection(val label: String) {
    Home("Home"),
    Movies("Movies"),
    Series("Series"),
    Search("Search"),
    Settings("Settings")
}

/**
 * The real post-login shell: an original left nav rail plus a content pane.
 * Catalog data is placeholder for now — this establishes the navigation
 * framework the catalog/player milestone builds on, not the catalog itself.
 *
 * Back is deliberately left to the platform default here: Home is the root
 * of the app, and Android TV's convention is that Back from a root screen
 * backgrounds/exits rather than doing something custom.
 */
@Composable
fun HomeShellScreen() {
    var selected by rememberSaveable { mutableStateOf(NavSection.Home) }
    val firstNavFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstNavFocus.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FtBackground)
    ) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(FtSurface)
                .padding(vertical = 32.dp, horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "FLIX TOWN", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(24.dp))

            NavSection.entries.forEachIndexed { index, section ->
                NavRailItem(
                    label = section.label,
                    isSelected = section == selected,
                    onClick = { selected = section },
                    modifier = if (index == 0) Modifier.focusRequester(firstNavFocus) else Modifier
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(48.dp)
        ) {
            HomeContent(section = selected)
        }
    }
}

@Composable
private fun NavRailItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlixFocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) FtAccent else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun HomeContent(section: NavSection) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = section.label, style = MaterialTheme.typography.headlineMedium)

        when (section) {
            NavSection.Home -> {
                Text(
                    text = "You're signed in to Flix Town.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Continue Watching",
                    style = MaterialTheme.typography.titleMedium,
                    color = FtTextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                PlaceholderRow()
            }
            else -> {
                Text(
                    text = "${section.label} is coming in the next milestone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FtTextSecondary
                )
            }
        }
    }
}

@Composable
private fun PlaceholderRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(5) { index ->
            FlixFocusSurface(onClick = {}, shape = RoundedCornerShape(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(width = 160.dp, height = 90.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Coming\nSoon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FtTextSecondary
                    )
                }
            }
        }
    }
}
