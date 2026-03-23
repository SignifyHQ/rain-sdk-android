package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.NetworkClient
import io.portalhq.android.mpc.data.BackupConfigs
import io.portalhq.android.mpc.data.BackupMethods
import io.portalhq.android.mpc.data.PasswordStorageConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(isInitialized = rainClient.isInitialized)
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun onSessionTokenChanged(value: String) {
        _state.update { it.copy(sessionToken = value) }
    }

    fun onAccessTokenChanged(value: String) {
        _state.update { it.copy(accessToken = value) }
    }

    fun onPinChanged(value: String) {
        _state.update { it.copy(pin = value) }
    }

    fun initializeSdk() {
        if (_state.value.sessionToken.isBlank()) return

        try {
            val rpcConfig = mapOf(
                RainChain.AVALANCHE_TESTNET to "https://api.avax-test.network/ext/bc/C/rpc"
            )

            rainClient.initializePortal(
                portalSessionToken = _state.value.sessionToken,
                rpcEndpoints = rpcConfig,
                chainId = RainChain.AVALANCHE_TESTNET
            )

            _state.update {
                it.copy(
                    isInitialized = rainClient.isInitialized,
                    statusText = "SDK Initialized Successfully!",
                    needsRecovery = true
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    statusText = "Error: ${e.message}",
                    isInitialized = false
                )
            }
        }
    }

    fun recoverWithPin() {
        val currentState = _state.value
        if (currentState.sessionToken.isBlank()) {
            _state.update { it.copy(statusText = "Session token required for recovery") }
            return
        }
        if (currentState.accessToken.isBlank()) {
            _state.update { it.copy(statusText = "Access token required for recovery") }
            return
        }
        if (currentState.pin.isBlank()) {
            _state.update { it.copy(statusText = "PIN required for recovery") }
            return
        }

        _state.update { it.copy(statusText = "Fetching backup share...", isLoading = true) }
        viewModelScope.launch {
            try {
                val backupResponse = NetworkClient.fetchBackupShare(currentState.accessToken)
                if (backupResponse.result.isFailure) {
                    _state.update {
                        it.copy(
                            statusText = "Failed to fetch backup share: ${backupResponse.result.exceptionOrNull()?.message}",
                            isLoading = false
                        )
                    }
                    return@launch
                }

                val cipherText = backupResponse.result.getOrThrow()
                _state.update { it.copy(statusText = "Recovering wallet...") }

                val portal = rainClient.portal
                val backupConfigs = BackupConfigs(
                    PasswordStorageConfig(password = currentState.pin)
                )

                portal.recoverWallet(
                    cipherText,
                    BackupMethods.Password,
                    backupConfigs
                ) { status ->
                    _state.update { it.copy(statusText = "Recovery status: $status") }
                }

                _state.update {
                    it.copy(
                        isRecovered = true,
                        needsRecovery = false,
                        isLoading = false,
                        statusText = "Recovery successful! Wallet is ready."
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        statusText = "Recovery failed: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun clearSession() {
        _state.update {
            HomeUiState(statusText = "Session Cleared")
        }
    }
}

data class HomeUiState(
    val sessionToken: String = "",
    val accessToken: String = "",
    val pin: String = "",
    val isInitialized: Boolean = false,
    val needsRecovery: Boolean = false,
    val isRecovered: Boolean = false,
    val isLoading: Boolean = false,
    val statusText: String = "Ready"
)

class HomeViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
