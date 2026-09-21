package com.flixtown.tv.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flixtown.tv.BuildConfig
import com.flixtown.tv.data.AuthRepository
import com.flixtown.tv.data.ConfigRepository
import com.flixtown.tv.data.XtreamRepository
import com.flixtown.tv.data.model.XtreamAuthResult
import com.flixtown.tv.security.SecureCredentialStore
import com.flixtown.tv.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the startup flow: load cached config immediately, refresh it in the
 * background, then check stored credentials and route to Login, a required
 * Maintenance/Update screen, Renewal, or Home. Never blocks longer than a
 * single network round trip, and only on first-ever launch (no cache yet).
 */
class StartupViewModel(
    private val configRepository: ConfigRepository,
    private val xtreamRepository: XtreamRepository,
    private val authRepository: AuthRepository,
    private val secureStore: SecureCredentialStore
) : ViewModel() {

    private val _route = MutableStateFlow<Route>(Route.Loading)
    val route: StateFlow<Route> = _route

    fun start() {
        viewModelScope.launch {
            var config = configRepository.getCached()
            if (config == null) {
                // Nothing cached yet (first launch): we have no choice but to wait for the network.
                config = configRepository.refresh().getOrNull()
            } else {
                // Cached config exists: refresh in the background without blocking routing.
                launch { configRepository.refresh() }
            }

            if (config == null) {
                _route.value = Route.ConfigUnavailable
                return@launch
            }

            if (config.maintenanceMode) {
                _route.value = Route.Maintenance(config.maintenanceMessage)
                return@launch
            }

            val forceUpdate = config.forceUpdate || BuildConfig.VERSION_CODE < config.minAppVersionCode
            if (forceUpdate) {
                _route.value = Route.UpdateRequired(config.updateUrl, forced = true)
                return@launch
            }

            if (!authRepository.hasStoredSession()) {
                _route.value = Route.Login
                return@launch
            }

            val username = secureStore.getXtreamUsername()
            val password = secureStore.getXtreamPassword()
            if (username == null || password == null) {
                _route.value = Route.Login
                return@launch
            }

            when (val result = xtreamRepository.authenticate(config.xtreamBaseUrl, username, password)) {
                is XtreamAuthResult.Success -> {
                    _route.value = if (result.isActive) Route.Home else Route.RenewalRequired(result.status)
                }
                is XtreamAuthResult.InvalidCredentials -> {
                    // Credentials Xtream itself rejects (revoked/banned): stored data is left
                    // intact per policy, but the customer must re-authenticate to proceed.
                    _route.value = Route.Login
                }
                is XtreamAuthResult.NetworkError, is XtreamAuthResult.ServerError -> {
                    // Offline or the Xtream panel is briefly unreachable: don't lock out an
                    // already-paired customer over a transient network hiccup.
                    _route.value = Route.Home
                }
            }
        }
    }

    class Factory(
        private val configRepository: ConfigRepository,
        private val xtreamRepository: XtreamRepository,
        private val authRepository: AuthRepository,
        private val secureStore: SecureCredentialStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StartupViewModel(configRepository, xtreamRepository, authRepository, secureStore) as T
        }
    }
}
