package com.flixtown.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.gson.Gson
import kotlinx.coroutines.launch

val FlixRed = Color(0xFFE50914)
val FlixBlack = Color(0xFF050507)
val FlixPanel = Color(0xFF15161B)
val FlixMuted = Color(0xFF9297A4)

enum class Route { LOGIN, HOME, MOVIES, SERIES, DETAILS, PLAYER, RENEW }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = FlixRed, background = FlixBlack)) {
                Box(Modifier.fillMaxSize().background(FlixBlack)) { FlixTownApp() }
            }
        }
    }
}

@Composable
private fun FlixTownApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(if (loadCredentials(context) == null) Route.LOGIN else Route.HOME) }
    var config by remember { mutableStateOf(loadCachedConfig(context)) }
    var credentials by remember { mutableStateOf(loadCredentials(context)) }
    var account by remember { mutableStateOf(AccountInfo()) }
    var selected by remember { mutableStateOf<ContentItem?>(null) }
    var request by remember { mutableStateOf<PlayRequest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(credentials != null) }

    fun open(item: ContentItem) { selected = item; route = Route.DETAILS }
    fun play(item: PlayRequest) { request = item; route = Route.PLAYER }

    LaunchedEffect(Unit) {
        runCatching {
            val fresh = Gson().fromJson(Api.objectFrom(PANEL_CONFIG_URL), AppConfig::class.java)
            config = fresh
            cacheConfig(context, fresh)
            credentials?.let {
                val result = authenticate(fresh, it)
                account = result.second
                if (!result.first) {
                    clearCredentials(context)
                    credentials = null
                    route = Route.LOGIN
                } else if (isExpired(result.second)) route = Route.RENEW
            }
        }.onFailure { if (config == null) message = "Unable to connect. Check your internet connection." }
        checking = false
    }

    BackHandler(enabled = route !in listOf(Route.HOME, Route.LOGIN, Route.RENEW)) {
        route = when (route) {
            Route.PLAYER, Route.DETAILS -> selected?.type?.let { if (it == ContentType.MOVIE) Route.MOVIES else Route.SERIES } ?: Route.HOME
            else -> Route.HOME
        }
    }

    when {
        checking && config == null -> SplashScreen()
        route == Route.LOGIN -> LoginScreen(config, message) { user, pass ->
            scope.launch {
                message = null
                val activeConfig = config ?: return@launch
                runCatching { authenticate(activeConfig, Credentials(user.trim(), pass)) }
                    .onSuccess { (ok, info) ->
                        if (ok) {
                            saveCredentials(context, Credentials(user.trim(), pass))
                            credentials = Credentials(user.trim(), pass)
                            account = info
                            route = if (isExpired(info)) Route.RENEW else Route.HOME
                        } else message = "The username or password is incorrect."
                    }.onFailure { message = "Sign in failed. Check your connection." }
            }
        }
        route == Route.RENEW -> RenewalScreen(account)
        config == null || credentials == null -> ConnectionScreen(message ?: "Configuration unavailable")
        route == Route.HOME -> HomeScreen(config!!, credentials!!, account, ::open) { route = it }
        route == Route.MOVIES -> CatalogScreen(config!!, credentials!!, ContentType.MOVIE, ::open) { route = Route.HOME }
        route == Route.SERIES -> CatalogScreen(config!!, credentials!!, ContentType.SERIES, ::open) { route = Route.HOME }
        route == Route.DETAILS && selected != null -> DetailsScreen(config!!, credentials!!, selected!!, ::play) {
            route = if (selected!!.type == ContentType.MOVIE) Route.MOVIES else Route.SERIES
        }
        route == Route.PLAYER && request != null -> PlayerScreen(request!!) { route = Route.DETAILS }
        else -> HomeScreen(config!!, credentials!!, account, ::open) { route = it }
    }
}

private fun loadCachedConfig(context: android.content.Context): AppConfig? = runCatching {
    val value = context.getSharedPreferences("bootstrap", 0).getString("config", null) ?: return@runCatching null
    Gson().fromJson(value, AppConfig::class.java)
}.getOrNull()

private fun cacheConfig(context: android.content.Context, config: AppConfig) {
    context.getSharedPreferences("bootstrap", 0).edit().putString("config", Gson().toJson(config)).apply()
}
