package com.rain.sdk.internal.config

/**
 * Internal configuration storage for Rain SDK.
 * Holds global settings like RPC URLs to be reused across components.
 */
internal object RainConfig {
    var isInitialized: Boolean = false
    private val rpcUrls = mutableMapOf<Int, String>()

    fun setRpcUrl(chainId: Int, url: String) {
        rpcUrls[chainId] = url
    }

    fun getRpcUrl(chainId: Int): String? {
        return rpcUrls[chainId]
    }

    fun clear() {
        rpcUrls.clear()
        isInitialized = false
    }
}
