package com.rain.sdk.internal.constants

internal object RainConstants {
    const val FUNC_ADMIN_NONCE = "adminNonce"
    const val FUNC_WITHDRAW_ASSET = "withdrawAsset"

    // Network Config
    const val NETWORK_TIMEOUT_SECONDS = 30L

    /**
     * CAIP-2 namespace for EVM chains. Used to build `eip155:<chainId>` identifiers without
     * pulling a provider SDK's namespace enum into core.
     */
    const val EIP155_NAMESPACE = "eip155"

    /**
     * Chains for which the Turnkey `get-balances` API returns data.
     * On any other chain, balance reads fall through to `ChainReader`.
     * Source: https://docs.turnkey.com/api-reference/queries/get-balances
     */
    val TURNKEY_SUPPORTED_CHAINS: Set<Int> = setOf(
        1,        // Ethereum Mainnet
        11155111, // Sepolia
        8453,     // Base Mainnet
        84532,    // Base Sepolia
        137,      // Polygon Mainnet
        80002     // Polygon Amoy
    )
}
