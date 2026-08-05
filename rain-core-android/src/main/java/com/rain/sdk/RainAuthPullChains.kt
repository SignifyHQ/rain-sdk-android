package com.rain.sdk

import com.rain.sdk.models.RainApiEnvironment

/**
 * The chains Rain's Auth Pull runs on, grouped by environment.
 *
 * Rain's operator address and USDC contract both differ between sandbox and production, and the
 * two sets of chains do not overlap. Approving on a chain from the wrong environment therefore
 * produces a perfectly valid allowance that no authorization will ever draw on — and on a mainnet
 * chain it spends real gas to grant a real spend allowance to an address Rain does not use there.
 * The approval path rejects that pairing up front (see `RainSdkManager`).
 *
 * Hosts can gate their own UI on [supported] rather than keeping a second copy of the list, which
 * is how the two drift apart.
 *
 * Maintenance: in-tree like `TokenRegistry`, so the SDK owns updates. Rain is actively adding
 * chains to the beta; edit this file and ship a release. The matching USDC entries live in
 * `TokenRegistry`, and `RainAuthPullChainsTest` enforces that every chain here has one.
 */
object RainAuthPullChains {

    /** Sandbox Auth Pull chains. */
    val SANDBOX: Set<Int> = setOf(RainChain.BASE_SEPOLIA, RainChain.ARBITRUM_SEPOLIA)

    /** Production Auth Pull chains. */
    val PRODUCTION: Set<Int> = setOf(RainChain.BASE_MAINNET, RainChain.ARBITRUM_MAINNET)

    /**
     * The Auth Pull chains for [environment].
     *
     * [RainApiEnvironment.Dev] maps to Rain's sandbox set: Rain documents the operator per
     * *sandbox* / *production*, and `api-dev.rain.xyz` is the sandbox-side host. A
     * [RainApiEnvironment.Custom] base URL can front either environment, so it permits both rather
     * than guessing wrong in one direction.
     */
    fun supported(environment: RainApiEnvironment): Set<Int> = when (environment) {
        is RainApiEnvironment.Dev -> SANDBOX
        is RainApiEnvironment.Production -> PRODUCTION
        is RainApiEnvironment.Custom -> SANDBOX + PRODUCTION
    }

    /** Whether [chainId] is an Auth Pull chain in [environment]. */
    fun isSupported(chainId: Int, environment: RainApiEnvironment): Boolean =
        chainId in supported(environment)
}
