package com.flixtown.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.data.AccountStatusStore
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class NavSection(val label: String) {
    Home("Home"),
    Movies("Movies"),
    Series("Series"),
    Search("Search"),
    Settings("Settings")
}

private data class HomeRowSpec(val title: String, val placeholderCount: Int)

// Continue Watching is always first; the rest is placeholder ordering until
// real catalog data exists. Real data replaces placeholderCount with actual
// items — the row/card composables below don't need to change to support it.
private val HOME_ROWS = listOf(
    HomeRowSpec("Continue Watching", 6),
    HomeRowSpec("Recently Added", 10),
    HomeRowSpec("Trending", 10),
    HomeRowSpec("Latest Movies", 10),
    HomeRowSpec("Latest Series", 10)
)

private val NAV_RAIL_WIDTH = 148.dp
private val POSTER_WIDTH = 168.dp

/**
 * The real post-login shell: a slim original left nav rail plus a poster-row
 * content pane. Catalog data is still placeholder (skeleton cards) — this
 * establishes the navigation/row/focus framework the catalog milestone
 * builds on, not the catalog itself.
 *
 * Back is deliberately left to the platform default: Home is the app's
 * root, and Android TV convention is that Back from a root screen
 * backgrounds/exits rather than doing something custom.
 */
@Composable
fun HomeShellScreen(accountStatusStore: AccountStatusStore) {
    var selected by rememberSaveable { mutableStateOf(NavSection.Home) }
    val firstNavFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstNavFocus.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FtBackground)
    ) {
        NavRail(
            selected = selected,
            onSelect = { selected = it },
            firstItemFocusRequester = firstNavFocus
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            HomeHeader(sectionLabel = selected.label, accountStatusStore = accountStatusStore)
            HomeBody(section = selected)
        }
    }
}

@Composable
private fun NavRail(
    selected: NavSection,
    onSelect: (NavSection) -> Unit,
    firstItemFocusRequester: FocusRequester
) {
    Column(
        modifier = Modifier
            .width(NAV_RAIL_WIDTH)
            .fillMaxHeight()
            .background(FtSurface)
            .padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "FLIX",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = FtTextPrimary
        )
        Text(
            text = "TOWN",
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
            color = FtAccent
        )
        Spacer(modifier = Modifier.height(28.dp))

        NavSection.entries.forEachIndexed { index, section ->
            NavRailItem(
                label = section.label,
                isSelected = section == selected,
                onClick = { onSelect(section) },
                modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
            )
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
    FlixFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) FtAccent else FtTextPrimary
        )
    }
}

@Composable
private fun HomeHeader(sectionLabel: String, accountStatusStore: AccountStatusStore) {
    val expiresAtEpochSeconds by accountStatusStore.expiresAtEpochSeconds.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = sectionLabel, style = MaterialTheme.typography.headlineMedium)
        ExpirationLabel(expiresAtEpochSeconds)
    }
}

@Composable
private fun ExpirationLabel(expiresAtEpochSeconds: Long?) {
    if (expiresAtEpochSeconds == null) return
    val formatted = remember(expiresAtEpochSeconds) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(expiresAtEpochSeconds * 1000))
    }
    Text(
        text = "Subscription active until $formatted",
        style = MaterialTheme.typography.bodyMedium,
        color = FtTextSecondary
    )
}

@Composable
private fun HomeBody(section: NavSection) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (section) {
            NavSection.Home -> HomeRows()
            else -> Text(
                text = "${section.label} is coming in the next milestone.",
                style = MaterialTheme.typography.bodyLarge,
                color = FtTextSecondary,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}

@Composable
private fun HomeRows() {
    LazyColumn(
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        items(HOME_ROWS, key = { it.title }) { row ->
            PosterRow(title = row.title, placeholderCount = row.placeholderCount)
        }
    }
}

@Composable
private fun PosterRow(title: String, placeholderCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = FtTextPrimary)

        val listState = rememberLazyListState()
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(count = placeholderCount, key = { it }) {
                PosterSkeletonCard()
            }
        }
    }
}

/**
 * A poster-shaped skeleton placeholder (no fake copy, no gray debug box):
 * a subtly gradient-shaded 2:3 poster tile plus two skeleton text bars where
 * a title/subtitle will sit once real catalog data lands. Swapping this for
 * a real poster is a drop-in replacement — the card, row, and focus wiring
 * around it don't change.
 */
@Composable
private fun PosterSkeletonCard() {
    FlixFocusSurface(
        onClick = {},
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.width(POSTER_WIDTH)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(FtSurfaceElevated, FtSurface)))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(FtSurface)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            SkeletonBar(width = 120.dp)
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBar(width = 76.dp)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SkeletonBar(width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(9.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FtSurfaceElevated)
    )
}
