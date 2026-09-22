package com.flixtown.tv.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtTextPrimary
import com.flixtown.tv.ui.theme.FtTextSecondary

/**
 * TV-styled exit confirmation — never a phone AlertDialog. Default focus
 * lands on Cancel; LEFT/RIGHT moves between Cancel/Exit via ordinary
 * spatial focus search (they're the only two focusable siblings in the
 * Row); D-pad center activates whichever is focused; BACK dismisses like
 * Cancel. Cancel/Exit reuse FlixFocusSurface as-is — its focused state is
 * already a strong red fill, exactly the "focused button: red filled,
 * unfocused: dark surface" treatment this dialog needs.
 */
@Composable
fun ExitConfirmationDialog(onCancel: () -> Unit, onExit: () -> Unit) {
    BackHandler { onCancel() }

    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocusRequester.requestFocus() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FtSurface)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "Exit Flix Town?", style = MaterialTheme.typography.titleLarge, color = FtTextPrimary)
            Text(
                text = "Are you sure you want to exit?",
                style = MaterialTheme.typography.bodyMedium,
                color = FtTextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FlixFocusSurface(
                    onClick = onCancel,
                    modifier = Modifier.width(140.dp).focusRequester(cancelFocusRequester)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        color = FtTextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                FlixFocusSurface(
                    onClick = onExit,
                    modifier = Modifier.width(140.dp)
                ) {
                    Text(
                        text = "Exit",
                        style = MaterialTheme.typography.labelLarge,
                        color = FtTextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
