package com.flixtown.tv.data

import com.flixtown.tv.data.model.AuthOutcome
import com.flixtown.tv.data.model.XtreamAuthResult
import com.flixtown.tv.security.SecureCredentialStore

sealed interface LoginResult {
    data class Success(val accountActive: Boolean) : LoginResult
    data class InvalidCredentials(val message: String) : LoginResult
    data class NetworkError(val message: String) : LoginResult
    data object ConfigUnavailable : LoginResult
}

/**
 * Orchestrates manual login end-to-end. Authentication is only ever
 * considered complete, and only then does credential storage happen, after
 * ALL of: (1) Xtream accepts the credentials, and (2) the Flix Town backend
 * registers this installation and returns a device token. A failure at
 * either step leaves the app on the login screen — never a partial success
 * routed to Home.
 */
class AuthRepository(
    private val configRepository: ConfigRepository,
    private val xtreamRepository: XtreamRepository,
    private val backendApi: BackendApi,
    private val secureStore: SecureCredentialStore
) {

    suspend fun manualLogin(installationId: String, deviceModel: String, username: String, password: String): LoginResult {
        val config = configRepository.getCached() ?: return LoginResult.ConfigUnavailable

        when (val xtreamResult = xtreamRepository.authenticate(config.xtreamBaseUrl, username, password)) {
            is XtreamAuthResult.InvalidCredentials ->
                return LoginResult.InvalidCredentials(xtreamResult.message ?: "Incorrect username or password")
            is XtreamAuthResult.NetworkError ->
                return LoginResult.NetworkError("Could not reach the Xtream server")
            is XtreamAuthResult.ServerError ->
                return LoginResult.NetworkError(xtreamResult.message)
            is XtreamAuthResult.Success -> Unit // fall through to backend registration
        }

        return when (val outcome = backendApi.registerDevice(installationId, deviceModel, username, password)) {
            is AuthOutcome.Success -> {
                secureStore.saveXtreamCredentials(username, password)
                secureStore.saveDeviceToken(outcome.deviceToken)
                LoginResult.Success(accountActive = outcome.accountActive)
            }
            is AuthOutcome.InvalidCredentials -> LoginResult.InvalidCredentials(outcome.message)
            is AuthOutcome.NetworkError -> LoginResult.NetworkError(outcome.message)
        }
    }

    fun hasStoredSession(): Boolean =
        secureStore.hasXtreamCredentials() && secureStore.getDeviceToken() != null

    fun signOut() = secureStore.clearAccount()
}
