package com.rain.sdk.provider

/**
 * Stable identifier for a wallet provider.
 *
 * Modeled as a value class wrapping a string (rather than a closed enum) so host apps can ship
 * their own provider under a custom id without modifying core. The well-known Rain providers are
 * exposed as companion constants — [PORTAL], [TURNKEY], [PRIVY].
 */
@JvmInline
value class ProviderId(val value: String) {
    companion object {
        val PORTAL = ProviderId("portal")
        val TURNKEY = ProviderId("turnkey")
        val PRIVY = ProviderId("privy")
    }
}
