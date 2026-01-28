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

    var isInitialized by mutableStateOf(rainClient.isInitialized)
        private set

    var statusText by mutableStateOf("Ready")
        private set
    
    var selectedRpcOption by mutableStateOf(RpcOption.MAINNET)
        private set

    fun onTokenChanged(newToken: String) {
        sessionToken = newToken
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

    fun testWithdraw() {
        if (!isInitialized) return
        
        viewModelScope.launch {
            try {
                statusText = "Processing withdrawal..."
                
                // Test parameters - Using Avalanche Testnet for safety
                val txHash = rainClient.withdrawCollateral(
                    chainId = when (selectedRpcOption) {
                        RpcOption.MAINNET -> 43114 // Avalanche Mainnet
                        RpcOption.TESTNET -> 43113 // Avalanche Testnet
                    },
                    collateralProxyAddress = "0xA23c083FE7ab3ba7D07Ded50081e4E8d7249603b", // TODO: Replace with actual test contract
                    tokenAddress = "0xD856a0585Da55e83d03ccb49Ef09D180494CfBAD", // USDC on Avalanche
                    amount = 1.0, // Small test amount
                    decimals = 6, // USDC decimals
                    recipientAddress = "0xA23c083FE7ab3ba7D07Ded50081e4E8d7249603b", // TODO: Replace with test recipient
                    expiresAt = ((System.currentTimeMillis() / 1000) + 3600).toString(), // 1 hour from now
                    adminSalt = "0x0000000000000000000000000000000000000000000000000000000000000000", // TODO: Get from backend
                    adminSignature = "0x00", // TODO: Get from backend
                    nonce = null // Let SDK resolve
                )
                
                statusText = "Withdrawal successful!\nTx: ${txHash.take(16)}..."
            } catch (e: Exception) {
                statusText = "Withdrawal failed: ${e.message}"
            }
        }
    }

    fun clearSession() {
        sessionToken = ""
        statusText = "Session Cleared"
        isInitialized = false
    }
}
