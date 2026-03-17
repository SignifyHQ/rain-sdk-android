package com.rain.sdk.sample.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.NetworkClient
import io.portalhq.android.mpc.data.BackupConfigs
import io.portalhq.android.mpc.data.BackupMethods
import io.portalhq.android.mpc.data.PasswordStorageConfig
import kotlinx.coroutines.launch

class HomeViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    var sessionToken by mutableStateOf("")
        private set

    var accessToken by mutableStateOf("")
        private set

    var pin by mutableStateOf("")
        private set

    var isInitialized by mutableStateOf(rainClient.isInitialized)
        private set

    var needsRecovery by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("Ready")
        private set

    fun onSessionTokenChanged(value: String) {
        sessionToken = value
    }

    fun onAccessTokenChanged(value: String) {
        accessToken = value
    }

    fun onPinChanged(value: String) {
        pin = value
    }

    fun initializeSdk() {
        if (sessionToken.isBlank()) return

        try {
            val rpcConfig = mapOf(
                RainChain.AVALANCHE_TESTNET to "https://api.avax-test.network/ext/bc/C/rpc"
            )

            rainClient.initializePortal(
                portalSessionToken = sessionToken,
                rpcEndpoints = rpcConfig,
                chainId = RainChain.AVALANCHE_TESTNET
            )

            isInitialized = rainClient.isInitialized
            statusText = "SDK Initialized Successfully!"
            needsRecovery = true
        } catch (e: Exception) {
            statusText = "Error: ${e.message}"
            isInitialized = false
        }
    }

    fun recoverWithPin() {
        if (sessionToken.isBlank()) {
            statusText = "Session token required for recovery"
            return
        }
        if (pin.isBlank()) {
            statusText = "PIN required for recovery"
            return
        }

        statusText = "Fetching backup share..."
        viewModelScope.launch {
            try {
                val backupResponse = NetworkClient.fetchBackupShare(accessToken)
                if (backupResponse.result.isFailure) {
                    statusText = "Failed to fetch backup share: ${backupResponse.result.exceptionOrNull()?.message}"
                    return@launch
                }

                val cipherText = backupResponse.result.getOrThrow()
                statusText = "Recovering wallet..."

                val portal = rainClient.portal
                val backupConfigs = BackupConfigs(
                    PasswordStorageConfig(password = pin)
                )

                portal.recoverWallet(
                    cipherText,
                    BackupMethods.Password,
                    backupConfigs
                ) { status ->
                    statusText = "Recovery status: $status"
                }

                statusText = "Recovery triggered! Check wallet address in a moment."
            } catch (e: Exception) {
                statusText = "Recovery failed: ${e.message}"
            }
        }
    }

    fun clearSession() {
        sessionToken = ""
        accessToken = ""
        pin = ""
        statusText = "Session Cleared"
        isInitialized = false
        needsRecovery = false
    }
}

class HomeViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(RainSdk.getInstance().client) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
