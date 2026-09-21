package com.flixtown.tv.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flixtown.tv.BuildConfig
import com.flixtown.tv.data.AccountStatusStore
import com.flixtown.tv.data.AuthRepository
import com.flixtown.tv.data.ConfigRepository
import com.flixtown.tv.data.XtreamRepository
import com.flixtown.tv.data.model.RemoteConfig
import com.flixtown.tv.data.model.XtreamAuthResult
import com.flixtown.tv.security.SecureCredentialStore
import com.flixtown.tv.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the startup flow.
 *
 * Cold-start speed is priority one: cached config is read synchronously (a
 * SharedPreferences hit, not a network call) and, when a device session is
 * already stored, the app routes straight to Home instead of waiting on an
 * Xtream `player_api.php` round trip first. That Xtream check still runs,
 * but in the background, after Home is already showing — a definitive
 * rejection (revoked/banned credentials) or an expired account switches the
 * route away; a network hiccup or timeout does not, so a flaky connection
 * never locks out an already-paired customer.
 *
 * The only startup work that legitimately blocks the UI is a first-ever
 * launch with no cached config at all (nothing to route on yet).
 */
class StartupViewModel(
    private val configRepository: ConfigRepository,
    private val xtreamRepository: XtreamRepository,
    private val authRepository: AuthRepository,
    private val secureStore: SecureCredentialStore,
    private val accountStatusStore: AccountStatusStore
) : ViewModel() {

    private val _route = MutableStateFlow<Route>(Route.Loading)
    val route: StateFlow<Route> = _route

    // Process-lifetime, not UI state: the intro must play at most once per
    // app launch, including when start() is re-invoked after a fresh login.
    private var introConsumedThisLaunch = false

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

            val introUrl = config.introVideoUrl
            if (!introConsumedThisLaunch && config.introEnabled && !introUrl.isNullOrBlank()) {
                introConsumedThisLaunch = true
                _route.value = Route.Intro(introUrl)
                return@launch
            }

            routePastIntro(config)
        }
    }

    /** Called by the intro screen when playback finishes, errors, or times out. */
    fun onIntroFinished() {
        viewModelScope.launch {
            // Re-read from cache (instant) rather than re-triggering a network fetch;
            // the config we just used to decide to show the intro is still current.
            val config = configRepository.getCached()
            if (config == null) {
                _route.value = Route.ConfigUnavailable
                return@launch
            }
            routePastIntro(config)
        }
    }

    private suspend fun routePastIntro(config: RemoteConfig) {
        if (!authRepository.hasStoredSession()) {
            _route.value = Route.Login
            return
        }

        val username = secureStore.getXtreamUsername()
        val password = secureStore.getXtreamPassword()
        if (username == null || password == null) {
            _route.value = Route.Login
            return
        }

        // A device session is already stored: proceed into the app immediately
        // rather than blocking on a network round trip that may never even be
        // needed (an already-paired, still-active customer is the common case).
        _route.value = Route.Home

        viewModelScope.launch {
            when (val result = xtreamRepository.authenticate(config.xtreamBaseUrl, username, password)) {
                is XtreamAuthResult.Success -> {
                    accountStatusStore.save(result.expiresAtEpochSeconds)
                    if (!result.isActive) {
                        _route.value = Route.RenewalRequired(result.status)
                    }
                    // Active: already on Home, nothing to do.
                }
                is XtreamAuthResult.InvalidCredentials -> {
                    // Definitive rejection (revoked/banned) — not a timeout. Stored data is
                    // left intact per policy, but the customer must re-authenticate.
                    _route.value = Route.Login
                }
                is XtreamAuthResult.NetworkError, is XtreamAuthResult.ServerError -> {
                    // Transient/offline: never send an already-paired customer back to
                    // login just because a background check timed out.
                }
            }
        }
    }

    class Factory(
        private val configRepository: ConfigRepository,
        private val xtreamRepository: XtreamRepository,
        private val authRepository: AuthRepository,
        private val secureStore: SecureCredentialStore,
        private val accountStatusStore: AccountStatusStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StartupViewModel(configRepository, xtreamRepository, authRepository, secureStore, accountStatusStore) as T
        }
    }
}
