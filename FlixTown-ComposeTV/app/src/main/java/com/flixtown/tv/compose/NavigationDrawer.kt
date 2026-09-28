package com.flixtown.tv.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text

enum class BrowsePage(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Search("Search", Icons.Default.Search),
    Movies("Movies", Icons.Default.Movie),
    Series("TV Shows", Icons.Default.Tv),
    Watchlist("Watchlist", Icons.Default.Star),
    Settings("Settings", Icons.Default.Settings)
}

@Composable
fun FlixNavigationDrawer(selected: BrowsePage, homeFocus: FocusRequester,
    onSelect: (BrowsePage) -> Unit, content: @Composable () -> Unit) {
    NavigationDrawer(drawerContent = { drawerValue ->
        val expanded = drawerValue == DrawerValue.Open
        val width = animateDpAsState(if (expanded) 210.dp else 78.dp, label = "Drawer width")
        Column(Modifier.width(width.value).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(CinemaColor.Surface,
                CinemaColor.Background)), RoundedCornerShape(18.dp))
            .padding(horizontal = 8.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (expanded) "FLIX TOWN" else "FT",
                modifier = Modifier.padding(start = 16.dp, bottom = 15.dp),
                color = CinemaColor.Accent, fontSize = 19.sp)
            BrowsePage.entries.forEach { item ->
                NavigationDrawerItem(selected = selected == item,
                    onClick = { onSelect(item) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                        .then(if (item == BrowsePage.Home) Modifier.focusRequester(homeFocus)
                            else Modifier),
                    leadingContent = { Icon(item.icon, contentDescription = item.label,
                        modifier = Modifier.size(26.dp)) }) {
                    if (expanded) Text(item.label, fontSize = 17.sp)
                }
            }
        }
    }, modifier = Modifier.fillMaxSize(), content = content)
}
