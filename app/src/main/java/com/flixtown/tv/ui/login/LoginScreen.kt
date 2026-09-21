package com.flixtown.tv.ui.login

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.flixtown.tv.AppGraph
import com.flixtown.tv.core.BackendConstants
import com.flixtown.tv.ui.components.FlixButton
import com.flixtown.tv.ui.components.FlixFocusSurface
import com.flixtown.tv.ui.components.FlixTextField
import com.flixtown.tv.ui.theme.FtAccent
import com.flixtown.tv.ui.theme.FtBackground
import com.flixtown.tv.ui.theme.FtSurface
import com.flixtown.tv.ui.theme.FtTextSecondary

private enum class LoginTab(val label: String) {
    QrPairing("Pair with QR Code"),
    Manual("Sign in Manually")
}

@Composable
fun LoginScreen(
    graph: AppGraph,
    installationId: String,
    deviceModel: String,
    onAuthenticated: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(LoginTab.QrPairing) }
    val firstTabFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstTabFocus.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(FtBackground)
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(FtSurface)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "FLIX TOWN", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(24.dp))
            LoginTab.entries.forEachIndexed { index, tab ->
                FlixFocusSurface(
                    onClick = { selectedTab = tab },
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (index == 0) it.focusRequester(firstTabFocus) else it }
                ) {
                    Text(text = tab.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(48.dp)
        ) {
            when (selectedTab) {
                LoginTab.QrPairing -> QrPairingTab(
                    pairingRepository = graph.pairingRepository,
                    installationId = installationId,
                    deviceModel = deviceModel,
                    onAuthenticated = onAuthenticated
                )
                LoginTab.Manual -> ManualLoginTab(
                    authRepository = graph.authRepository,
                    installationId = installationId,
                    deviceModel = deviceModel,
                    onAuthenticated = onAuthenticated
                )
            }
        }
    }
}

@Composable
private fun QrPairingTab(
    pairingRepository: com.flixtown.tv.data.PairingRepository,
    installationId: String,
    deviceModel: String,
    onAuthenticated: () -> Unit
) {
    val viewModel: PairingViewModel = viewModel(
        factory = PairingViewModel.Factory(pairingRepository, installationId, deviceModel)
    )
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 760.dp)) {
        Text(text = "Activate with your phone", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Scan the QR code, or open the address below on your phone and enter the code shown.",
            style = MaterialTheme.typography.bodyMedium,
            color = FtTextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        when (val s = state) {
            PairingUiState.Starting -> Text(text = "Generating your code…", style = MaterialTheme.typography.bodyLarge)

            is PairingUiState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(16.dp)
                ) {
                    Image(
                        bitmap = s.qrCode,
                        contentDescription = "QR code to activate Flix Town",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = BackendConstants.ACTIVATION_HOST_LABEL, style = MaterialTheme.typography.titleLarge)
                    Text(text = "Enter this code:", style = MaterialTheme.typography.bodyMedium, color = FtTextSecondary)
                    Text(
                        text = s.session.publicCode,
                        style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 8.sp, fontWeight = FontWeight.Bold),
                        color = FtAccent
                    )
                }
            }

            PairingUiState.Confirming -> Text(text = "Confirming on this device…", style = MaterialTheme.typography.bodyLarge)

            PairingUiState.Success -> {
                LaunchedEffect(Unit) { onAuthenticated() }
                Text(text = "Success! Loading Flix Town…", style = MaterialTheme.typography.bodyLarge)
            }

            is PairingUiState.Error -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = s.message, style = MaterialTheme.typography.bodyLarge, color = FtAccent)
                FlixButton(text = "Try Again", onClick = { viewModel.start() })
            }
        }
    }
}

@Composable
private fun ManualLoginTab(
    authRepository: com.flixtown.tv.data.AuthRepository,
    installationId: String,
    deviceModel: String,
    onAuthenticated: () -> Unit
) {
    val viewModel: ManualLoginViewModel = viewModel(
        factory = ManualLoginViewModel.Factory(authRepository, installationId, deviceModel)
    )
    val state by viewModel.state.collectAsState()
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { usernameFocus.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 480.dp)) {
        Text(text = "Sign in", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Enter your Xtream username and password.",
            style = MaterialTheme.typography.bodyMedium,
            color = FtTextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        FlixTextField(
            value = viewModel.username,
            onValueChange = viewModel::onUsernameChange,
            label = "Username",
            focusRequester = usernameFocus,
            onImeAction = { passwordFocus.requestFocus() }
        )
        FlixTextField(
            value = viewModel.password,
            onValueChange = viewModel::onPasswordChange,
            label = "Password",
            isPassword = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            focusRequester = passwordFocus,
            onImeAction = { viewModel.submit(onAuthenticated) }
        )

        if (state is ManualLoginUiState.Error) {
            Text(
                text = (state as ManualLoginUiState.Error).message,
                style = MaterialTheme.typography.bodyMedium,
                color = FtAccent,
                textAlign = TextAlign.Start
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        FlixButton(
            text = if (state is ManualLoginUiState.Loading) "Signing in…" else "Sign In",
            onClick = { viewModel.submit(onAuthenticated) }
        )
    }
}
