package com.flixtown.tv

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flixtown.tv.core.InstallationId
import com.flixtown.tv.ui.Route
import com.flixtown.tv.ui.home.HomeShellScreen
import com.flixtown.tv.ui.intro.IntroScreen
import com.flixtown.tv.ui.login.LoginScreen
import com.flixtown.tv.ui.screens.ConfigUnavailableScreen
import com.flixtown.tv.ui.screens.MaintenanceScreen
import com.flixtown.tv.ui.screens.RenewalRequiredScreen
import com.flixtown.tv.ui.screens.StartupScreen
import com.flixtown.tv.ui.screens.UpdateRequiredScreen
import com.flixtown.tv.ui.startup.StartupViewModel
import com.flixtown.tv.ui.theme.FlixTownTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val graph = (application as FlixTownApp).graph
        val installationId = InstallationId.get(this)
        val deviceModel = Build.MODEL ?: "Android TV"

        setContent {
            FlixTownTheme {
                val startupViewModel: StartupViewModel = viewModel(
                    factory = StartupViewModel.Factory(
                        graph.configRepository,
                        graph.xtreamRepository,
                        graph.authRepository,
                        graph.secureCredentialStore,
                        graph.accountStatusStore
                    )
                )

                LaunchedEffect(Unit) { startupViewModel.start() }
                val route by startupViewModel.route.collectAsState()

                when (val current = route) {
                    Route.Loading -> StartupScreen()
                    is Route.Intro -> IntroScreen(
                        videoUrl = current.videoUrl,
                        onFinished = { startupViewModel.onIntroFinished() }
                    )
                    Route.ConfigUnavailable -> ConfigUnavailableScreen(onRetry = { startupViewModel.start() })
                    is Route.Maintenance -> MaintenanceScreen(message = current.message)
                    is Route.UpdateRequired -> UpdateRequiredScreen(updateUrl = current.updateUrl)
                    Route.Login -> LoginScreen(
                        graph = graph,
                        installationId = installationId,
                        deviceModel = deviceModel,
                        onAuthenticated = { startupViewModel.start() }
                    )
                    is Route.RenewalRequired -> RenewalRequiredScreen(xtreamStatus = current.xtreamStatus)
                    Route.Home -> HomeShellScreen(accountStatusStore = graph.accountStatusStore)
                }
            }
        }
    }
}
