package com.rain.sdk.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rain.sdk.Rain
import com.rain.sdk.RainChain
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.launch

class SampleViewModel : ViewModel() {
    
    var sessionToken by mutableStateOf("")
        private set

    var isInitialized by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("Ready")
        private set

    fun onTokenChanged(newToken: String) {
        sessionToken = newToken
    }

    fun initializeSdk() {
        if (sessionToken.isBlank()) return

        try {
            val rpcConfig = mapOf(RainChain.AVALANCHE_MAINNET to "https://api.avax.network/ext/bc/C/rpc")
            
            Rain.instance.initializePortal(
                portalSessionToken = sessionToken,
                rpcEndpoints = rpcConfig,
                chainId = RainChain.AVALANCHE_MAINNET
            )
            
            isInitialized = Rain.instance.isInitialized
            statusText = "SDK Initialized Successfully!"
        } catch (e: Exception) {
            statusText = "Error: ${e.message}"
            isInitialized = false
        }
    }

    fun getWalletAddress() {
        if (!isInitialized) return
        
        viewModelScope.launch {
            try {
                val address = Rain.instance.portal.getAddress(PortalNamespace.EIP155) ?: "Address not found"
                statusText = "Address fetched: $address"
            } catch (e: Exception) {
                statusText = "Failed to get address: ${e.message}"
            }
        }
    }

    fun clearSession() {
        sessionToken = ""
        statusText = "Session Cleared"
        isInitialized = false
    }
}
