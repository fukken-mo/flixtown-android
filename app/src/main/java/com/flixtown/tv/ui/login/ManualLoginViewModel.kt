package com.flixtown.tv.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flixtown.tv.data.AuthRepository
import com.flixtown.tv.data.LoginResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ManualLoginUiState {
    data object Idle : ManualLoginUiState
    data object Loading : ManualLoginUiState
    data class Error(val message: String) : ManualLoginUiState
}

class ManualLoginViewModel(
    private val authRepository: AuthRepository,
    private val installationId: String,
    private val deviceModel: String
) : ViewModel() {

    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    private val _state = MutableStateFlow<ManualLoginUiState>(ManualLoginUiState.Idle)
    val state: StateFlow<ManualLoginUiState> = _state

    fun onUsernameChange(value: String) {
        username = value
    }

    fun onPasswordChange(value: String) {
        password = value
    }

    fun submit(onSuccess: () -> Unit) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = ManualLoginUiState.Error("Enter both your username and password")
            return
        }
        viewModelScope.launch {
            _state.value = ManualLoginUiState.Loading
            when (val result = authRepository.manualLogin(installationId, deviceModel, username.trim(), password)) {
                is LoginResult.Success -> {
                    _state.value = ManualLoginUiState.Idle
                    onSuccess()
                }
                is LoginResult.InvalidCredentials -> _state.value = ManualLoginUiState.Error(result.message)
                is LoginResult.NetworkError -> _state.value = ManualLoginUiState.Error(result.message)
                LoginResult.ConfigUnavailable ->
                    _state.value = ManualLoginUiState.Error("Configuration unavailable. Check your connection.")
            }
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val installationId: String,
        private val deviceModel: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ManualLoginViewModel(authRepository, installationId, deviceModel) as T
        }
    }
}
