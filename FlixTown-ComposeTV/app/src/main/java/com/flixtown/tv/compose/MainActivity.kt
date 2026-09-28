package com.flixtown.tv.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = FlixRepository(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFE52254), background = Color(0xFF090C16),
                surface = Color(0xFF151B2A), onSurface = Color.White
            )) {
                var account by remember { mutableStateOf(repo.savedAccount()) }
                var catalog by remember { mutableStateOf(BrowseCatalog()) }
                var details by remember { mutableStateOf<TvTitle?>(null) }
                var error by remember { mutableStateOf("") }
                var loading by remember { mutableStateOf(false) }
                var refresh by remember { mutableIntStateOf(0) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) refresh++
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }
                LaunchedEffect(account, refresh) {
                    val current = account ?: return@LaunchedEffect
                    loading = true; error = ""
                    try {
                        if (!repo.verify(current)) {
                            repo.signOut(); account = null; catalog = BrowseCatalog()
                            error = "Account is no longer active"
                        } else catalog = repo.browse(current)
                    } catch (e: Exception) {
                        error = e.message ?: "Could not refresh the catalog"
                    } finally { loading = false }
                }
                val play: (TvTitle) -> Unit = { title ->
                    if (title.streamUrl.isNotBlank()) {
                        startActivity(Intent(this, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_URL, title.streamUrl)
                            putExtra(PlayerActivity.EXTRA_TITLE, title.name)
                        })
                    } else details = title
                }
                when {
                    account == null -> LoginScreen(repo) { account = it; details = null }
                    details != null -> DetailsScreen(details!!, repo, account!!, play) { details = null }
                    else -> Box(Modifier.fillMaxSize()) {
                        HomeScreen(onPlay = play, onDetails = { details = it }, catalog = catalog)
                        if (catalog.featured == null && (loading || error.isNotBlank())) {
                            Column(Modifier.padding(start = 48.dp, top = 340.dp)
                                .background(Color(0xDD090C16)).padding(16.dp)) {
                                Text(if (loading) "Refreshing movies and series…" else error,
                                    color = Color.White, fontSize = 18.sp)
                                if (!loading && error.isNotBlank()) {
                                    Button(onClick = { refresh++ }) { Text("Retry") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
