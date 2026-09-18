package com.flixtown.tv

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val NavItems = listOf("Home", "Movies", "Series")

@Composable
fun SplashScreen() = Box(Modifier.fillMaxSize().background(FlixBlack), contentAlignment = Alignment.Center) {
    Image(painterResource(com.flixtown.tv.R.drawable.flix_logo), "Flix Town", Modifier.width(230.dp))
}

@Composable
fun ConnectionScreen(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Flix Town", fontSize = 36.sp, fontWeight = FontWeight.Black, color = FlixRed)
        Spacer(Modifier.height(16.dp)); Text(text, color = Color.LightGray)
    }
}

@Composable
fun LoginScreen(config: AppConfig?, error: String?, onLogin: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var activationUrl by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("------") }
    var status by remember { mutableStateOf("Creating your secure TV code") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        runCatching {
            val created = Api.postObject(PAIR_CREATE_URL, emptyMap())
            code = created.get("code")?.asString.orEmpty()
            activationUrl = created.get("activation_url")?.asString.orEmpty()
            val token = created.get("device_token")?.asString.orEmpty()
            status = "Scan to sign in"
            while (isActive && token.isNotBlank()) {
                delay(2500)
                val result = Api.postObject(PAIR_STATUS_URL, mapOf("code" to code, "device_token" to token))
                if (result.get("status")?.asString == "approved") {
                    onLogin(result.get("username")?.asString.orEmpty(), result.get("password")?.asString.orEmpty()); break
                }
            }
        }.onFailure { status = "Phone sign-in is temporarily unavailable" }
    }

    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF08090D), Color(0xFF171017))))) {
        Image(painterResource(R.drawable.flix_logo), "Flix Town", Modifier.align(Alignment.TopStart).padding(42.dp, 28.dp).width(170.dp))
        Row(Modifier.align(Alignment.Center).padding(top = 42.dp), horizontalArrangement = Arrangement.spacedBy(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(290.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SIGN IN WITH YOUR PHONE", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                if (activationUrl.isNotBlank()) Image(qrBitmap(activationUrl).asImageBitmap(), "QR login", Modifier.size(185.dp))
                else Box(Modifier.size(185.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FlixRed) }
                Text(code, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 25.sp, letterSpacing = 4.sp)
                Text(status, color = Color.DarkGray, fontSize = 11.sp)
                Spacer(Modifier.height(9.dp)); FocusButton("REFRESH CODE", secondary = true) { refresh++ }
            }
            Column(Modifier.width(390.dp).clip(RoundedCornerShape(22.dp)).background(FlixPanel).border(1.dp, Color(0xFF32343B), RoundedCornerShape(22.dp)).padding(28.dp)) {
                Text("Sign in with your remote", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Use the account details provided by Flix Town.", color = FlixMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                Spacer(Modifier.height(11.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (username.isNotBlank() && password.isNotBlank()) onLogin(username, password) }))
                if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFF8A8F), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(18.dp)); FocusButton("SIGN IN", Modifier.fillMaxWidth().height(52.dp)) { if (username.isNotBlank() && password.isNotBlank()) onLogin(username, password) }
                if (!config?.announcement.isNullOrBlank()) Text(config!!.announcement, color = FlixMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

@Composable
fun HomeScreen(config: AppConfig, credentials: Credentials, account: AccountInfo, onOpen: (ContentItem) -> Unit, onRoute: (Route) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var movies by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var zone by remember { mutableIntStateOf(0) } // 0 nav, 1 continue, 2 recent
    var nav by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    val watching = remember { loadContinueWatching(context) }
    val recent = remember(movies, series) { (movies.take(12) + series.take(12)).sortedByDescending { it.id }.take(20) }
    val rowState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val a = async { runCatching { loadContent(config, credentials, ContentType.MOVIE) }.getOrDefault(emptyList()) }
        val b = async { runCatching { loadContent(config, credentials, ContentType.SERIES) }.getOrDefault(emptyList()) }
        movies = a.await(); series = b.await(); loading = false
        focus.requestFocus()
    }
    LaunchedEffect(index, zone) { if (zone > 0) rowState.animateScrollToItem(index.coerceAtLeast(0)) }

    val keyHandler: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.DirectionLeft -> { if (zone == 0) nav = (nav - 1).coerceAtLeast(0) else index = (index - 1).coerceAtLeast(0); true }
            Key.DirectionRight -> { if (zone == 0) nav = (nav + 1).coerceAtMost(2) else index = (index + 1).coerceAtMost((if (zone == 1) watching.size else recent.size).minus(1).coerceAtLeast(0)); true }
            Key.DirectionUp -> { if (zone > 0) { zone = if (zone == 2 && watching.isNotEmpty()) 1 else 0; index = 0 }; true }
            Key.DirectionDown -> { if (zone == 0) { zone = if (watching.isEmpty()) 2 else 1; index = 0 } else if (zone == 1) { zone = 2; index = 0 }; true }
            Key.Enter, Key.DirectionCenter -> {
                if (zone == 0) onRoute(listOf(Route.HOME, Route.MOVIES, Route.SERIES)[nav])
                else if (zone == 1) watching.getOrNull(index)?.let { onOpen(ContentItem(it.mediaId, it.title, it.poster, type = it.mediaType, extension = it.extension)) }
                else recent.getOrNull(index)?.let(onOpen); true
            }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent(keyHandler)) {
        val hero = recent.firstOrNull()
        AsyncImage(hero?.backdrop ?: hero?.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .35f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.12f), FlixBlack.copy(.9f), FlixBlack))))
        Column(Modifier.fillMaxSize().padding(horizontal = 46.dp, vertical = 24.dp)) {
            Header(nav, zone == 0, account, onRoute)
            Spacer(Modifier.height(25.dp))
            Text(hero?.title ?: "Welcome to Flix Town", fontSize = 38.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(hero?.plot?.take(180) ?: "Movies and series, organized for your TV.", color = Color.LightGray, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.width(580.dp).padding(top = 8.dp))
            Spacer(Modifier.height(26.dp))
            if (loading) LinearProgressIndicator(color = FlixRed, modifier = Modifier.width(260.dp))
            if (watching.isNotEmpty()) {
                SectionTitle("Continue Watching")
                PosterRow(watching.map { ContentItem(it.mediaId, it.title, it.poster, type = it.mediaType, extension = it.extension) }, index, zone == 1, rowState)
                Spacer(Modifier.height(18.dp))
            }
            SectionTitle("Recently Added")
            PosterRow(recent, index, zone == 2, rowState)
        }
    }
}

