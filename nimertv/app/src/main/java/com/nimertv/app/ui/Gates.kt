package com.nimertv.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nimertv.app.BuildConfig
import com.nimertv.app.R
import com.nimertv.app.data.FailureKind
import com.nimertv.app.data.InstallEvents
import com.nimertv.app.data.InstallStatus
import com.nimertv.app.data.PanelConfig
import com.nimertv.app.data.Support
import com.nimertv.app.data.UpdateException
import com.nimertv.app.data.UpdateFailure
import com.nimertv.app.data.UpdateInfo
import com.nimertv.app.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Panel-controlled splash: a cached holiday/default image, or the artwork bundled in the APK. */
@Composable
fun SplashScreen(file: File?) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Image(painterResource(R.drawable.splash_default), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (file != null) {
            AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GateLayout(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Screen), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(560.dp)) {
            Image(painterResource(R.drawable.nimer_logo), "Nimer TV", Modifier.size(120.dp))
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun FatalScreen(message: String, onRetry: () -> Unit) {
    GateLayout { Message("Can't connect", message, actionLabel = "Try again", onAction = onRetry) }
}

@Composable
fun MaintenanceScreen(message: String, support: Support?, onRetry: () -> Unit) {
    GateLayout {
        Message(
            "We'll be right back",
            message.ifBlank { "Nimer TV is under maintenance. Please try again shortly." },
            actionLabel = "Try again",
            onAction = onRetry,
        )
        SupportLine(support)
    }
}

@Composable
fun SigningInScreen() {
    GateLayout { Loading(label = "Signing in…") }
}

@Composable
fun SupportLine(support: Support?) {
    if (support == null || support.isEmpty) return
    val parts = listOfNotNull(
        support.whatsapp.takeIf { it.isNotEmpty() }?.let { "WhatsApp $it" },
        support.phone.takeIf { it.isNotEmpty() }?.let { "Phone $it" },
        support.email.takeIf { it.isNotEmpty() },
        support.website.takeIf { it.isNotEmpty() },
    )
    Text("Support: " + parts.joinToString("   ·   "), style = Type.Caption.copy(textAlign = TextAlign.Center), modifier = Modifier.padding(top = 12.dp))
}

@Composable
fun LoginScreen(config: PanelConfig?, initialUser: String, message: String?, onSubmit: (String, String) -> Unit) {
    var user by rememberSaveable { mutableStateOf(initialUser) }
    var pass by rememberSaveable { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val buttonFocus = remember { FocusRequester() }

    Row(Modifier.fillMaxSize().background(Palette.Screen)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.nimer_logo), "Nimer TV", Modifier.size(230.dp))
            Spacer(Modifier.height(12.dp))
            Text("LIVE TV  ·  MOVIES  ·  SERIES", style = Type.Overline)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(end = 64.dp), verticalArrangement = Arrangement.Center) {
            Text("Sign in", style = Type.Display)
            Spacer(Modifier.height(6.dp))
            Text(
                config?.loginMessage?.takeIf { it.isNotBlank() } ?: "Use the username and password from your Nimer TV subscription.",
                style = Type.Body, color = Palette.TextDim,
            )
            Spacer(Modifier.height(22.dp))
            TvTextField(user, { user = it }, "Username", Modifier.focusRequester(userFocus), imeAction = ImeAction.Next,
                onImeAction = { runCatching { passFocus.requestFocus() } })
            Spacer(Modifier.height(12.dp))
            TvTextField(pass, { pass = it }, "Password", Modifier.focusRequester(passFocus), password = true,
                onImeAction = { runCatching { buttonFocus.requestFocus() } })
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message, style = Type.Label, color = Palette.Danger, modifier = Modifier.width(360.dp))
            }
            Spacer(Modifier.height(20.dp))
            PillButton("Sign in", { onSubmit(user, pass) }, Modifier.focusRequester(buttonFocus), primary = true)
            SupportLine(config?.support)
            Spacer(Modifier.height(18.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", style = Type.Caption, color = Palette.TextFaint)
        }
    }
    LaunchedEffect(Unit) { runCatching { (if (initialUser.isEmpty()) userFocus else passFocus).requestFocus() } }
}

