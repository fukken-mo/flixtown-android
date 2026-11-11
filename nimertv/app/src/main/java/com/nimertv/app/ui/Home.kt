package com.nimertv.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nimertv.app.BuildConfig
import com.nimertv.app.NimerApp
import com.nimertv.app.R
import com.nimertv.app.data.Catalog
import com.nimertv.app.data.Channel
import com.nimertv.app.data.Session
import com.nimertv.app.data.UpdateLogic
import com.nimertv.app.data.UpdateState
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home layouts the customer can pick on the Account screen. */
object HomeStyle {
    const val PANELS = 0
    const val SPOTLIGHT = 1
    const val MARQUEE = 2
    val names = listOf("Panels", "Spotlight", "Marquee")
}

/** One of the three destinations with real provider numbers and artwork. */
private class Destination(
    val key: String,
    val title: String,
    val tagline: String,
    val caption: String,
    val art: List<String>,
    val logos: Boolean,
    val open: () -> Unit,
)

private class Feed(val count: Int, val art: List<String>)

private fun count(n: Int, noun: String) = NumberFormat.getIntegerInstance(Locale.US).format(n) + " " + noun

/** Subscription line for the top of the home screen; the flag is true when it needs attention. */
fun expiryText(session: Session, now: Long = System.currentTimeMillis()): Pair<String, Boolean> {
    if (session.expiresAtSec <= 0) return "Subscription active  ·  no expiry date" to false
    val end = session.expiresAtSec * 1000
    val date = SimpleDateFormat("d MMMM yyyy", Locale.US).format(Date(end))
    val days = (end - now) / 86_400_000L
    return when {
        end <= now -> "Subscription expired on $date" to true
        days < 1 -> "Subscription expires today" to true
        days <= 7 -> "Subscription expires $date  ·  ${days + 1} days left" to true
        else -> "Subscription expires $date" to false
    }
}