@Composable
private fun Header(selected: Int, active: Boolean, account: AccountInfo, onRoute: (Route) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.flix_logo), "Flix Town", Modifier.width(138.dp).height(58.dp))
        Spacer(Modifier.width(44.dp))
        NavItems.forEachIndexed { i, label ->
            val chosen = selected == i && active
            Box(Modifier.padding(horizontal = 5.dp).clip(RoundedCornerShape(9.dp)).background(if (chosen) FlixRed else Color.Transparent).border(if (chosen) 2.dp else 0.dp, Color.White, RoundedCornerShape(9.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(label, fontWeight = if (chosen) FontWeight.Black else FontWeight.SemiBold, color = if (chosen) Color.White else Color.LightGray)
            }
        }
        Spacer(Modifier.weight(1f)); Column(horizontalAlignment = Alignment.End) { Text("ACCOUNT EXPIRES", color = FlixMuted, fontSize = 9.sp, letterSpacing = 1.sp); Text(account.expiration, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun PosterRow(items: List<ContentItem>, selected: Int, active: Boolean, state: androidx.compose.foundation.lazy.LazyListState) {
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(15.dp), modifier = Modifier.height(178.dp)) {
        itemsIndexed(items, key = { _, it -> "${it.type}-${it.id}" }) { i, item -> Poster(item, active && i == selected, 118.dp, 166.dp) }
    }
}

@Composable
fun CatalogScreen(config: AppConfig, credentials: Credentials, type: ContentType, onOpen: (ContentItem) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
    var categories by remember { mutableStateOf(listOf(Category("", "All"))) }
    var items by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var shown by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var zone by remember { mutableIntStateOf(0) } // 0 categories 1 sort 2 grid
    var category by remember { mutableIntStateOf(0) }; var sort by remember { mutableIntStateOf(0) }; var index by remember { mutableIntStateOf(0) }
    val categoryState = rememberLazyListState(); val gridState = rememberLazyGridState(); val columns = 5
    val sorts = listOf("Newest", "A-Z", "Rating")

    fun reorder() { shown = when (sort) { 1 -> items.sortedBy { it.title.lowercase() }; 2 -> items.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }; else -> items }.toList(); index = 0 }
    LaunchedEffect(Unit) { categories = listOf(Category("", "All")) + runCatching { loadCategories(config, credentials, type) }.getOrDefault(emptyList()); items = runCatching { loadContent(config, credentials, type) }.getOrDefault(emptyList()); reorder(); focus.requestFocus() }
    LaunchedEffect(category) { categoryState.animateScrollToItem(category); val id = categories.getOrNull(category)?.category_id; items = runCatching { loadContent(config, credentials, type, id) }.getOrDefault(emptyList()); reorder() }
    LaunchedEffect(index) { if (zone == 2 && shown.isNotEmpty()) gridState.animateScrollToItem(index) }

    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) false else when (e.key) {
            Key.DirectionUp -> { when (zone) { 0 -> category = (category - 1).coerceAtLeast(0); 1 -> Unit; 2 -> if (index < columns) zone = 1 else index -= columns }; true }
            Key.DirectionDown -> { when (zone) { 0 -> category = (category + 1).coerceAtMost(categories.lastIndex); 1 -> zone = 2; 2 -> index = (index + columns).coerceAtMost(shown.lastIndex.coerceAtLeast(0)) }; true }
            Key.DirectionLeft -> { when (zone) { 1 -> sort = (sort - 1).coerceAtLeast(0); 2 -> if (index % columns == 0) zone = 0 else index--; else -> Unit }; true }
            Key.DirectionRight -> { when (zone) { 0 -> zone = 2; 1 -> sort = (sort + 1).coerceAtMost(sorts.lastIndex); 2 -> index = (index + 1).coerceAtMost(shown.lastIndex.coerceAtLeast(0)) }; true }
            Key.Enter, Key.DirectionCenter -> { if (zone == 1) reorder() else if (zone == 2) shown.getOrNull(index)?.let(onOpen); true }
            Key.Back -> { onBack(); true }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxSize().padding(28.dp)) {
            Column(Modifier.width(190.dp)) {
                Image(painterResource(R.drawable.flix_logo), "Flix Town", Modifier.width(130.dp).height(55.dp)); Spacer(Modifier.height(14.dp))
                Text("CATEGORIES", color = FlixMuted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(10.dp))
                LazyColumn(state = categoryState, verticalArrangement = Arrangement.spacedBy(5.dp)) { itemsIndexed(categories) { i, cat ->
                    val chosen = zone == 0 && category == i
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (chosen) FlixRed else Color.Transparent).border(if (chosen) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp)).padding(12.dp)) { Text(cat.category_name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal) }
                } }
            }
            Spacer(Modifier.width(26.dp)); Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (type == ContentType.MOVIE) "Movies" else "Series", fontSize = 32.sp, fontWeight = FontWeight.Black); Spacer(Modifier.weight(1f)); Text("Sort by", color = FlixMuted, fontSize = 12.sp); Spacer(Modifier.width(10.dp)); sorts.forEachIndexed { i, label ->
                    val chosen = zone == 1 && sort == i
                    Box(Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(18.dp)).background(if (chosen) FlixRed else Color(0xFF24262D)).border(if (chosen) 2.dp else 0.dp, Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 17.dp, vertical = 9.dp)) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                } }
                Spacer(Modifier.height(17.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState, horizontalArrangement = Arrangement.spacedBy(15.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(shown.size, key = { "${shown[it].type}-${shown[it].id}" }) { i -> Column { Poster(shown[i], zone == 2 && index == i, 142.dp, 205.dp); Text(shown[i].title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(142.dp).padding(top = 7.dp)) } }
                }
            }
        }
    }
}

