package com.flixtown.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.BuildConfig
import com.flixtown.tv.core.CrashReporter
import com.flixtown.tv.ui.components.FlixButton
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurfaceElevated
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

/**
 * Temporary debug-only screen: shown before normal navigation when a saved
 * crash report exists (see [CrashReporter]) so a crash can be read/photographed
 * off a real TV with no Android Studio attached. Not for release builds.
 */
@Composable
fun DebugCrashReportScreen(
    report: CrashReporter.SavedReport,
    onContinue: () -> Unit,
    onClear: () -> Unit
) {
    val frames = remember(report.stackTrace) { report.stackTrace.lines().take(15) }

    Box(modifier = Modifier.fillMaxSize().background(FtBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Debug Crash Report — Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = FtAccent
            )
            Text(
                text = "This screen only appears because the previous run crashed. It is local-only and never sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = FtTextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            InfoRow("Route", report.route)
            InfoRow("Content type", report.contentType)
            InfoRow("Stream ID", report.streamId)
            InfoRow("Exception", report.exceptionClass)
            InfoRow("Message", report.message)

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Stack trace (first ${frames.size} lines):", style = MaterialTheme.typography.titleMedium, color = FtTextPrimary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(FtSurfaceElevated)
                    .padding(16.dp)
            ) {
                Column {
                    frames.forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodyMedium, color = FtTextPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FlixButton(text = "Continue to App", onClick = onContinue)
                FlixButton(text = "Clear Crash Report", onClick = onClear)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = FtTextSecondary
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = FtTextPrimary)
    }
}
