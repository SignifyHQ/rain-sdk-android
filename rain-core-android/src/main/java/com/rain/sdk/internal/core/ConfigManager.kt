package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.rain.sdk.internal.error.RainError
import kotlin.collections.iterator

/**
 * Validates the chain configuration a [com.rain.sdk.RainSdk] is built with.
 *
 * Validation only — the endpoints are held by the `RainSdk` instance built with them, never in
 * shared state.
 */
internal class ConfigManager {

    /**
     * Rejects a configuration that cannot produce working RPC calls.
     *
     * @param rpcEndpoints Map of chain IDs to RPC URLs
     * @throws RainError.InvalidConfig if the map is empty, or any chain ID or URL is unusable
     */
    fun validateRpcEndpoints(rpcEndpoints: Map<Int, String>) {
        if (rpcEndpoints.isEmpty()) {
            throw RainError.InvalidConfig("At least one RPC endpoint is required")
        }

        for ((id, url) in rpcEndpoints) {
            if (id <= 0) {
                throw RainError.InvalidConfig("Invalid Chain ID: $id. Must be a positive integer.")
            }
            if (!URLUtil.isValidUrl(url)) {
                throw RainError.InvalidConfig("Invalid RPC URL for chainId $id: $url")
            }
        }
    }
}