@Composable
private fun Poster(item: ContentItem, selected: Boolean, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val scale by animateFloatAsState(if (selected) 1.09f else 1f, tween(120), label = "poster")
    AsyncImage(item.poster, item.title, Modifier.size(width, height).scale(scale).clip(RoundedCornerShape(10.dp)).background(Color(0xFF24262B)).border(if (selected) 4.dp else 1.dp, if (selected) FlixRed else Color(0xFF444650), RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
}

@Composable
fun DetailsScreen(config: AppConfig, credentials: Credentials, item: ContentItem, onPlay: (PlayRequest) -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }; var plot by remember { mutableStateOf(item.plot) }; var backdrop by remember { mutableStateOf(item.backdrop) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }; var selected by remember { mutableIntStateOf(0) }; var zone by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    LaunchedEffect(item.id) {
        if (item.type == ContentType.MOVIE) runCatching { loadMovieDetails(config, credentials, item) }.onSuccess { plot = it.plot; backdrop = it.backdrop }
        else runCatching { loadSeriesDetails(config, credentials, item) }.onSuccess { details -> plot = details.plot; backdrop = details.backdrop; episodes = details.episodes.values.flatten() }
        focus.requestFocus()
    }
    LaunchedEffect(selected) { if (zone == 1 && episodes.isNotEmpty()) list.animateScrollToItem(selected) }
    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) false else when (e.key) {
            Key.DirectionDown -> { if (episodes.isNotEmpty()) zone = 1; true }
            Key.DirectionUp -> { if (zone == 1) zone = 0; true }
            Key.DirectionLeft -> { if (zone == 1) selected = (selected - 1).coerceAtLeast(0); true }
            Key.DirectionRight -> { if (zone == 1) selected = (selected + 1).coerceAtMost(episodes.lastIndex); true }
            Key.Enter, Key.DirectionCenter -> { if (zone == 0 && item.type == ContentType.MOVIE) onPlay(PlayRequest(streamUrl(config, credentials, item.type, item.id, item.extension), item.title, item.poster, item, "movie-${item.id}", item.type, item.id, item.extension)) else episodes.getOrNull(selected)?.let { ep -> onPlay(PlayRequest(streamUrl(config, credentials, ContentType.SERIES, ep.id, ep.extension), "${item.title} - ${ep.title}", ep.poster ?: item.poster, item, "series-${item.id}-${ep.id}", ContentType.SERIES, ep.id, ep.extension)) }; true }
            Key.Back -> { onBack(); true }
            else -> false
        }
    }) {
        AsyncImage(backdrop ?: item.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .48f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(FlixBlack, FlixBlack.copy(.76f), Color.Transparent))))
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Text("‹  BACK", color = FlixMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(34.dp)); Text(item.title, fontSize = 42.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(680.dp), maxLines = 2)
            if (item.rating.isNotBlank()) Text("★ ${item.rating}", color = Color(0xFFFFD166), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(plot.ifBlank { "No description available." }, color = Color.LightGray, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 5, modifier = Modifier.width(650.dp).padding(vertical = 18.dp))
            val playFocused = zone == 0
            Box(Modifier.clip(RoundedCornerShape(9.dp)).background(if (playFocused) FlixRed else Color.White).border(if (playFocused) 3.dp else 0.dp, Color.White, RoundedCornerShape(9.dp)).padding(horizontal = 28.dp, vertical = 12.dp)) { Text(if (item.type == ContentType.MOVIE) "▶  PLAY" else "SELECT EPISODE", color = if (playFocused) Color.White else Color.Black, fontWeight = FontWeight.Black) }
            if (episodes.isNotEmpty()) { Spacer(Modifier.height(24.dp)); Text("Episodes", fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(12.dp)) { itemsIndexed(episodes) { i, ep ->
                val chosen = zone == 1 && selected == i
                Column(Modifier.width(190.dp).clip(RoundedCornerShape(9.dp)).background(if (chosen) FlixRed else FlixPanel).border(if (chosen) 3.dp else 1.dp, if (chosen) Color.White else Color.DarkGray, RoundedCornerShape(9.dp)).padding(12.dp)) { Text("S${ep.season} E${ep.episodeNumber}", fontSize = 11.sp, fontWeight = FontWeight.Black); Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            } } }
        }
    }
}

