package com.rain.sdk

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.models.RainApiEnvironment
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RainAuthPullConfigTest {

    private val operator = "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"

    @Before
    fun setUp() {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `sandbox config permits a trusted subset with a configured RPC`() {
        RainSdk.builder()
            .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
            .authPullConfig(RainAuthPullConfig.sandbox(operator))
            .build()
    }

    // ---- the resolved chain set --------------------------------------------------------------

    /**
     * The set the approval guard enforces, which is not the environment's set: a sandbox config
     * covers two chains, but only the one with an RPC endpoint can carry an approval.
     */
    @Test
    fun `the resolved chain set is the config narrowed to chains with an RPC`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
            .authPullConfig(RainAuthPullConfig.sandbox(operator))
            .build()

        assertThat(rain.authPullChainIds).containsExactly(RainChain.BASE_SEPOLIA)
        // The environment's set is the wider answer, and the one a host must not gate UI on.
        assertThat(RainAuthPullChains.supported(RainApiEnvironment.Dev))
            .contains(RainChain.ARBITRUM_SEPOLIA)
    }

    @Test
    fun `every configured chain with an RPC is resolved`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(
                mapOf(
                    RainChain.BASE_SEPOLIA to "https://rpc.example/base",
                    RainChain.ARBITRUM_SEPOLIA to "https://rpc.example/arbitrum",
                    // An unrelated chain must not leak into the Auth Pull set.
                    RainChain.AVALANCHE_TESTNET to "https://rpc.example/fuji"
                )
            )
            .authPullConfig(RainAuthPullConfig.sandbox(operator))
            .build()

        assertThat(rain.authPullChainIds)
            .containsExactly(RainChain.BASE_SEPOLIA, RainChain.ARBITRUM_SEPOLIA)
    }

    /** No `authPullConfig(...)` means Auth Pull is off, and the set has to say so. */
    @Test
    fun `an unconfigured SDK resolves no Auth Pull chains`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
            .build()

        assertThat(rain.authPullChainIds).isEmpty()
    }

    /**
     * The case the environment set cannot answer at all: `supported(Custom)` is empty by design,
     * so a custom gateway's own chains are only discoverable through the resolved set.
     */
    @Test
    fun `a custom gateway can enumerate the chains it configured`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(
                mapOf(
                    RainChain.BASE_SEPOLIA to "https://rpc.example/base",
                    RainChain.ARBITRUM_SEPOLIA to "https://rpc.example/arbitrum"
                )
            )
            .rainApiEnvironment(RainApiEnvironment.Custom("https://rain.example"))
            .authPullConfig(
                RainAuthPullConfig.custom(
                    operatorAddress = operator,
                    tokenAddresses = mapOf(
                        RainChain.ARBITRUM_SEPOLIA to "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d"
                    )
                )
            )
            .build()

        assertThat(rain.authPullChainIds).containsExactly(RainChain.ARBITRUM_SEPOLIA)
        assertThat(RainAuthPullChains.supported(RainApiEnvironment.Custom("https://rain.example")))
            .isEmpty()
    }

    // ---- builder validation ------------------------------------------------------------------

    @Test
    fun `production config is rejected in the dev environment`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(
                    mapOf(
                        RainChain.BASE_MAINNET to "https://rpc.example/base",
                        RainChain.ARBITRUM_MAINNET to "https://rpc.example/arbitrum"
                    )
                )
                .authPullConfig(RainAuthPullConfig.production(operator))
                .build()
        }
    }

    @Test
    fun `custom environment requires explicit custom targets`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(
                    mapOf(
                        RainChain.BASE_SEPOLIA to "https://rpc.example/base",
                        RainChain.ARBITRUM_SEPOLIA to "https://rpc.example/arbitrum"
                    )
                )
                .rainApiEnvironment(RainApiEnvironment.Custom("https://rain.example"))
                .authPullConfig(RainAuthPullConfig.sandbox(operator))
                .build()
        }
    }
}
