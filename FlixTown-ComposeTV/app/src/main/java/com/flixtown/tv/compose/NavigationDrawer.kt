package com.flixtown.tv.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
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
        val width = animateDpAsState(if (expanded) 190.dp else 72.dp, label = "Drawer width")
        Column(Modifier.width(width.value).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(CinemaColor.Surface,
                CinemaColor.Background)), RoundedCornerShape(16.dp))
            .padding(horizontal = 6.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (expanded) "FLIX TOWN" else "FT",
                modifier = Modifier.padding(start = if (expanded) 16.dp else 14.dp,
                    top = 4.dp, bottom = 17.dp),
                color = CinemaColor.Accent, fontSize = 18.sp)
            BrowsePage.entries.forEach { item ->
                val active = selected == item
                Card(onClick = { onSelect(item) },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                        .then(if (item == BrowsePage.Home) Modifier.focusRequester(homeFocus)
                            else Modifier),
                    shape = CardDefaults.shape(RoundedCornerShape(13.dp)),
                    colors = CardDefaults.colors(
                        containerColor = if (active) CinemaColor.Accent.copy(alpha = 0.12f) else Color.Transparent,
                        focusedContainerColor = CinemaColor.Accent.copy(alpha = 0.22f)),
                    border = CardDefaults.border(focusedBorder = Border(
                        border = BorderStroke(1.5.dp, CinemaColor.Accent),
                        shape = RoundedCornerShape(13.dp))),
                    scale = CardDefaults.scale(focusedScale = 1.02f)) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(24.dp)
                            .background(if (active) CinemaColor.Accent else Color.Transparent,
                                RoundedCornerShape(50)))
                        Spacer(Modifier.width(if (expanded) 15.dp else 13.dp))
                        Icon(item.icon, contentDescription = item.label,
                            tint = if (active) CinemaColor.Accent else CinemaColor.Muted,
                            modifier = Modifier.size(25.dp))
                        if (expanded) {
                            Spacer(Modifier.width(13.dp))
                            Text(item.label, color = CinemaColor.Text, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }, modifier = Modifier.fillMaxSize(), content = content)
}