@Composable
fun RenewalScreen(account: AccountInfo) {
    val context = LocalContext.current; var selected by remember { mutableIntStateOf(0) }; val focus = remember { FocusRequester() }; val plans = listOf("1 Month", "3 Months", "6 Months", "12 Months")
    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) false else when (e.key) { Key.DirectionLeft -> { selected = (selected - 1).coerceAtLeast(0); true }; Key.DirectionRight -> { selected = (selected + 1).coerceAtMost(plans.lastIndex); true }; Key.Enter, Key.DirectionCenter -> { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cash.app/\$streamtownofficial"))); true }; else -> true }
    }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.flix_logo), "Flix Town", Modifier.width(190.dp)); Spacer(Modifier.height(20.dp)); Text("Your account has expired", fontSize = 34.sp, fontWeight = FontWeight.Black); Text("Select a plan, then scan or open Cash App. Include the phone number connected to your account.", color = FlixMuted, fontSize = 14.sp, modifier = Modifier.padding(10.dp)); Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { plans.forEachIndexed { i, plan -> Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected == i) FlixRed else FlixPanel).border(if (selected == i) 3.dp else 1.dp, if (selected == i) Color.White else Color.DarkGray, RoundedCornerShape(12.dp)).padding(horizontal = 28.dp, vertical = 20.dp)) { Text(plan, fontWeight = FontWeight.Black) } } }
            Spacer(Modifier.height(25.dp)); Text("Press OK to pay with Cash App", fontWeight = FontWeight.Bold); Text("Current status: ${account.status}", color = FlixMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun FocusButton(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick, modifier.onFocusChanged { focused = it.isFocused }.border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(9.dp)).focusable(), colors = ButtonDefaults.buttonColors(containerColor = if (focused) FlixRed else if (secondary) Color(0xFF30323A) else FlixRed)) { Text(text, fontWeight = FontWeight.Black, fontSize = 12.sp) }
}

private fun qrBitmap(text: String, size: Int = 520): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
