package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.RainWithdrawAddresses
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Core asks the provider whether it can broadcast on the chain before a withdrawal or approval
 * does any work. The provider's own funnel gate would catch the same chain later, but only after
 * the contract reads and a biometric signing prompt the user then sees refused.
 */
class RainSdkManagerSendGateTest {

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    private fun refusingStub() = StubWalletProvider().apply {
        requireSendSupportError = RainError.ChainNotSupported(1, "this provider cannot broadcast here")
    }

    @Test
    fun `withdrawCollateral asks the provider before any signing or network work`() {
        val (manager, stub) = TestManagers.stubProviderManager(stub = refusingStub())

        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                manager.withdrawCollateral(1, addresses, BigDecimal("1"), 6, TestFixtures.adminSignature())
            }
        }

        assertThat(error.chainId).isEqualTo(1)
        assertThat(stub.signTypedDataCalls).isEmpty()
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `prepareWithdrawal is gated the same way because it signs too`() {
        val (manager, stub) = TestManagers.stubProviderManager(stub = refusingStub())

        assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                manager.prepareWithdrawal(1, addresses, BigDecimal("1"), 6, TestFixtures.adminSignature())
            }
        }

        assertThat(stub.signTypedDataCalls).isEmpty()
    }

    @Test
    fun `a Solana withdrawal is refused before the composer reads anything`() {
        val (manager, stub) = TestManagers.stubProviderManager(
            stub = refusingStub(),
            rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "http://127.0.0.1:1/unreachable")
        )

        assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    RainChain.SOLANA_DEVNET, addresses, BigDecimal("1"), 6, TestFixtures.adminSignature()
                )
            }
        }

        assertThat(stub.sendSolanaTransactionCalls).isEmpty()
    }

    @Test
    fun `approveTokenAllowance is refused before the wallet is touched`() {
        val (manager, stub, _) = TestManagers.approvalManager(stub = refusingStub())

        assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                manager.approveTokenAllowance(
                    RainChain.BASE_SEPOLIA,
                    TestFixtures.AUTH_PULL_USDC_ADDRESS,
                    "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
                )
            }
        }

        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `estimateApprovalFee is a read and is not gated`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(stub = refusingStub())
        stub.estimateTransactionFeeToReturn = BigDecimal("0.001")

        val fee = manager.estimateApprovalFee(
            RainChain.BASE_SEPOLIA,
            TestFixtures.AUTH_PULL_USDC_ADDRESS,
            "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
        )

        assertThat(fee).isEqualTo(BigDecimal("0.001"))
    }
}
