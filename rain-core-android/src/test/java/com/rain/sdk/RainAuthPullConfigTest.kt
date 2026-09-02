package com.rain.sdk

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.constants.TokenRegistry
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
    private val zeroAddress = "0x0000000000000000000000000000000000000000"
    private val baseSepoliaUsdc = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
    private val customGateway = RainApiEnvironment.Custom("https://rain.example")

    /** A real EVM chain that is not an Auth Pull chain in either environment. */
    private val ETHEREUM_MAINNET = 1

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

    // ---- the canonical token maps ------------------------------------------------------------
    // Hardcoded addresses with nothing to check them against: a one-character typo would pass
    // every other test here and send approvals to the wrong contract.

    @Test
    fun `the sandbox token map is the registry's USDC on exactly the sandbox chains`() {
        assertTokenMapMatchesRegistry(
            RainAuthPullConfig.sandbox(operator).tokenAddresses,
            RainAuthPullChains.SANDBOX
        )
    }

    @Test
    fun `the production token map is the registry's USDC on exactly the production chains`() {
        assertTokenMapMatchesRegistry(
            RainAuthPullConfig.production(operator).tokenAddresses,
            RainAuthPullChains.PRODUCTION
        )
    }

    private fun assertTokenMapMatchesRegistry(tokenAddresses: Map<Int, String>, chains: Set<Int>) {
        assertThat(tokenAddresses.keys).containsExactlyElementsIn(chains)
        for ((chainId, address) in tokenAddresses) {
            val usdc = TokenRegistry.tokensFor(chainId).single { it.symbol == "USDC" }
            // Exact, checksum casing included: the registry is the one copy that gets maintained.
            assertThat(address).isEqualTo(usdc.address)
        }
    }

    // ---- builder validation ------------------------------------------------------------------
    // One test per rejection branch in `RainSdk.Builder.validateAuthPullConfig`.

    private fun customConfig(
        operatorAddress: String = operator,
        tokenAddresses: Map<Int, String> = mapOf(RainChain.BASE_SEPOLIA to baseSepoliaUsdc)
    ) = RainAuthPullConfig.custom(operatorAddress, tokenAddresses)

    private fun buildCustom(config: RainAuthPullConfig): RainError.InvalidConfig =
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
                .rainApiEnvironment(customGateway)
                .authPullConfig(config)
                .build()
        }

    @Test
    fun `a malformed operator address is rejected`() {
        val error = buildCustom(customConfig(operatorAddress = "0xnot-an-address"))
        assertThat(error.message).contains("operator")
    }

    @Test
    fun `the zero address is rejected as the operator`() {
        val error = buildCustom(customConfig(operatorAddress = zeroAddress))
        assertThat(error.message).contains("zero address")
    }

    /** Address validation is 0x-optional, so the zero check must be too. */
    @Test
    fun `the prefix-less zero address is rejected as the operator`() {
        val error = buildCustom(customConfig(operatorAddress = zeroAddress.removePrefix("0x")))
        assertThat(error.message).contains("zero address")
    }

    @Test
    fun `a custom config must name at least one token contract`() {
        val error = buildCustom(customConfig(tokenAddresses = emptyMap()))
        assertThat(error.message).contains("at least one token")
    }

    /** Even a custom gateway may only name chains from the two Auth Pull sets. */
    @Test
    fun `a chain outside both Auth Pull sets is rejected on a custom gateway`() {
        val error = buildCustom(
            customConfig(tokenAddresses = mapOf(ETHEREUM_MAINNET to baseSepoliaUsdc))
        )
        assertThat(error.message).contains("do not match")
    }

    @Test
    fun `a malformed token contract is rejected`() {
        val error = buildCustom(customConfig(tokenAddresses = mapOf(RainChain.BASE_SEPOLIA to "0xnope")))
        assertThat(error.message).contains("token contract")
    }

    @Test
    fun `the zero address is rejected as a token contract`() {
        val error = buildCustom(customConfig(tokenAddresses = mapOf(RainChain.BASE_SEPOLIA to zeroAddress)))
        assertThat(error.message).contains("token contract")
    }

    @Test
    fun `the prefix-less zero address is rejected as a token contract`() {
        val error = buildCustom(
            customConfig(tokenAddresses = mapOf(RainChain.BASE_SEPOLIA to zeroAddress.removePrefix("0x")))
        )
        assertThat(error.message).contains("token contract")
    }

    /** A trusted chain with no RPC endpoint can never carry an approval; all of them missing is a misconfiguration. */
    @Test
    fun `a config none of whose chains has an RPC endpoint is rejected`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(RainChain.AVALANCHE_TESTNET to "https://rpc.example/fuji"))
                .authPullConfig(RainAuthPullConfig.sandbox(operator))
                .build()
        }
        assertThat(error.message).contains("No RPC endpoint")
    }

    @Test
    fun `a sandbox config is rejected in the production environment`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
                .rainApiEnvironment(RainApiEnvironment.Production)
                .authPullConfig(RainAuthPullConfig.sandbox(operator))
                .build()
        }
    }

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
