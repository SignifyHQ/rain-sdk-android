package com.rain.sdk.turnkey

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.helpers.expectThrows
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.provider.ProviderContext
import com.turnkey.core.TurnkeyContext
import com.turnkey.types.V1AddressFormat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The managed-auth surface of [TurnkeyProvider]: the two [TurnkeyConfig] modes, the BYO guard,
 * forwarding to the controller through the real wiring (only the vendor context is faked), and
 * account provisioning at resolution.
 *
 * The BYO constructor takes the vendor's `TurnkeyContext` singleton, whose class initializer
 * dispatches onto `Dispatchers.Main`; on the JVM that dispatcher is absent, so a test dispatcher
 * stands in for it. Nothing dispatched there is ever run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyManagedProviderTest {

    /** True only once setMain ran: on a JDK the assumption skips, @After still runs. */
    private var mainSet = false

    @Before
    fun setUp() {
        assumeJdk24()
        Dispatchers.setMain(StandardTestDispatcher())
        mainSet = true
        TurnkeyManagedConfigurator.resetForTest()
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
    }

    @After
    fun tearDown() {
        TurnkeyManagedConfigurator.resetForTest()
        if (mainSet) Dispatchers.resetMain()
    }

    private fun managedConfig(
        organizationId: String = "org-a",
        authProxyConfigId: String = "proxy-a",
    ) = TurnkeyConfig(
        application = mockk<Application>(),
        organizationId = organizationId,
        authProxyConfigId = authProxyConfigId,
    )

    /** The vendor-free context core hands every descriptor; nothing here hits the network. */
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

    // ---------- bring-your-own mode ----------

    @Test
    fun `BYO mode keeps its constructor and exposes no authentication`() = runTest {
        val turnkey = MockTurnkey()
        val provider = TurnkeyProvider(
            TurnkeyConfig(turnkey = TurnkeyContext, sponsorGas = false),
            contextOverride = turnkey,
        )

        assertThat(provider.hasActiveSession()).isFalse()
        assertThat(provider.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(provider.authState.first()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        provider.awaitSessionRestore(timeoutMs = 10) // no-op, must not throw
        val thrown = expectThrows<RainError.InvalidConfig> { provider.sendLoginCode("user@example.com") }
        assertThat(thrown).hasMessageThat().contains("managed mode")
        expectThrows<RainError.InvalidConfig> { provider.confirmLoginCode("123456") }
        expectThrows<RainError.InvalidConfig> { provider.logout() }
        assertThat(turnkey.sendOtpCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }

    // ---------- managed mode ----------

    @Test
    fun `managed mode configures the vendor with the config's ids on the first auth call, not at construction`() = runTest {
        val configured = mutableListOf<Pair<String, String>>()
        TurnkeyManagedConfigurator.initImpl = { _, organizationId, authProxyConfigId ->
            configured += organizationId to authProxyConfigId
        }
        val turnkey = MockTurnkey(session = null)
        val provider = TurnkeyProvider(managedConfig("org-a", "proxy-a"), contextOverride = turnkey)
        assertThat(configured).isEmpty()

        provider.sendLoginCode("user@example.com")

        assertThat(configured).containsExactly("org-a" to "proxy-a")
        assertThat(turnkey.sendOtpCalls).containsExactly(MockTurnkey.SendOtpCall("user@example.com", OtpChannel.EMAIL))
    }

    @Test
    fun `managed mode forwards the OTP flow and reports the session it produced`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onCompleteOtp = { turnkey.authenticate() }
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey)
        assertThat(provider.hasActiveSession()).isFalse()

        provider.sendLoginCode("user@example.com")
        provider.confirmLoginCode("123456")

        assertThat(turnkey.completeOtpCalls).hasSize(1)
        assertThat(provider.hasActiveSession()).isTrue()
        assertThat(provider.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(provider.authState.first()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `resolving a managed provider provisions a half-provisioned restored session before the wallet probe`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet())) // live session, Ethereum only
        turnkey.onCreateWalletAccounts = { turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana()) }
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey)
        try {
            val wallet = provider.create(providerContext())

            val added = turnkey.createWalletAccountsCalls.single()
            assertThat(added.walletId).isEqualTo("wallet-id")
            assertThat(added.accounts.map { it.addressFormat })
                .containsExactly(V1AddressFormat.ADDRESS_FORMAT_SOLANA)
            assertThat(turnkey.createWalletCalls).isEmpty()
            assertThat(wallet.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `resolving a BYO provider never provisions`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()))
        val provider = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext), contextOverride = turnkey)
        try {
            provider.create(providerContext())
            assertThat(turnkey.createWalletCalls).isEmpty()
        } finally {
            provider.close()
        }
    }

    @Test
    fun `blank managed ids are rejected on the first auth call with InvalidConfig`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val provider = TurnkeyProvider(managedConfig(organizationId = "  "), contextOverride = turnkey)

        expectThrows<RainError.InvalidConfig> { provider.sendLoginCode("user@example.com") }
        assertThat(turnkey.sendOtpCalls).isEmpty()
    }

    @Test
    fun `a closed managed provider refuses authentication and reads unauthenticated`() = runTest {
        val turnkey = MockTurnkey()
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey)

        provider.close()

        expectThrows<RainError.InvalidConfig> { provider.sendLoginCode("user@example.com") }
        expectThrows<RainError.InvalidConfig> { provider.logout() }
        assertThat(provider.hasActiveSession()).isFalse()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }
}
