package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.turnkey.core.TurnkeyContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins what the Turnkey descriptor advertises. `RainSdk` copies the descriptor's capability set
 * onto the resolved client, so this is the set a host sees through `client.capabilities` and
 * `rain.first { }`. It has to match what the materialized wallet provider reports, or the host
 * and core would disagree about whether this provider's sends are sponsored.
 *
 * [TurnkeyConfig] needs the vendor's `TurnkeyContext` singleton, whose class initializer
 * dispatches onto `Dispatchers.Main`; on the JVM that dispatcher is absent, so a test dispatcher
 * stands in for it. Nothing dispatched there is ever run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyProviderTest {

    @Before
    fun setUp() {
        assumeJdk24()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun config(sponsorGas: Boolean) =
        TurnkeyConfig(turnkey = TurnkeyContext, sponsorGas = sponsorGas)

    @Test
    fun `advertises gas sponsorship by default`() {
        val descriptor = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext))

        assertThat(descriptor.capabilities).containsExactly(
            Capability.MULTI_CHAIN,
            Capability.BIOMETRIC_GATE,
            Capability.GAS_SPONSORSHIP
        )
    }

    @Test
    fun `does not advertise gas sponsorship when sponsorGas is off`() {
        val descriptor = TurnkeyProvider(config(sponsorGas = false))

        assertThat(descriptor.capabilities).containsExactly(
            Capability.MULTI_CHAIN,
            Capability.BIOMETRIC_GATE
        )
    }

    @Test
    fun `descriptor and the wallet provider it creates advertise the same capabilities`(): Unit =
        runBlocking {
            listOf(true, false).forEach { sponsorGas ->
                val descriptor = TurnkeyProvider(config(sponsorGas), contextOverride = MockTurnkey())
                try {
                    val wallet = descriptor.create(providerContext())

                    assertThat(wallet.capabilities).isEqualTo(descriptor.capabilities)
                    assertThat(Capability.GAS_SPONSORSHIP in wallet.capabilities).isEqualTo(sponsorGas)
                } finally {
                    descriptor.close()
                }
            }
        }

    /** The vendor-free context core hands every descriptor; nothing here is called during create. */
    private fun providerContext(): ProviderContext {
        val rpcEndpoints = mapOf(1 to "http://127.0.0.1:1/unused")
        val evm = EvmChainReader(rpcEndpoints = rpcEndpoints)
        return ProviderContext(
            rpcEndpoints = rpcEndpoints,
            tokenStore = TokenMetadataStore(chainReader = evm),
            evmChainReader = evm,
            solanaSupport = SolanaSupport(rpcEndpoints)
        )
    }
}
