package com.rain.sdk.internal.network.rainapi

import com.rain.sdk.internal.error.RainError

/** The credential pair a client session token is minted against. */
internal data class RainApiCredentials(val apiKey: String, val userId: String)

/**
 * Holds the Rain API base URL (fixed at build time) and the host-supplied credentials
 * (mutable at runtime via `RainSdk.configureRainApi`). The SDK never persists these.
 */
internal class RainApiConfigStore(val baseUrl: String) {

    // One volatile pair, so a concurrent setCredentials can never produce a torn read
    // (new apiKey with the old userId).
    @Volatile
    private var pair: RainApiCredentials? = null

    /** Sets or replaces the credential pair. Values are trimmed; blank clears. */
    fun setCredentials(apiKey: String, userId: String) {
        val key = apiKey.trim()
        val user = userId.trim()
        pair = if (key.isBlank() || user.isBlank()) null else RainApiCredentials(key, user)
    }

    fun clear() {
        pair = null
    }

    val isConfigured: Boolean
        get() = pair != null

    /** @throws RainError.ApiNotConfigured when either value is blank. */
    fun credentials(): RainApiCredentials =
        pair ?: throw RainError.ApiNotConfigured()
}
