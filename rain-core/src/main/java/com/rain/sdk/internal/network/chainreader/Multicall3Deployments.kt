package com.rain.sdk.internal.network.chainreader

/**
 * Set of EVM chains where Multicall3 is known-deployed at [Multicall3.CANONICAL_ADDRESS].
 * Used to decide between the batched `aggregate3` path and the parallel `eth_call` fallback.
 *
 * Source: https://www.multicall3.com/deployments
 */
internal val CANONICALLY_DEPLOYED_CHAIN_IDS: Set<Int> = setOf(
    1,       // Ethereum
    10,      // Optimism
    56,      // BNB Chain
    137,     // Polygon
    143,     // Monad
    324,     // zkSync Era
    8453,    // Base
    9745,    // Plasma
    42161,   // Arbitrum
    42220,   // Celo
    43114,   // Avalanche
    57073,   // Ink
)

/** True when Multicall3 is known-deployed at [Multicall3.CANONICAL_ADDRESS] on [chainId]. */
internal fun isMulticall3CanonicallyDeployed(chainId: Int): Boolean =
    chainId in CANONICALLY_DEPLOYED_CHAIN_IDS
