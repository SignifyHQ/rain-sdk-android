package com.rain.sdk.sample

import com.rain.sdk.models.RainApiEnvironment
import com.rain.sdk.RainAuthPullConfig

/**
 * Which Rain environment this build of the sample talks to.
 *
 * One constant drives three things that have to agree: the Rain API host, the chains the picker
 * offers, and the operator address the Auth Pull screen prefills. The SDK rejects an Auth Pull
 * approval on a chain outside the configured environment's set, so they cannot be set separately.
 *
 * Left on [RainApiEnvironment.Dev] deliberately. Production means mainnet: real USDC, real gas, and
 * an allowance a real card authorization can draw on.
 */
object SampleEnvironment {

    val rainApi: RainApiEnvironment = RainApiEnvironment.Dev

    /**
     * Rain's Auth Pull operator for [rainApi]: the spender an approval names, one address per
     * environment and the same on every chain within it. Published in Rain's Auth Pull docs:
     * https://docs.rain.xyz/docs/authorization-pull-from-user-wallet
     *
     * Prefilled here so the screen is usable immediately, and editable in the UI. A host app should
     * read this from Rain rather than shipping it as a constant, keyed off the same environment it
     * configures the SDK with.
     */
    val authPullOperator: String
        get() = when (rainApi) {
            is RainApiEnvironment.Production -> "0xA3750f692BB9Fc5e62834f9291E3D508d7Ba4F74"
            else -> "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
        }

    val authPullConfig: RainAuthPullConfig
        get() = when (rainApi) {
            is RainApiEnvironment.Production -> RainAuthPullConfig.production(authPullOperator)
            is RainApiEnvironment.Dev -> RainAuthPullConfig.sandbox(authPullOperator)
            is RainApiEnvironment.Custom -> error(
                "Custom Rain environments require an explicit Auth Pull chain/token configuration"
            )
        }

    /** A human label for the mode banner, so it is obvious which environment a build points at. */
    val displayName: String
        get() = when (rainApi) {
            is RainApiEnvironment.Dev -> "Sandbox"
            is RainApiEnvironment.Production -> "Production"
            is RainApiEnvironment.Custom -> "Custom"
        }

    val isProduction: Boolean
        get() = rainApi is RainApiEnvironment.Production
}
