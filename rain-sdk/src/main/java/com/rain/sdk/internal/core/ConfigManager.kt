package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.config.RainConfig
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlin.collections.iterator

/**
 * Manages SDK configuration and validation.
 * 
 * Handles RPC endpoint validation, chain configuration, and initialization state.
 */
internal class ConfigManager {
    
    private val config = RainConfig.getInstance()
    
    /**
     * Checks if the SDK has been initialized.
     */
    val isInitialized: Boolean
        get() = config.isInitialized
    
    /**
     * Validates and sets up RPC endpoints for all chains.
     * 
     * @param rpcEndpoints Map of chain IDs to RPC URLs
     * @return Map of Portal chain identifiers to RPC URLs (e.g., "eip155:43114" -> "https://...")
     * @throws RainError.InvalidConfig if validation fails
     */
    fun validateAndSetupRpcEndpoints(
        rpcEndpoints: Map<Int, String>
    ): Map<String, String> {
        if (rpcEndpoints.isEmpty()) {
            throw RainError.InvalidConfig("At least one RPC endpoint is required")
        }
        
        val eip155RpcEndpointsConfig = mutableMapOf<String, String>()
        
        for ((id, url) in rpcEndpoints) {
            // Validate chain ID
            if (id <= 0) {
                throw RainError.InvalidConfig("Invalid Chain ID: $id. Must be a positive integer.")
            }
            
            // Validate URL
            if (!URLUtil.isValidUrl(url)) {
                throw RainError.InvalidConfig("Invalid RPC URL for chainId $id: $url")
            }
            
            // Store in both formats
            eip155RpcEndpointsConfig["${PortalNamespace.EIP155.value}:$id"] = url
            config.setRpcUrl(id, url)
        }
        
        return eip155RpcEndpointsConfig
    }
    
    /**
     * Determines the legacy chain ID to use.
     * 
     * Priority:
     * 1. Use provided chainId if specified
     * 2. Use Avalanche Mainnet if available in endpoints
     * 3. Use the first available chain from endpoints
     * 
     * @param providedChainId Optional chain ID provided by user
     * @param rpcEndpoints Available RPC endpoints
     * @return The legacy chain ID to use
     */
    fun determineLegacyChainId(
        providedChainId: Int?,
        rpcEndpoints: Map<Int, String>
    ): Int {
        return providedChainId ?: if (rpcEndpoints.containsKey(RainChain.AVALANCHE_MAINNET)) {
            RainChain.AVALANCHE_MAINNET
        } else {
            rpcEndpoints.keys.first()
        }
    }
    
    /**
     * Gets the RPC URL for a specific chain.
     * 
     * @param chainId The chain ID
     * @return The RPC URL or null if not configured
     */
    fun getRpcUrl(chainId: Int): String? {
        return config.getRpcUrl(chainId)
    }
    
    /**
     * Marks the SDK as initialized.
     */
    fun markInitialized() {
        config.markInitialized()
    }
}
