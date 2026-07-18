package com.rain.sdk.internal.network.rainapi

import com.rain.sdk.internal.error.RainError

/** The credential pair a client session token is minted against. */
internal data class RainApiCredentials(val apiKey: String, val userId: String)

/**
 * Holds the Rain API base URL (fixed at build time) and the host-supplied credentials
 * (mutable at runtime via `RainSdk.configureRainApi`). The SDK never persists these.
 */
internal class RainApiConfigStore(val baseUrl: String) {

    @Volatile
    private var apiKey: String = ""

    @Volatile
    private var userId: String = ""

    /** Sets or replaces the credential pair. Values are trimmed; blank clears. */
    fun setCredentials(apiKey: String, userId: String) {
        this.apiKey = apiKey.trim()
        this.userId = userId.trim()
    }

    fun clear() {
        apiKey = ""
        userId = ""
    }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && userId.isNotBlank()

    /** @throws RainError.ApiNotConfigured when either value is blank. */
    fun credentials(): RainApiCredentials {
        val key = apiKey
        val user = userId
        if (key.isBlank() || user.isBlank()) throw RainError.ApiNotConfigured()
        return RainApiCredentials(apiKey = key, userId = user)
    }
}
