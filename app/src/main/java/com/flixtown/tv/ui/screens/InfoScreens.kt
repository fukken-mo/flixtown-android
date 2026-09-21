package com.flixtown.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.ui.components.FlixButton
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtTextSecondary

@Composable
private fun FullScreenMessage(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FtBackground)
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 640.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = FtTextSecondary,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Box(modifier = Modifier.height(8.dp))
                FlixButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun MaintenanceScreen(message: String?) {
    FullScreenMessage(
        title = "Flix Town is being updated",
        message = message ?: "We're performing scheduled maintenance. Please check back shortly."
    )
}

@Composable
fun UpdateRequiredScreen(updateUrl: String?) {
    FullScreenMessage(
        title = "Update required",
        message = buildString {
            append("A new version of Flix Town is required to continue.")
            if (!updateUrl.isNullOrBlank()) {
                append("\n\n")
                append(updateUrl)
            }
        }
    )
}

@Composable
fun ConfigUnavailableScreen(onRetry: () -> Unit) {
    FullScreenMessage(
        title = "Can't reach Flix Town",
        message = "Check that your TV is connected to the internet, then try again.",
        actionLabel = "Retry",
        onAction = onRetry
    )
}

@Composable
fun RenewalRequiredScreen(xtreamStatus: String) {
    // Full renewal flow (Cash App QR, plan selection, phone number, admin approval)
    // is a later milestone. For now this blocks catalog access and states why,
    // without ever touching the customer's stored credentials.
    FullScreenMessage(
        title = "Your subscription has ended",
        message = "Account status: $xtreamStatus\n\nRenewal is coming soon. Please contact support to reactivate your subscription."
    )
}
