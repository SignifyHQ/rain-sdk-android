package com.rain.sdk.internal.core

import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.RainWithdrawAddresses
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/**
 * A Solana withdrawal composes in the manager rather than in `TransactionCoordinator`, so it has
 * to run the same parameter validation the EVM path gets. These pin that: unusable parameters are
 * rejected before any RPC or signing, on both chain families.
 */
class RainSdkManagerSolanaWithdrawTest {

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    private fun manager() = TestManagers.stubProviderManager(
        rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "https://api.devnet.solana.com")
    )

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
