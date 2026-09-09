package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.helpers.SolanaWithdrawFixtures
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.provider.Capability
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * A Solana withdrawal composes in the manager rather than in `TransactionCoordinator`, so it has
 * to run the same parameter validation the EVM path gets. These pin that: unusable parameters are
 * rejected before any RPC or signing, on both chain families. The first two drive a real
 * composition (devnet fixtures over a mock node) to pin how the provider's
 * [Capability.GAS_SPONSORSHIP] reaches the composer's self-paid dry run.
 */
class RainSdkManagerSolanaWithdrawTest {

    private lateinit var rpc: MockRpcServer

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    private fun manager() = TestManagers.stubProviderManager(
        rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "https://api.devnet.solana.com")
    )

    // ---------- sponsorship reaches the composer ----------

    /** The devnet fixture withdrawal: collateral, mint, and the owner as recipient. */
    private val fixtureAddresses = RainWithdrawAddresses(
        proxyAddress = SolanaWithdrawFixtures.COLLATERAL,
        controllerAddress = SolanaWithdrawFixtures.COLLATERAL, // not used on Solana
        tokenAddress = SolanaWithdrawFixtures.MINT,
        recipientAddress = SolanaWithdrawFixtures.OWNER
    )

    /** A manager over the mock node whose stub provider owns the fixture collateral. */
    private fun fixtureManager(sponsored: Boolean): Pair<RainSdkManager, StubWalletProvider> {
        val stub = StubWalletProvider().apply {
            addressToReturn = SolanaWithdrawFixtures.OWNER
            if (sponsored) capabilitiesToReturn = setOf(Capability.GAS_SPONSORSHIP)
        }
        return TestManagers.stubProviderManager(
            stub = stub,
            rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to rpc.urlFor(RainChain.SOLANA_DEVNET))
        )
    }

    @Test
    fun `withdrawCollateral skips the Solana dry run when the provider advertises gas sponsorship`(): Unit =
        runBlocking {
            // The dry run charges the fee to the owner, so a zero-SOL sponsored user would fail
            // before the sponsor ever saw the send. Composition is unchanged: golden bytes.
            SolanaWithdrawFixtures.stubHappyPath(rpc)
            val (manager, stub) = fixtureManager(sponsored = true)

            manager.withdrawCollateral(
                chainId = RainChain.SOLANA_DEVNET,
                addresses = fixtureAddresses,
                amount = BigDecimal("0.000001"), // 1 base unit at 6 decimals, as in the golden tx
                decimals = 6,
                adminSignature = SolanaWithdrawFixtures.adminSignature
            )

            assertThat(rpc.recordedMethods).doesNotContain("simulateTransaction")
            assertThat(stub.sendSolanaTransactionUnsigned.single().transactionHex)
                .isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
        }

    @Test
    fun `withdrawCollateral keeps the Solana dry run for a self-paid provider`(): Unit = runBlocking {
        // No capability advertised (Privy, or Turnkey with sponsorGas off): the owner pays the
        // fee, so the self-paid preflight must still run before anything is signed.
        SolanaWithdrawFixtures.stubHappyPath(rpc)
        val (manager, stub) = fixtureManager(sponsored = false)

        manager.withdrawCollateral(
            chainId = RainChain.SOLANA_DEVNET,
            addresses = fixtureAddresses,
            amount = BigDecimal("0.000001"),
            decimals = 6,
            adminSignature = SolanaWithdrawFixtures.adminSignature
        )

        assertThat(rpc.recordedMethods).contains("simulateTransaction")
        assertThat(stub.sendSolanaTransactionCalls).containsExactly(RainChain.SOLANA_DEVNET)
    }

    @Test
    fun `withdrawCollateral rejects a non-positive amount on Solana`() {
        val (manager, stub) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal.ZERO,
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }

        // Rejected before composition, so nothing was handed to the provider to sign.
        assert(stub.sendSolanaTransactionCalls.isEmpty())
    }

    @Test
    fun `withdrawCollateral rejects a negative amount on Solana`() {
        val (manager, _) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal("-1.0"),
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
    }

    @Test
    fun `withdrawCollateral rejects negative decimals on Solana`() {
        val (manager, _) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal("1.0"),
                    decimals = -1,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
    }

    @Test
    fun `prepareWithdrawal rejects a non-positive amount on Solana`() {
        val (manager, _) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.prepareWithdrawal(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal.ZERO,
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
    }
}
