package com.rain.sdk.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.RainSdk
import com.rain.sdk.RainChain
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.launch

enum class RpcOption {
    MAINNET, TESTNET
}

class SampleViewModel(
    private val rainClient: RainClient
) : ViewModel() {
    
    var sessionToken by mutableStateOf("")
        private set

    var accessToken by mutableStateOf("")
        private set

    var isInitialized by mutableStateOf(rainClient.isInitialized)
        private set

    var statusText by mutableStateOf("Ready")
        private set
    
    var selectedRpcOption by mutableStateOf(RpcOption.MAINNET)
        private set

    fun onTokenChanged(newToken: String) {
        sessionToken = newToken
    }

    fun onAccessTokenChanged(newToken: String) {
        accessToken = newToken
    }
    
    fun onRpcOptionChanged(option: RpcOption) {
        selectedRpcOption = option
    }

    fun initializeSdk() {
        if (sessionToken.isBlank()) return

        try {
            val (chainId, rpcUrl) = when (selectedRpcOption) {
                RpcOption.MAINNET -> RainChain.AVALANCHE_MAINNET to "https://api.avax.network/ext/bc/C/rpc"
                RpcOption.TESTNET -> RainChain.AVALANCHE_TESTNET to "https://api.avax-test.network/ext/bc/C/rpc"
            }
            
            val rpcConfig = mapOf(chainId to rpcUrl)

          rainClient.initializePortal(
                portalSessionToken = sessionToken,
                rpcEndpoints = rpcConfig,
                chainId = chainId
            )
            
            isInitialized = rainClient.isInitialized
            statusText = "SDK Initialized Successfully ($selectedRpcOption)!"
        } catch (e: Exception) {
            statusText = "Error: ${e.message}"
            isInitialized = false
        }
    }

    fun getWalletAddress() {
        if (!isInitialized) return
        
        viewModelScope.launch {
            try {
                val address = rainClient.portal.getAddress(PortalNamespace.EIP155) ?: "Address not found"
                statusText = "Address fetched: $address"
            } catch (e: Exception) {
                statusText = "Failed to get address: ${e.message}"
            }
        }
    }

    fun testWithdraw(context: android.content.Context) {
        if (!isInitialized) return
        if (accessToken.isBlank()) {
            statusText = "Error: Access Token is required"
            return
        }
        
        viewModelScope.launch {
            try {
                statusText = "Fetching Admin Signature..."
                
                val chainId = when (selectedRpcOption) {
                    RpcOption.MAINNET -> 43114 // Avalanche Mainnet
                    RpcOption.TESTNET -> 43113 // Avalanche Testnet
                }
                val tokenAddress = "0xD856a0585Da55e83d03ccb49Ef09D180494CfBAD" // USDC on Avalanche Testnet?
                val amount = 0.1
                val decimals = 6
                // IMPORTANT: Adjust logic to convert amount to base units based on decimals
                val amountLong = (amount * Math.pow(10.0, decimals.toDouble())).toLong()
                
                // TODO: Replace with real inputs
                val recipientAddress = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff"

                val response = NetworkClient.fetchAdminSignature(
                    accessToken = accessToken,
                    chainId = chainId.toLong(),
                    token = tokenAddress.lowercase(), // Ensure lowercase as per main app
                    amount = amountLong,
                    recipientAddress = recipientAddress
                )

                if (response.result.isFailure) {
                    statusText = "Fetch failed: ${response.result.exceptionOrNull()?.message}"
                    return@launch
                }

                val (signature, expiresAt) = response.result.getOrThrow()

                statusText = "Signature fetched! Processing withdrawal..."
                
                val txHash = rainClient.withdrawCollateral(
                    chainId = chainId,
                    collateralProxyAddress = "0xA23c083FE7ab3ba7D07Ded50081e4E8d7249603b", // TODO: Check if this needs to come from API too
                    tokenAddress = tokenAddress,
                    amount = amount,
                    decimals = decimals,
                    recipientAddress = recipientAddress,
                    expiresAt = expiresAt, // Use expiry from API
                    adminSalt = signature.salt,
                    adminSignature = signature.data,
                    nonce = null // Let SDK resolve
                )
                
                statusText = "Withdrawal successful!\nTx: ${txHash.take(16)}..."
            } catch (e: Exception) {
                statusText = "Withdrawal failed: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun clearSession() {
        sessionToken = ""
        statusText = "Session Cleared"
        isInitialized = false
    }
}