@Composable
fun HomeScreen(app: NimerApp, screen: Screen.Home, nav: Nav, catalog: Catalog, reload: Int, onShowUpdate: () -> Unit) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    var focusedKey by remember { mutableStateOf((screen.focus.key as? String)?.takeIf { it.startsWith("hero:") } ?: "hero:live") }

    val live by rememberLoadable(reload, retry) {
        val channels = catalog.channels()
        Feed(channels.size, channels.asSequence().map { it.logo }.filter { it.isNotBlank() }.distinct().take(8).toList())
    }
    val movies by rememberLoadable(reload, retry) {
        val all = catalog.movies()
        Feed(all.size, all.sortedByDescending { it.added }.asSequence().map { it.poster }.filter { it.isNotBlank() }.distinct().take(6).toList())
    }
    val series by rememberLoadable(reload, retry) {
        val all = catalog.series()
        Feed(all.size, all.sortedByDescending { it.lastModified }.asSequence().map { it.cover }.filter { it.isNotBlank() }.distinct().take(6).toList())
    }

    fun caption(l: Loadable<Feed>, noun: String) = when (l) {
        is Loadable.Ready -> count(l.data.count, noun)
        is Loadable.Failed -> "Unavailable"
        Loadable.Loading -> "Loading…"
    }
    val destinations = listOf(
        Destination("hero:live", "LIVE TV", "Watch channels as they air", caption(live, "channels"), live.value?.art.orEmpty(), true) { nav.push(Screen.Live()) },
        Destination("hero:movies", "MOVIES", "Browse the film library", caption(movies, "movies"), movies.value?.art.orEmpty(), false) { nav.push(Screen.Movies()) },
        Destination("hero:series", "SERIES", "Seasons and episodes", caption(series, "series"), series.value?.art.orEmpty(), false) { nav.push(Screen.SeriesList()) },
    )
    val failure = listOf(live, movies, series).filterIsInstance<Loadable.Failed>().firstOrNull()
    val config = app.panel?.config
    val now = System.currentTimeMillis()
    val banners = config?.announcements.orEmpty().filter { !it.popup && it.activeAt(now) }
    val updateAvailable = UpdateLogic.state(config?.update, BuildConfig.VERSION_CODE, context.packageName) != UpdateState.NONE
    val session = app.session
    val focused = destinations.firstOrNull { it.key == focusedKey } ?: destinations.first()

    Box(Modifier.fillMaxSize()) {
        if (app.homeStyle == HomeStyle.MARQUEE) MarqueeBackdrop(focused)

        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 22.dp)) {
            // Top bar: brand · subscription expiry (centre) · search and account.
            Box(Modifier.fillMaxWidth().height(46.dp)) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.nimer_logo), "Nimer TV", Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("NIMER TV", style = Type.Section.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp))
                }
                if (session != null) {
                    val (text, warn) = expiryText(session, now)
                    Text(
                        text,
                        style = Type.Label.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                        color = if (warn) Palette.Gold else Palette.Text,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.Center)
                            .background(if (warn) Palette.Gold.copy(alpha = 0.16f) else Color(0x14FFFFFF), RoundedCornerShape(50))
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (updateAvailable) PillButton("Update", onShowUpdate, Modifier.rememberFocus(screen.focus, "update"), primary = true)
                    PillButton("Search", { nav.push(Screen.Search()) }, Modifier.rememberFocus(screen.focus, "search"), icon = Icons.Filled.Search)
                    PillButton("Account", { nav.push(Screen.Account()) }, Modifier.rememberFocus(screen.focus, "account"), icon = Icons.Filled.AccountCircle)
                }
            }

            banners.take(1).forEach { a ->
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (a.priority) Palette.Gold.copy(alpha = 0.16f) else Palette.Purple.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("NOTICE", style = Type.Overline.copy(color = if (a.priority) Palette.Gold else Palette.PurpleLight))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        listOf(a.title, a.message).filter { it.isNotEmpty() }.joinToString("  —  "),
                        style = Type.Label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (failure != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(failure.message, style = Type.Body, color = Palette.Danger, modifier = Modifier.weight(1f))
                    PillButton("Try again", { retry++ }, Modifier.rememberFocus(screen.focus, "retry"), icon = Icons.Filled.Refresh)
                }
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (app.homeStyle) {
                    HomeStyle.SPOTLIGHT -> SpotlightHome(screen, destinations, focusedKey) { focusedKey = it }
                    HomeStyle.MARQUEE -> MarqueeHome(screen, destinations, focused) { focusedKey = it }
                    else -> PanelsHome(screen, destinations, focusedKey) { focusedKey = it }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ shared artwork */

@Composable
private fun PosterStrip(art: List<String>, count: Int, alpha: Float, modifier: Modifier = Modifier) {
    Row(modifier) {
        art.take(count).forEach { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alpha = alpha, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LogoGrid(art: List<String>, columns: Int, rows: Int, alpha: Float, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        art.take(columns * rows).chunked(columns).forEach { line ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { url ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x1FFFFFFF), RoundedCornerShape(10.dp))) {
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, alpha = alpha, modifier = Modifier.fillMaxSize().padding(8.dp))
                    }
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/* ------------------------------------------------------------------ Concept 1: Panels */

/** Three tall cinematic panels; the focused one widens. */
@Composable
private fun PanelsHome(screen: Screen.Home, destinations: List<Destination>, focusedKey: String, onFocus: (String) -> Unit) {
    Row(Modifier.fillMaxSize().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        destinations.forEach { d ->
            val isFocused = d.key == focusedKey
            val weight by animateFloatAsState(if (isFocused) 1.35f else 1f, tween(260), label = "panel")
            FocusSurface(
                onClick = d.open, onFocused = { onFocus(d.key) },
                modifier = Modifier.weight(weight).fillMaxHeight().rememberFocus(screen.focus, d.key),
                shape = RoundedCornerShape(24.dp), focusedScale = 1.02f,
            ) { focused ->
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.PurpleDeep, Palette.BackgroundPurple))))
                if (d.art.isNotEmpty()) {
                    if (d.logos) {
                        LogoGrid(d.art, 2, 3, if (focused) 1f else 0.7f, Modifier.fillMaxWidth().fillMaxHeight(0.62f).padding(18.dp))
                    } else {
                        PosterStrip(d.art, 2, if (focused) 0.95f else 0.6f, Modifier.fillMaxSize())
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.72f to Palette.Background.copy(alpha = 0.82f), 1f to Palette.Background)))
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text(if (focused) "PRESS OK TO OPEN" else "BROWSE", style = Type.Overline.copy(color = if (focused) Palette.Gold else Palette.PurpleLight))
                    Text(d.title, style = Type.Display, color = Color.White, maxLines = 1)
                    Text(d.caption, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Text, maxLines = 1)
                    Text(d.tagline, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ Concept 2: Spotlight */

/** Brand on the left, three wide banners stacked on the right. */
@Composable
private fun SpotlightHome(screen: Screen.Home, destinations: List<Destination>, focusedKey: String, onFocus: (String) -> Unit) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.36f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.nimer_logo), "Nimer TV", Modifier.fillMaxWidth(0.72f).weight(1f, fill = false))
            Spacer(Modifier.height(8.dp))
            Text("LIVE TV  ·  MOVIES  ·  SERIES", style = Type.Overline)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(0.64f).fillMaxHeight().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            destinations.forEach { d ->
                FocusSurface(
                    onClick = d.open, onFocused = { onFocus(d.key) },
                    modifier = Modifier.fillMaxWidth().weight(1f).rememberFocus(screen.focus, d.key),
                    shape = RoundedCornerShape(20.dp), focusedScale = 1.03f,
                ) { focused ->
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Palette.PurpleDeep, Palette.BackgroundPurple))))
                    if (d.art.isNotEmpty()) {
                        val artModifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.62f).fillMaxHeight()
                        if (d.logos) LogoGrid(d.art, 4, 1, if (focused) 1f else 0.7f, artModifier.padding(vertical = 22.dp, horizontal = 16.dp))
                        else PosterStrip(d.art, 5, if (focused) 0.95f else 0.6f, artModifier)
                    }
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.3f to Palette.Background.copy(alpha = 0.95f), 0.7f to Palette.Background.copy(alpha = if (focused) 0.1f else 0.45f))))
                    Column(Modifier.align(Alignment.CenterStart).padding(start = 26.dp)) {
                        Text(d.title, style = Type.Display, color = Color.White)
                        Text(d.caption, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = if (focused) Palette.Gold else Palette.PurpleLight)
                        Text(d.tagline, style = Type.Caption)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ Concept 3: Marquee */

@Composable
private fun MarqueeBackdrop(focused: Destination) {
    Crossfade(focused.key, animationSpec = tween(350), label = "backdrop") { key ->
        Box(Modifier.fillMaxSize()) {
            if (key == focused.key && focused.art.isNotEmpty()) {
                if (focused.logos) {
                    LogoGrid(focused.art, 4, 2, 0.8f, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.6f).fillMaxHeight(0.62f).padding(top = 96.dp, end = 44.dp))
                } else {
                    PosterStrip(focused.art, 6, 0.55f, Modifier.fillMaxSize())
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.Background.copy(alpha = 0.96f), 0.5f to Palette.Background.copy(alpha = 0.6f), 1f to Palette.Background.copy(alpha = 0.25f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.Background.copy(alpha = 0.7f), 0.3f to Color.Transparent, 0.62f to Palette.Background.copy(alpha = 0.7f), 1f to Palette.Background)))
        }
    }
}

