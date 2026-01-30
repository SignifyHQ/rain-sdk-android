package com.rain.sdk.internal.core

import com.rain.sdk.internal.error.RainError
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.PortalRequestMethod
import io.portalhq.android.storage.mobile.PortalNamespace
import timber.log.Timber

/**
 * Wrapper around Portal SDK to encapsulate all Portal interactions.
 * 
 * Provides a clean API for signing and sending transactions through Portal,
 * and manages the Portal instance lifecycle.
 */
internal class PortalManager {
    
    private var _portal: Portal? = null
    
    /**
     * Checks if Portal has been initialized.
     */
    val isInitialized: Boolean
        get() = _portal != null
    
    /**
     * Initializes the Portal instance with provided configuration.
     * 
     * @param apiKey Portal API key (session token)
     * @param legacyEthChainId The default chain ID for legacy operations
     * @param rpcConfig Map of chain identifiers to RPC URLs (e.g., "eip155:43114" -> "https://...")
     * @param featureFlags Portal feature flags
     * @param autoApprove Whether to auto-approve transactions
     */
    fun initialize(
        apiKey: String,
        legacyEthChainId: Int,
        rpcConfig: Map<String, String>,
        featureFlags: FeatureFlags,
        autoApprove: Boolean
    ) {
        _portal = createPortal(
            apiKey = apiKey,
            legacyEthChainId = legacyEthChainId,
            rpcConfig = rpcConfig,
            featureFlags = featureFlags,
            autoApprove = autoApprove
        )
        
        Timber.d("Rain SDK: Portal initialized successfully")
    }
    
    /**
     * Gets the wallet address for the specified namespace.
     * 
     * @return The wallet address
     * @throws RainError.ProviderError if Portal is not initialized or fails to get address
     */
    suspend fun getAddress(): String {
        val portal = getPortalOrThrow()
        
        return try {
            portal.getAddress(PortalNamespace.EIP155)
                ?: throw RainError.ProviderError(IllegalStateException("Portal returned null address"))
        } catch (e: Exception) {
            throw RainError.ProviderError(e)
        }
    }
    
    /**
     * Signs typed data (EIP-712) using Portal.
     * 
     * @param chainId The chain ID
     * @param walletAddress The wallet address to sign with
     * @param typedDataJson The EIP-712 typed data as JSON string
     * @return The signature as a hex string
     * @throws RainError.ProviderError if signing fails
     */
    suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        val portal = getPortalOrThrow()
        
        return try {
            val response = portal.request(
                chainId = "${PortalNamespace.EIP155.value}:$chainId",
                method = PortalRequestMethod.eth_signTypedData_v4,
                params = listOf(walletAddress, typedDataJson)
            )
            response.result.toString()
        } catch (e: Exception) {
            Timber.e(e, "Rain SDK: Failed to sign typed data")
            throw e
        }
    }
    
    /**
     * Sends a transaction using Portal.
     * 
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The recipient address
     * @param data The transaction data (encoded function call)
     * @param value The value to send (default "0x0")
     * @return The transaction hash
     * @throws Exception if transaction fails
     */
    suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String = "0x0"
    ): String {
        val portal = getPortalOrThrow()
        
        val response = portal.request(
            chainId = "${PortalNamespace.EIP155.value}:$chainId",
            method = PortalRequestMethod.eth_sendTransaction,
            params = listOf(
                mapOf(
                    "from" to from,
                    "to" to to,
                    "data" to data,
                    "value" to value
                )
            )
        )
        
        return response.result.toString()
    }
    
    /**
     * Gets the Portal instance.
     * 
     * @return The Portal instance
     * @throws RainError.SdkNotInitialized if Portal is not initialized
     */
    fun getPortalInstance(): Portal {
        return _portal ?: throw RainError.SdkNotInitialized()
    }
    
    /**
     * Helper to get Portal instance or throw error.
     */
    private fun getPortalOrThrow(): Portal {
        return _portal ?: throw RainError.SdkNotInitialized()
    }
    
    /**
     * Factory method to create Portal instance.
     * Separated for testability (can be mocked).
     */
    internal fun createPortal(
        apiKey: String,
        legacyEthChainId: Int,
        rpcConfig: Map<String, String>,
        featureFlags: FeatureFlags,
        autoApprove: Boolean
    ): Portal {
        return Portal(
            apiKey = apiKey,
            legacyEthChainId = legacyEthChainId,
            rpcConfig = rpcConfig,
            featureFlags = featureFlags,
            autoApprove = autoApprove
        )
    }
}
