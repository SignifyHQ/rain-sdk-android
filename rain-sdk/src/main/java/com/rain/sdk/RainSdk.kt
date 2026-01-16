package com.rain.sdk

import android.webkit.URLUtil
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import timber.log.Timber

object RainSdk {
    // Internal storage for Portal instance
    private var _portal: Portal? = null

    // Web3 Utilities (Standalone)
    val Utils = RainUtils

    /**
     * Computed property to safely access the Portal instance.
     * Throws RainError.PortalNotInitialized if not initialized.
     */
    val portal: Portal
        @Throws(RainError::class)
        get() {
            return _portal ?: throw RainError.SdkNotInitialized()
        }

    /**
     * Initializes the SDK with a Portal token and chain-specific RPC endpoints.
     *
     * @param portalSessionToken A valid Portal session token
     * @param rpcEndpoints Map mapping numeric chain IDs to RPC URLs
     * Example: mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com")
     * @throws RainError if initialization fails
     */
    @Throws(RainError::class)
    fun initializePortal(
        portalSessionToken: String,
        rpcEndpoints: Map<Int, String>
    ) {
        try {
            // Validate token
            if (portalSessionToken.isBlank()) {
                throw RainError.InvalidConfig("Portal session token cannot be blank")
            }

            // Validate and Map RPC endpoints
            val eip155RpcEndpointsConfig = mutableMapOf<String, String>()
            
            // We need a legacy chain ID for Portal constructor. 
            // Prefer 43114 (Avalanche Mainnet) or take the first one available.
            var legacyChainId = 43114 
            if (rpcEndpoints.isNotEmpty()) {
                legacyChainId = rpcEndpoints.keys.first()
                if (rpcEndpoints.containsKey(43114)) {
                    legacyChainId = 43114
                }
            }

            for ((chainId, url) in rpcEndpoints) {
                if (!URLUtil.isValidUrl(url)) {
                    throw RainError.InvalidConfig("Invalid RPC URL for chainId $chainId: $url")
                }
                eip155RpcEndpointsConfig["eip155:$chainId"] = url
            }

            // Initialize Portal instance
            _portal = Portal(
                apiKey = portalSessionToken,
                legacyEthChainId = legacyChainId,
                rpcConfig = eip155RpcEndpointsConfig,
                featureFlags = FeatureFlags(isMultiBackupEnabled = true),
                autoApprove = true
            )

            Timber.d("Rain SDK: Registered Portal instance successfully")
        } catch (e: RainError) {
            Timber.e(e, "Rain SDK: Initialization error")
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Rain SDK: Portal SDK error")
            throw RainError.ProviderError(e)
        }
    }
}