/** Full-screen artwork for the highlighted section, with the three choices along the bottom. */
@Composable
private fun MarqueeHome(screen: Screen.Home, destinations: List<Destination>, focused: Destination, onFocus: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth(0.55f), verticalArrangement = Arrangement.Center) {
            Text("NOW BROWSING", style = Type.Overline.copy(color = Palette.Gold))
            Text(focused.title, style = Type.Display.copy(fontSize = 56.sp), color = Color.White)
            Text(focused.caption, style = Type.Title, color = Palette.PurpleLight)
            Spacer(Modifier.height(4.dp))
            Text(focused.tagline, style = Type.Body, color = Palette.TextDim)
        }
        Row(Modifier.fillMaxWidth().height(86.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            destinations.forEach { d ->
                FocusSurface(
                    onClick = d.open, onFocused = { onFocus(d.key) },
                    modifier = Modifier.weight(1f).fillMaxHeight().rememberFocus(screen.focus, d.key),
                    shape = RoundedCornerShape(18.dp), focusedScale = 1.04f,
                    background = Color(0x99140C24), focusedBackground = Palette.Purple.copy(alpha = 0.85f),
                ) { isFocused ->
                    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 22.dp)) {
                        Text(d.title, style = Type.Title.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp), color = Color.White)
                        Text(d.caption, style = Type.Caption, color = if (isFocused) Color.White else Palette.TextDim)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ tiles shared with Search */

/** 16:9 channel tile used in search results. */
@Composable
fun ChannelTile(channel: Channel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(150.dp)) {
        FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().height(84.dp)) {
            LogoArt(channel.logo, channel.name, Modifier.fillMaxSize(), inset = 14.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(channel.name, style = Type.Label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

val POSTER_WIDTH = 118.dp
