package com.nimertv.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nimertv.app.BuildConfig
import com.nimertv.app.NimerApp
import com.nimertv.app.R
import com.nimertv.app.data.UpdateLogic
import com.nimertv.app.data.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AccountScreen(app: NimerApp, screen: Screen.Account, onRefresh: () -> Unit, onSignOut: () -> Unit, onShowUpdate: () -> Unit) {
    val session = app.session ?: return
    val config = app.panel?.config
    var confirmSignOut by remember { mutableStateOf(false) }
    val update = config?.update
    val updateAvailable = UpdateLogic.state(update, BuildConfig.VERSION_CODE, LocalContext.current.packageName) != UpdateState.NONE

    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("ACCOUNT", style = Type.Overline)
            Text(session.user, style = Type.Display)
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.fillMaxWidth(0.9f).background(Palette.Surface.copy(alpha = 0.75f), RoundedCornerShape(16.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field("Subscription", session.status + if (session.isTrial) " (trial)" else "")
                Field(
                    "Expires",
                    if (session.expiresAtSec > 0) SimpleDateFormat("d MMMM yyyy", Locale.US).format(Date(session.expiresAtSec * 1000)) else "No expiry date",
                )
                if (session.maxConnections.isNotEmpty()) Field("Screens", session.maxConnections)
                Field("App version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            }
            Spacer(Modifier.height(16.dp))
            Text("HOME LAYOUT", style = Type.Overline)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStyle.names.forEachIndexed { index, name ->
                    PillButton(
                        name, { app.chooseHomeStyle(index) },
                        Modifier.rememberFocus(screen.focus, "home:$index"), primary = app.homeStyle == index,
                    )
                }
            }
            SupportLine(config?.support)
        }
        Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.nimer_logo), "Nimer TV", Modifier.size(150.dp))
            if (updateAvailable && update != null) {
                PillButton("Update to ${update.versionName.ifEmpty { update.versionCode.toString() }}", onShowUpdate,
                    Modifier.rememberFocus(screen.focus, "update"), primary = true)
            }
            PillButton("Refresh content", onRefresh, Modifier.rememberFocus(screen.focus, "refresh"), icon = Icons.Filled.Refresh)
            PillButton("Sign out", { confirmSignOut = true }, Modifier.rememberFocus(screen.focus, "signout"), icon = Icons.Filled.ExitToApp)
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            detail = "Favorites and watch history stay on this device for this account.",
            confirmLabel = "Sign out",
            onConfirm = onSignOut,
            onDismiss = { confirmSignOut = false },
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text(label, style = Type.Label, color = Palette.TextDim, modifier = Modifier.width(150.dp))
        Text(value, style = Type.Label.copy(fontWeight = FontWeight.SemiBold))
    }
}
