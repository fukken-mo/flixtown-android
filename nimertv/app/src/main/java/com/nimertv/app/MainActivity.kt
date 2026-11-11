package com.nimertv.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.lifecycleScope
import com.nimertv.app.data.AuthResult
import com.nimertv.app.data.PanelSnapshot
import com.nimertv.app.data.UpdateInfo
import com.nimertv.app.data.UpdateLogic
import com.nimertv.app.data.UpdateState
import com.nimertv.app.ui.FatalScreen
import com.nimertv.app.ui.LoginScreen
import com.nimertv.app.ui.MainScreens
import com.nimertv.app.ui.MaintenanceScreen
import com.nimertv.app.ui.Palette
import com.nimertv.app.ui.SigningInScreen
import com.nimertv.app.ui.SplashScreen
import com.nimertv.app.ui.UpdateScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Stage {
    data object Splash : Stage()
    data class Fatal(val message: String) : Stage()
    data class Maintenance(val message: String) : Stage()
    data class Update(val info: UpdateInfo, val mandatory: Boolean) : Stage()
    data class Login(val message: String? = null, val user: String = "") : Stage()
    data object SigningIn : Stage()
    data object Main : Stage()
}

/**
 * Startup flow: splash → panel configuration → mandatory update → maintenance →
 * optional update → sign-in → home.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as NimerApp

    var stage by mutableStateOf<Stage>(Stage.Splash)
        private set

    private var lastPanelCheck = 0L
    private var refreshJob: Job? = null

    /** Optional update the customer postponed, and update versions that turned out not to be installable. */
    private var deferredVersion = 0
    private var unavailableVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The interface is English and always laid out left-to-right; Arabic provider text
            // is still shaped and ordered correctly inside each text element.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize().background(Palette.Background)) {
                    Crossfade(stage, animationSpec = tween(350), label = "stage") { current ->
                        when (current) {
                            Stage.Splash -> SplashScreen(app.splashStore.currentFile(app.panel))
                            is Stage.Fatal -> FatalScreen(current.message, onRetry = ::start)
                            is Stage.Maintenance -> MaintenanceScreen(current.message, app.panel?.config?.support, onRetry = ::start)
                            is Stage.Update -> UpdateScreen(
                                update = current.info,
                                mandatory = current.mandatory,
                                updater = app.updater,
                                onLater = {
                                    deferredVersion = current.info.versionCode
                                    route()
                                },
                                onUnavailable = {
                                    unavailableVersion = current.info.versionCode
                                    route()
                                },
                                onExit = ::finish,
                            )
                            is Stage.Login -> LoginScreen(
                                config = app.panel?.config,
                                initialUser = current.user,
                                message = current.message,
                                onSubmit = ::login,
                            )
                            Stage.SigningIn -> SigningInScreen()
                            Stage.Main -> MainScreens(app = app, onSignOut = ::signOut, onShowUpdate = ::showUpdate, onExit = ::finish)
                        }
                    }
                }
            }
        }
        start()
    }

    private fun start() {
        stage = Stage.Splash
        val shownAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.panelRepository.fetch() } }
            delay((MIN_SPLASH_MS - (SystemClock.elapsedRealtime() - shownAt)).coerceAtLeast(0))
            val snapshot = result.getOrNull()
            if (snapshot == null) {
                stage = Stage.Fatal("Nimer TV could not be reached. Check the internet connection and try again.")
                return@launch
            }
            applyPanel(snapshot)
            route()
            scheduleRefresh()
        }
    }

    private fun applyPanel(snapshot: PanelSnapshot) {
        app.panel = snapshot
        // A cached (offline) configuration is re-checked on the next resume.
        lastPanelCheck = if (snapshot.fresh) System.currentTimeMillis() else 1L
        // Artwork for the current and upcoming holidays is cached for the next launch.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { app.splashStore.sync(snapshot) } }
    }

    private fun updateState(): UpdateState {
        val update = app.panel?.config?.update ?: return UpdateState.NONE
        if (update.versionCode == unavailableVersion) return UpdateState.NONE
        return UpdateLogic.state(update, BuildConfig.VERSION_CODE, packageName)
    }

    /** Decides which screen the app must show for the current panel state. */
    private fun route() {
        val config = app.panel?.config ?: return
        val update = config.update
        val state = updateState()
        when {
            state == UpdateState.MANDATORY && update != null -> stage = Stage.Update(update, mandatory = true)
            config.maintenanceEnabled -> stage = Stage.Maintenance(config.maintenanceMessage)
            state == UpdateState.OPTIONAL && update != null && deferredVersion != update.versionCode ->
                stage = Stage.Update(update, mandatory = false)
            app.session != null -> stage = Stage.Main
            app.sessionStore.hasLogin -> resumeSession()
            else -> stage = Stage.Login()
        }
    }

    private fun showUpdate() {
        deferredVersion = 0
        route()
    }

    private fun resumeSession() {
        stage = Stage.SigningIn
        val store = app.sessionStore
        val user = store.user
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { app.client.authenticate(user, store.pass, store.lastServer) }
            when (result) {
                is AuthResult.Success -> {
                    app.signIn(result.session)
                    stage = Stage.Main
                }
                AuthResult.InvalidCredentials -> {
                    app.signOut()
                    stage = Stage.Login("Your saved sign-in is no longer accepted. Please sign in again.", user)
                }
                is AuthResult.AccountBlocked -> {
                    app.signOut()
                    stage = Stage.Login(blockedMessage(result.status), user)
                }
                is AuthResult.Unreachable -> {
                    // Keep the customer signed in through an outage; content screens offer Retry.
                    val restored = store.restore(app.panel?.config?.servers?.map { it.url }.orEmpty())
                    if (restored != null) {
                        app.signIn(restored, persist = false)
                        stage = Stage.Main
                    } else {
                        stage = Stage.Login(result.detail, user)
                    }
                }
            }
        }
    }

    private fun login(user: String, pass: String) {
        if (user.isBlank() || pass.isBlank()) {
            stage = Stage.Login("Enter your username and password.", user)
            return
        }
        stage = Stage.SigningIn
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { app.client.authenticate(user.trim(), pass, app.sessionStore.lastServer) }
            stage = when (result) {
                is AuthResult.Success -> {
                    app.signIn(result.session)
                    Stage.Main
                }
                AuthResult.InvalidCredentials -> Stage.Login("The username or password is incorrect.", user)
                is AuthResult.AccountBlocked -> Stage.Login(blockedMessage(result.status), user)
                is AuthResult.Unreachable -> Stage.Login(result.detail, user)
            }
        }
    }

    private fun blockedMessage(status: String) = when (status.lowercase()) {
        "expired" -> "This subscription has expired. Please renew to continue watching."
        "banned", "disabled" -> "This account has been disabled. Please contact support."
        else -> "This account is not active (status: $status). Please contact support."
    }

    private fun signOut() {
        app.signOut()
        stage = Stage.Login()
    }

    /** Re-reads the panel while the app stays open: DNS changes, holidays, updates, maintenance. */
    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(app.panel?.config?.refreshMillis ?: 3_600_000L)
                refreshPanel()
            }
        }
    }

    private suspend fun refreshPanel() {
        val snapshot = withContext(Dispatchers.IO) { runCatching { app.panelRepository.fetch() }.getOrNull() } ?: return
        if (!snapshot.fresh) return
        applyPanel(snapshot)
        // Never interrupt the splash, an update in progress or a sign-in attempt.
        val interruptible = stage is Stage.Main || stage is Stage.Login || stage is Stage.Maintenance
        val mustGate = updateState() == UpdateState.MANDATORY || snapshot.config.maintenanceEnabled
        if (interruptible && (mustGate || stage is Stage.Maintenance)) route()
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (stage != Stage.Splash && lastPanelCheck != 0L && UpdateLogic.shouldCheck(now, lastPanelCheck)) {
            lastPanelCheck = now
            lifecycleScope.launch { refreshPanel() }
        }
    }

    private companion object {
        const val MIN_SPLASH_MS = 1800L
    }
}
