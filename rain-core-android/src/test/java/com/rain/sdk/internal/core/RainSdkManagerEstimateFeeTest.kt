package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthCall

/**
 * Manager-contract tests for `estimateWithdrawalFee`: the primary BigDecimal overload
 * (caller-supplied salt / signature / expiresAt) and the deprecated Double/adminSignature shim.
 */
class RainSdkManagerEstimateFeeTest {

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    @Before
    fun setUp() {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true

        // Stub every eth_call (admin-nonce read + isAdmin preflight) with uint256(1): nonce = 1,
        // and Bool decode = true so the collateral-admin check passes.
        val mockWeb3j = mockk<Web3j>(relaxed = true)
        val mockEthCall = mockk<Request<*, EthCall>>()
        val response = EthCall().apply {
            result = "0x" + "0".repeat(63) + "1"
        }
        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(response)
        RainTransactionBuilderImpl.web3jFactory = { _ -> mockWeb3j }

        RainConfig.reset()
        Web3jProvider.shutDownAll()
    }

    @After
    fun tearDown() {
        unmockkAll()
        RainConfig.reset()
        Web3jProvider.shutDownAll()
        RainTransactionBuilderImpl.resetFactory()
    }

    @Test
    fun `estimateWithdrawalFee signs once and returns the provider's BigDecimal fee`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        RainConfig.getInstance().setRpcUrl(1, "https://rpc.example/test")
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex
        stub.estimateTransactionFeeToReturn = BigDecimal("0.00042")
        // Caller-supplied authorization, distinct from the wallet signature so the calldata
        // assertion below proves the caller's values (not the wallet's) were embedded.
        val callerSignatureHex = "0x" + "02".repeat(65)

        val fee = manager.estimateWithdrawalFee(
            chainId = 1,
            addresses = addresses,
            amount = BigDecimal("100.0"),
            decimals = 6,
            salt = TestFixtures.validSaltBase64,
            signature = callerSignatureHex,
            expiresAt = "2030-12-31T23:59:59Z"
        )

        assertThat(fee).isEqualTo(BigDecimal("0.00042"))
        // One wallet signature (the EIP-712 payload), and one fee estimate against the controller.
        assertThat(stub.signTypedDataCalls).hasSize(1)
        val estimate = stub.estimateTransactionFeeCalls.single()
        assertThat(estimate.chainId).isEqualTo(1)
        assertThat(estimate.to).isEqualTo(TestFixtures.CONTROLLER_ADDRESS)
        assertThat(estimate.value).isEqualTo("0x0")
        // The whole point of this overload: the estimated calldata embeds the caller-supplied
        // withdrawal authorization (salt bytes and signature bytes), not placeholders.
        val calldata = estimate.data.lowercase()
        assertThat(calldata).contains("aa".repeat(32)) // validSaltBase64 decodes to 32 x 0xAA
        assertThat(calldata).contains("02".repeat(65)) // caller's admin signature bytes
        // Nothing was broadcast.
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `estimateWithdrawalFee throws InvalidAmount for over-precision amounts`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        RainConfig.getInstance().setRpcUrl(1, "https://rpc.example/test")
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.estimateWithdrawalFee(
                    chainId = 1,
                    addresses = addresses,
                    amount = BigDecimal("1.2345678"), // 7 decimal places on a 6-decimal token
                    decimals = 6,
                    salt = TestFixtures.validSaltBase64,
                    signature = TestFixtures.validSignatureHex,
                    expiresAt = "2030-12-31T23:59:59Z"
                )
            }
        }
    }

    @Test
    fun `estimateWithdrawalFee translates a provider simulation failure to WithdrawalRevertedByNetwork`(): Unit =
        runBlocking {
            val simulationFailure = RainError.TransactionSimulationFailed(RuntimeException("execution reverted"))
            val stub = object : StubWalletProvider() {
                override suspend fun estimateTransactionFee(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): BigDecimal = throw simulationFailure
            }
            val (manager, _) = TestManagers.stubProviderManager(stub)
            RainConfig.getInstance().setRpcUrl(1, "https://rpc.example/test")
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            val error = assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
                runBlocking {
                    manager.estimateWithdrawalFee(
                        chainId = 1,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        salt = TestFixtures.validSaltBase64,
                        signature = TestFixtures.validSignatureHex,
                        expiresAt = "2030-12-31T23:59:59Z"
                    )
                }
            }
            assertThat(error.cause).isSameInstanceAs(simulationFailure)
        }

    @Test
    fun `withdrawCollateral translates a provider simulation failure to WithdrawalRevertedByNetwork`(): Unit =
        runBlocking {
            val simulationFailure = RainError.TransactionSimulationFailed(RuntimeException("execution reverted"))
            val stub = object : StubWalletProvider() {
                override suspend fun sendTransaction(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): String = throw simulationFailure
            }
            val (manager, _) = TestManagers.stubProviderManager(stub)
            RainConfig.getInstance().setRpcUrl(1, "https://rpc.example/test")
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            val error = assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
                runBlocking {
                    manager.withdrawCollateral(
                        chainId = 1,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = RainAdminSignature(
                            salt = TestFixtures.validSaltBase64,
                            signature = TestFixtures.validSignatureHex,
                            expiresAt = "2030-12-31T23:59:59Z"
                        ),
                        autoSend = true
                    )
                }
            }
            assertThat(error.cause).isSameInstanceAs(simulationFailure)
        }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated Double overload delegates and collapses the fee to Double`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        RainConfig.getInstance().setRpcUrl(1, "https://rpc.example/test")
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex
        stub.estimateTransactionFeeToReturn = BigDecimal("0.00042")

        val fee = manager.estimateWithdrawalFee(
            chainId = 1,
            addresses = addresses,
            amount = 100.0,
            decimals = 6,
            adminSignature = RainAdminSignature(
                salt = TestFixtures.validSaltBase64,
                signature = TestFixtures.validSignatureHex,
                expiresAt = "2030-12-31T23:59:59Z"
            )
        )

        assertThat(fee).isWithin(1e-12).of(0.00042)
        assertThat(stub.estimateTransactionFeeCalls).hasSize(1)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated Double overload rejects a non-finite amount with InvalidAmount`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        RainConfig.getInstance().setRpcUrl(1, "https://rpc.example/test")

        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(RainError.InvalidAmount::class.java) {
                runBlocking {
                    manager.estimateWithdrawalFee(
                        chainId = 1,
                        addresses = addresses,
                        amount = amount,
                        decimals = 6,
                        adminSignature = RainAdminSignature(
                            salt = TestFixtures.validSaltBase64,
                            signature = TestFixtures.validSignatureHex,
                            expiresAt = "2030-12-31T23:59:59Z"
                        )
                    )
                }
            }
        }
        // The guard fires before any signing or estimation.
        assertThat(stub.signTypedDataCalls).isEmpty()
        assertThat(stub.estimateTransactionFeeCalls).isEmpty()
    }
}