private sealed class UpdatePhase {
    data class Downloading(val percent: Int) : UpdatePhase()
    data object Installing : UpdatePhase()
    data class Stopped(val failure: UpdateFailure) : UpdatePhase()
}

/**
 * Downloads, verifies and installs an update. A mandatory update cannot be dismissed: the
 * only ways out are installing it, retrying, or leaving the app.
 */
@Composable
fun UpdateScreen(
    update: UpdateInfo,
    mandatory: Boolean,
    updater: Updater,
    onLater: () -> Unit,
    onUnavailable: () -> Unit,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Downloading(0)) }
    var attempt by remember { mutableIntStateOf(0) }
    val install by InstallEvents.status.collectAsState()
    val primaryFocus = remember { FocusRequester() }

    fun run() {
        phase = UpdatePhase.Downloading(0)
        InstallEvents.status.value = InstallStatus.Idle
        attempt++
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { updater.download(update) { p -> phase = UpdatePhase.Downloading(p) } }
                phase = UpdatePhase.Installing
                withContext(Dispatchers.IO) { updater.install(apk) }
            } catch (e: UpdateException) {
                phase = UpdatePhase.Stopped(e.failure)
            }
        }
    }

    // The download starts on its own; the customer only confirms Android's install prompt.
    LaunchedEffect(update.versionCode) { run() }

    // Results from the system installer (cancelled, failed).
    LaunchedEffect(install) {
        when (val s = install) {
            InstallStatus.Cancelled -> phase = UpdatePhase.Stopped(UpdateFailure(FailureKind.INSTALL, "The installation was cancelled."))
            is InstallStatus.Failed -> phase = UpdatePhase.Stopped(UpdateFailure(FailureKind.INSTALL, s.message))
            else -> Unit
        }
    }

    BackHandler { if (mandatory) onExit() else onLater() }

    GateLayout {
        Text(if (mandatory) "Update required" else "Update available", style = Type.Display)
        Spacer(Modifier.height(6.dp))
        Text(
            "Version ${update.versionName.ifEmpty { update.versionCode.toString() }}  ·  installed ${BuildConfig.VERSION_NAME}",
            style = Type.Label, color = Palette.PurpleLight,
        )
        if (update.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(update.notes, style = Type.Paragraph.copy(textAlign = TextAlign.Center), maxLines = 5)
        }
        Spacer(Modifier.height(20.dp))

        when (val p = phase) {
            is UpdatePhase.Downloading -> {
                Text("Downloading update… ${p.percent}%", style = Type.Body)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth(p.percent / 100f).height(6.dp).background(Palette.Purple))
                }
                if (!mandatory) {
                    Spacer(Modifier.height(20.dp))
                    PillButton("Later", onLater, Modifier.focusRequester(primaryFocus))
                    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }
                }
            }
            UpdatePhase.Installing -> {
                Text(
                    "The update is verified. Confirm the installation when Android asks.\n" +
                        "If Android asks for permission to install apps from Nimer TV, allow it and return here.",
                    style = Type.Body.copy(textAlign = TextAlign.Center), color = Palette.TextDim,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Install again", ::run, Modifier.focusRequester(primaryFocus), primary = true)
                    if (!mandatory) PillButton("Later", onLater)
                }
                LaunchedEffect(attempt) { runCatching { primaryFocus.requestFocus() } }
            }
            is UpdatePhase.Stopped -> {
                Text(p.failure.message, style = Type.Body.copy(textAlign = TextAlign.Center), color = Palette.Danger)
                if (mandatory && p.failure.kind != FailureKind.NOT_PUBLISHED) {
                    Spacer(Modifier.height(6.dp))
                    Text("This update is required to keep using Nimer TV.", style = Type.Caption)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Try again", ::run, Modifier.focusRequester(primaryFocus), primary = true)
                    when {
                        // The announced version is not on the server: nothing to install, so do not lock the app.
                        p.failure.kind == FailureKind.NOT_PUBLISHED -> PillButton("Continue", onUnavailable)
                        mandatory -> PillButton("Exit", onExit)
                        else -> PillButton("Later", onLater)
                    }
                }
                LaunchedEffect(p) { runCatching { primaryFocus.requestFocus() } }
            }
        }
    }
}
