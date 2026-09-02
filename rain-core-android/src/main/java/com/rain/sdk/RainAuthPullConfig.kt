package com.rain.sdk

/**
 * Trusted Auth Pull targets for one Rain environment.
 *
 * Auth Pull approvals are intentionally disabled until a host supplies this configuration. The
 * SDK then requires every approval, allowance read, and fee estimate to use this exact operator
 * and the configured token for the selected chain.
 */
class RainAuthPullConfig private constructor(
    val operatorAddress: String,
    tokenAddresses: Map<Int, String>,
    internal val kind: Kind
) {
    val tokenAddresses: Map<Int, String> = tokenAddresses.toMap()

    companion object {
        /** Canonical sandbox USDC contracts with the operator supplied by Rain. */
        fun sandbox(operatorAddress: String): RainAuthPullConfig = RainAuthPullConfig(
            operatorAddress = operatorAddress,
            tokenAddresses = mapOf(
                RainChain.BASE_SEPOLIA to "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
                RainChain.ARBITRUM_SEPOLIA to "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d"
            ),
            kind = Kind.SANDBOX
        )

        /** Canonical production USDC contracts with the operator supplied by Rain. */
        fun production(operatorAddress: String): RainAuthPullConfig = RainAuthPullConfig(
            operatorAddress = operatorAddress,
            tokenAddresses = mapOf(
                RainChain.BASE_MAINNET to "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                RainChain.ARBITRUM_MAINNET to "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
            ),
            kind = Kind.PRODUCTION
        )

        /**
         * Explicit targets for a custom Rain API gateway. There is no safe environment inference
         * from an arbitrary URL, so custom gateways must opt in with an exact chain/token map.
         */
        fun custom(
            operatorAddress: String,
            tokenAddresses: Map<Int, String>
        ): RainAuthPullConfig = RainAuthPullConfig(operatorAddress, tokenAddresses, Kind.CUSTOM)
    }

    internal enum class Kind { SANDBOX, PRODUCTION, CUSTOM }
}
