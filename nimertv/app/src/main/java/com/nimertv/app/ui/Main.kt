package com.nimertv.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nimertv.app.NimerApp
import com.nimertv.app.PlayRequest
import com.nimertv.app.PlayerActivity
import com.nimertv.app.data.Announcement
import com.nimertv.app.data.Movie
import com.nimertv.app.data.ProviderException
import com.nimertv.app.data.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A destination plus the UI state that must survive while other screens are on top of it. */
sealed class Screen(initialFocus: Any?) {
    val focus = FocusMemory(initialFocus)
    private val listStates = HashMap<String, LazyListState>()
    private val gridStates = HashMap<String, LazyGridState>()
    fun listState(key: String) = listStates.getOrPut(key) { LazyListState() }
    fun gridState(key: String) = gridStates.getOrPut(key) { LazyGridState() }

    class Home : Screen("hero:live")
    class Live : Screen("cat:$ALL") {
        var category by mutableStateOf(ALL)
    }
    class Movies : Screen("cat:$ALL") {
        var category by mutableStateOf(ALL)
    }
    class SeriesList : Screen("cat:$ALL") {
        var category by mutableStateOf(ALL)
    }
    class MovieDetail(val movie: Movie) : Screen("play")
    class SeriesDetail(val series: Series) : Screen("play") {
        var season by mutableIntStateOf(-1)
    }
    class Search : Screen("query") {
        var query by mutableStateOf("")
    }
    class Account : Screen("refresh")

    companion object {
        const val ALL = "__all"
        const val FAVORITES = "__favorites"
        const val RECENT = "__recent"
    }
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home())
    val top: Screen get() = stack.last()
    fun push(screen: Screen) {
        stack.add(screen)
    }
    fun pop(): Boolean = if (stack.size > 1) {
        stack.removeAt(stack.lastIndex)
        true
    } else {
        false
    }
    fun home() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

sealed class Loadable<out T> {
    data object Loading : Loadable<Nothing>()
    data class Ready<T>(val data: T) : Loadable<T>()
    data class Failed(val message: String) : Loadable<Nothing>()

    val value: T? get() = (this as? Ready)?.data
}

fun friendlyError(e: Throwable): String = when (e) {
    is ProviderException -> e.message ?: "The TV service is not responding."
    is IOException -> "The TV service could not be reached. Check the internet connection."
    else -> "Something went wrong. Please try again."
}

/** Loads [loader] off the main thread and exposes loading / ready / failed; re-runs when a key changes. */
@Composable
fun <T> rememberLoadable(vararg keys: Any?, loader: suspend () -> T): State<Loadable<T>> =
    produceState<Loadable<T>>(Loadable.Loading, *keys) {
        value = Loadable.Loading
        value = try {
            Loadable.Ready(withContext(Dispatchers.Default) { loader() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loadable.Failed(friendlyError(e))
        }
    }

fun play(context: Context, request: PlayRequest) {
    (context.applicationContext as NimerApp).playRequest = request
    context.startActivity(Intent(context, PlayerActivity::class.java))
}

@Composable
fun MainScreens(app: NimerApp, onSignOut: () -> Unit, onShowUpdate: () -> Unit, onExit: () -> Unit) {
    // During the fade to the sign-in screen the session is already gone.
    val library = app.library ?: return
    val catalog = app.catalog ?: return
    val nav = remember(library) { Nav() }
    var confirmExit by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    BackHandler { if (!nav.pop()) confirmExit = true }

    Box(Modifier.fillMaxSize().background(Palette.Screen)) {
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.985f)) togetherWith fadeOut(tween(140)) },
            label = "screen",
        ) { screen ->
            when (screen) {
                is Screen.Home -> HomeScreen(app, screen, nav, catalog, library, reload, onShowUpdate)
                is Screen.Live -> LiveScreen(screen, nav, catalog, library, reload)
                is Screen.Movies -> MoviesScreen(screen, nav, catalog, library, reload)
                is Screen.SeriesList -> SeriesScreen(screen, nav, catalog, library, reload)
                is Screen.MovieDetail -> MovieDetailScreen(screen, catalog, library)
                is Screen.SeriesDetail -> SeriesDetailScreen(screen, catalog, library)
                is Screen.Search -> SearchScreen(screen, nav, catalog, library)
                is Screen.Account -> AccountScreen(
                    app = app,
                    screen = screen,
                    onRefresh = {
                        catalog.invalidate()
                        reload++
                        nav.home()
                    },
                    onSignOut = onSignOut,
                    onShowUpdate = onShowUpdate,
                )
            }
        }
    }

    PopupAnnouncements(app)

    if (confirmExit) {
        ConfirmDialog(
            title = "Exit Nimer TV?",
            confirmLabel = "Exit",
            onConfirm = onExit,
            onDismiss = { confirmExit = false },
        )
    }
}

@Composable
fun DialogCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(420.dp).background(Palette.SurfaceHigh, RoundedCornerShape(18.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
fun ConfirmDialog(title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, detail: String? = null) {
    val cancel = remember { FocusRequester() }
    DialogCard(onDismiss) {
        Text(title, style = Type.Title.copy(textAlign = TextAlign.Center))
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(detail, style = Type.Body.copy(textAlign = TextAlign.Center), color = Palette.TextDim)
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Cancel", onDismiss, Modifier.focusRequester(cancel))
            PillButton(confirmLabel, onConfirm, primary = true)
        }
        LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    }
}

/** Panel announcements flagged as pop-ups, one at a time. Dismissible ones are remembered. */
@Composable
private fun PopupAnnouncements(app: NimerApp) {
    val announcements = app.panel?.config?.announcements.orEmpty()
    var closed by remember { mutableStateOf(setOf<String>()) }
    val now = System.currentTimeMillis()
    val current: Announcement = announcements.firstOrNull {
        it.popup && it.activeAt(now) && it.key !in closed && !(it.dismissible && app.sessionStore.isDismissed(it.key))
    } ?: return
    val ok = remember { FocusRequester() }
    val close = {
        if (current.dismissible) app.sessionStore.dismiss(current.key)
        closed = closed + current.key
    }
    DialogCard(onDismiss = close) {
        Text("ANNOUNCEMENT", style = Type.Overline)
        Spacer(Modifier.height(8.dp))
        if (current.title.isNotEmpty()) Text(current.title, style = Type.Title.copy(textAlign = TextAlign.Center))
        if (current.message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(current.message, style = Type.Paragraph.copy(textAlign = TextAlign.Center))
        }
        Spacer(Modifier.height(22.dp))
        PillButton("OK", close, Modifier.focusRequester(ok), primary = true)
        LaunchedEffect(current.key) { runCatching { ok.requestFocus() } }
    }
}
