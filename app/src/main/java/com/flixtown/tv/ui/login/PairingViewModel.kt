package com.flixtown.tv.ui.login

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flixtown.tv.core.BackendConstants
import com.flixtown.tv.data.PairingCompletionResult
import com.flixtown.tv.data.PairingRepository
import com.flixtown.tv.data.model.PairingPollResult
import com.flixtown.tv.data.model.PairingSession
import com.flixtown.tv.ui.components.QrCodeGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data object Starting : PairingUiState
    data class Ready(val session: PairingSession, val qrCode: ImageBitmap) : PairingUiState
    data object Confirming : PairingUiState
    data object Success : PairingUiState
    data class Error(val message: String) : PairingUiState
}

private const val POLL_INTERVAL_MS = 3000L

class PairingViewModel(
    private val pairingRepository: PairingRepository,
    private val installationId: String,
    private val deviceModel: String
) : ViewModel() {

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Starting)
    val state: StateFlow<PairingUiState> = _state

    private var pollJob: Job? = null

    fun start() {
        pollJob?.cancel()
        viewModelScope.launch {
            _state.value = PairingUiState.Starting
            pairingRepository.start(installationId, deviceModel).fold(
                onSuccess = { session ->
                    val qrContent = "${BackendConstants.ACTIVATION_URL}?code=${session.publicCode}"
                    val qrCode = QrCodeGenerator.generate(qrContent)
                    _state.value = PairingUiState.Ready(session, qrCode)
                    beginPolling(session)
                },
                onFailure = { error ->
                    _state.value = PairingUiState.Error(error.message ?: "Could not start pairing. Check your connection.")
                }
            )
        }
    }

    private fun beginPolling(session: PairingSession) {
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (System.currentTimeMillis() > session.expiresAtMillis) {
                    _state.value = PairingUiState.Error("This code expired. Generate a new one to continue.")
                    return@launch
                }

                when (val poll = pairingRepository.pollOnce(session)) {
                    is PairingPollResult.Pending -> Unit // keep waiting
                    is PairingPollResult.Completed -> {
                        _state.value = PairingUiState.Confirming
                        when (val completion = pairingRepository.completePairing(session, poll)) {
                            PairingCompletionResult.Success -> _state.value = PairingUiState.Success
                            is PairingCompletionResult.Failure -> _state.value = PairingUiState.Error(completion.message)
                        }
                        return@launch
                    }
                    is PairingPollResult.Expired -> {
                        _state.value = PairingUiState.Error("This code expired. Generate a new one to continue.")
                        return@launch
                    }
                    is PairingPollResult.Error -> Unit // transient network hiccup: keep polling
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
    }

    class Factory(
        private val pairingRepository: PairingRepository,
        private val installationId: String,
        private val deviceModel: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PairingViewModel(pairingRepository, installationId, deviceModel) as T
        }
    }
}
