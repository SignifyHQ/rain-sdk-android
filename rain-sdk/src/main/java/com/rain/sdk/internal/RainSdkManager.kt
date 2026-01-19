package com.rain.sdk.internal

import android.webkit.URLUtil
import com.rain.sdk.RainChain
import com.rain.sdk.RainError
import com.rain.sdk.RainSdk
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import timber.log.Timber

internal class RainSdkManager : RainSdk {

    private var _portal: Portal? = null

    companion object {
        private const val EIP155_PREFIX = "eip155"
    }

    override val isInitialized: Boolean
        get() = _portal != null

    override val portal: Portal
        get() = _portal ?: throw RainError.SdkNotInitialized()

    override fun initializePortal(
        portalSessionToken: String,
        rpcEndpoints: Map<Int, String>,
        chainId: Int?
    ) {
        try {
            // Validate token
            if (portalSessionToken.isBlank()) {
                throw RainError.InvalidConfig("Portal session token cannot be blank")
            }

            // Validate and Map RPC endpoints
            if (rpcEndpoints.isEmpty()) {
                throw RainError.InvalidConfig("At least one RPC endpoint is required")
            }

            val eip155RpcEndpointsConfig = mutableMapOf<String, String>()
            
            for ((id, url) in rpcEndpoints) {
                if (id <= 0) {
                    throw RainError.InvalidConfig("Invalid Chain ID: $id. Must be a positive integer.")
                }
                if (!URLUtil.isValidUrl(url)) {
                    throw RainError.InvalidConfig("Invalid RPC URL for chainId $id: $url")
                }
                eip155RpcEndpointsConfig["$EIP155_PREFIX:$id"] = url
            }

            // Determine Legacy Chain ID (Use provided one, or infer from endpoints)
            val legacyChainId = chainId ?: if (rpcEndpoints.containsKey(RainChain.AVALANCHE_MAINNET)) {
                RainChain.AVALANCHE_MAINNET
            } else {
                rpcEndpoints.keys.first()
            }

            // Initialize Portal instance
            _portal = createPortal(
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
