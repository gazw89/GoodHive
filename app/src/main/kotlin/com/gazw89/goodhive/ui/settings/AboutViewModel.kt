package com.gazw89.goodhive.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.BuildConfig
import com.gazw89.goodhive.auth.AuthRepository
import com.gazw89.goodhive.auth.SessionDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class AboutUiState(
    val versionName: String,
    val versionCode: Int,
    val session: SessionDiagnostics,
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** Refreshes every second so remaining / age fields count down live. */
    val uiState: StateFlow<AboutUiState> = flow {
        while (true) {
            emit(buildState())
            delay(1_000)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildState(),
    )

    private fun buildState(): AboutUiState = AboutUiState(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        session = authRepository.sessionDiagnostics(),
    )
}
