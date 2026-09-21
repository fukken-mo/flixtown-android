package com.flixtown.tv.data

import com.flixtown.tv.data.model.PairingPollResult
import com.flixtown.tv.data.model.PairingSession
import com.flixtown.tv.security.SecureCredentialStore

sealed interface PairingCompletionResult {
    data object Success : PairingCompletionResult
    data class Failure(val message: String) : PairingCompletionResult
}

/**
 * Drives the QR pairing flow described in the product spec: start -> poll ->
 * on completion, store credentials locally -> ACK -> only the ACK promotes
 * the backend's temporary device token to a permanent session. The backend's
 * ack endpoint is idempotent, so retrying [completePairing] for the same
 * pairing session (e.g. after a transient network failure) is safe and will
 * not mint a second device token.
 */
class PairingRepository(
    private val backendApi: BackendApi,
    private val secureStore: SecureCredentialStore
) {

    suspend fun start(installationId: String, deviceModel: String): Result<PairingSession> {
        val result = backendApi.startPairing(installationId, deviceModel)
        result.getOrNull()?.let { session ->
            secureStore.savePendingPairing(session.pairingId, session.pollToken)
        }
        return result
    }

    suspend fun pollOnce(session: PairingSession): PairingPollResult =
        backendApi.pollPairingStatus(session.pairingId, session.pollToken)

    /** Persists credentials, then ACKs. Only clears pending state once the ACK succeeds. */
    suspend fun completePairing(
        session: PairingSession,
        completed: PairingPollResult.Completed
    ): PairingCompletionResult {
        secureStore.saveXtreamCredentials(completed.xtreamUsername, completed.xtreamPassword)

        val ackResult = backendApi.ackPairing(session.pairingId, session.pollToken, completed.tempDeviceToken)
        return ackResult.fold(
            onSuccess = { permanentDeviceToken ->
                secureStore.saveDeviceToken(permanentDeviceToken)
                secureStore.clearPendingPairing()
                PairingCompletionResult.Success
            },
            onFailure = { error ->
                // Credentials are already stored locally; pending state is left in place
                // so the next attempt can retry the ACK without re-pairing from scratch.
                PairingCompletionResult.Failure(error.message ?: "Could not confirm pairing with the server")
            }
        )
    }

    fun hasPendingPairing(): Boolean =
        secureStore.getPendingPairingId() != null && secureStore.getPendingPollToken() != null
}
