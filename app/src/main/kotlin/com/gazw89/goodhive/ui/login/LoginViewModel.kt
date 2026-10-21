package com.gazw89.goodhive.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.auth.AuthRepository
import com.gazw89.goodhive.auth.LoginOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoginStep {
    data object Credentials : LoginStep
    data class SmsCode(val session: String, val username: String) : LoginStep
    data object LoggedIn : LoginStep
}

data class LoginUiState(
    val step: LoginStep = LoginStep.Credentials,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val savedUsername: String = "",
    val savedPassword: String = "",
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(
            step = if (authRepository.isLoggedIn()) LoginStep.LoggedIn else LoginStep.Credentials,
            savedUsername = authRepository.savedUsername().orEmpty(),
            savedPassword = authRepository.savedPassword().orEmpty(),
        ),
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun submitCredentials(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter your email and password")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching { authRepository.login(username, password) }
                .onSuccess { outcome ->
                    _uiState.value = when (outcome) {
                        is LoginOutcome.Success -> LoginUiState(
                            step = LoginStep.LoggedIn,
                            savedUsername = username,
                            savedPassword = password,
                        )
                        is LoginOutcome.SmsCodeRequired ->
                            LoginUiState(
                                step = LoginStep.SmsCode(outcome.session, outcome.username),
                                savedUsername = username,
                                savedPassword = password,
                            )
                    }
                }
                .onFailure { error ->
                    _uiState.value = LoginUiState(
                        step = LoginStep.Credentials,
                        errorMessage = error.message ?: "Login failed — check your email and password",
                        savedUsername = username,
                        savedPassword = password,
                    )
                }
        }
    }

    fun submitSmsCode(code: String) {
        val step = _uiState.value.step as? LoginStep.SmsCode ?: return
        if (code.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter the code Hive texted you")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            runCatching { authRepository.submitSmsCode(code, step.session, step.username) }
                .onSuccess {
                    _uiState.value = LoginUiState(
                        step = LoginStep.LoggedIn,
                        savedUsername = _uiState.value.savedUsername,
                        savedPassword = _uiState.value.savedPassword,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "That code wasn't accepted — try again",
                    )
                }
        }
    }
}
