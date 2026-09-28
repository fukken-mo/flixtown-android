package com.flixtown.tv.compose

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = FlixRepository(applicationContext)
        setContent {
            FlixTownTheme {
                var account by remember { mutableStateOf(repo.savedAccount()) }
                var catalog by remember { mutableStateOf(BrowseCatalog()) }
                var details by remember { mutableStateOf<TvTitle?>(null) }
                val detailHistory = remember { mutableStateListOf<TvTitle>() }
                var error by remember { mutableStateOf("") }
                var loading by remember { mutableStateOf(account != null) }
                var refresh by remember { mutableIntStateOf(0) }
                // Initial load is the app-open refresh. Returning from the player keeps
                // the in-memory catalog and avoids four full Xtream requests.
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
                    account == null -> LoginScreen(repo) {
                        loading = true; account = it; details = null; detailHistory.clear()
                    }
                    catalog.featured == null && loading -> OpeningScreen()
                    catalog.featured == null && error.isNotBlank() -> OpeningScreen(
                        message = "Unable to connect. Check your connection and try again.",
                        onRetry = { refresh++ })
                    details?.kind == "movie" -> DetailScreen(
                        item = details!!, account = account!!, repo = repo, catalog = catalog,
                        onPlay = play,
                        onTrailer = { url ->
                            if (url.startsWith("https://")) {
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                catch (_: Exception) { /* No trailer handler installed on this TV. */ }
                            }
                        },
                        onSimilar = { selected -> details?.let { detailHistory.add(it) }; details = selected },
                        onBack = { details = if (detailHistory.isNotEmpty())
                            detailHistory.removeAt(detailHistory.lastIndex) else null }
                    )
                    details != null -> DetailsScreen(details!!, repo, account!!, play) { details = null }
                    else -> Box(Modifier.fillMaxSize()) {
                        BrowseShell(catalog = catalog, repo = repo,
                            onPlay = play,
                            onDetails = { detailHistory.clear(); details = it },
                            onRefresh = { refresh++ },
                            onSignOut = {
                                repo.signOut(); account = null; catalog = BrowseCatalog(); loading = false
                                details = null; detailHistory.clear()
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun OpeningScreen(message: String = "Preparing your screen…",
    onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().background(CinemaColor.Background)
        .padding(horizontal = 48.dp, vertical = 27.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("FLIX TOWN", color = CinemaColor.Accent, fontSize = 38.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
            Box(Modifier.width(58.dp).height(3.dp)
                .background(CinemaColor.Accent, RoundedCornerShape(50)))
            Text(message, color = CinemaColor.Muted, fontSize = 17.sp)
            if (onRetry != null) PremiumButton(onClick = onRetry) { Text("Try again") }
        }
    }
}
