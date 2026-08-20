package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.RainChain
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainWithdrawAddresses
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.math.BigDecimal
import java.math.BigInteger
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
 * (the caller-supplied withdrawal authorization) and the Solana guard.
 */
class RainSdkManagerEstimateFeeTest {

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    /** Builder over a mocked Web3j; handed to every manager these tests build. */
    private lateinit var builder: RainTransactionBuilderImpl

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
        builder = RainTransactionBuilderImpl(mapOf(1 to "https://rpc.example/test")) { mockWeb3j }

        Web3jProvider.shutDownAll()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Web3jProvider.shutDownAll()
    }

    @Test
    fun `estimateWithdrawalFee signs once and returns the provider's BigDecimal fee`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
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
            adminSignature = TestFixtures.adminSignature(signature = callerSignatureHex)
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
        val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.estimateWithdrawalFee(
                    chainId = 1,
                    addresses = addresses,
                    amount = BigDecimal("1.2345678"), // 7 decimal places on a 6-decimal token
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
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
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            val error = assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
                runBlocking {
                    manager.estimateWithdrawalFee(
                        chainId = 1,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = TestFixtures.adminSignature()
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
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            val error = assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
                runBlocking {
                    manager.withdrawCollateral(
                        chainId = 1,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = TestFixtures.adminSignature()
                    )
                }
            }
            assertThat(error.cause).isSameInstanceAs(simulationFailure)
        }

    @Test
    fun `prepareWithdrawal returns the exact transaction withdrawCollateral broadcasts`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            // A pinned nonce is what makes the two runs comparable: the salt is random per call,
            // but the caller-supplied signature bytes and the encoded arguments are not.
            val prepared = manager.prepareWithdrawal(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )
            val parameters = (prepared as RainPreparedWithdrawal.Evm).parameters

            // Preparing broadcasts nothing.
            assertThat(stub.sendTransactionCalls).isEmpty()

            manager.withdrawCollateral(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )
            val broadcast = stub.sendTransactionCalls.single()

            // The prepared transaction is complete, not bare calldata — from/to/value are the
            // three fields the old `transactionData: String?` could not carry.
            assertThat(parameters.from).isEqualTo(broadcast.from)
            assertThat(parameters.to).isEqualTo(broadcast.to)
            assertThat(parameters.value).isEqualTo(broadcast.value)
            assertThat(parameters.from).isNotEmpty()
            // Same selector and same encoded length; only the random salt differs.
            assertThat(parameters.data.take(10)).isEqualTo(broadcast.data.take(10))
            assertThat(parameters.data).hasLength(broadcast.data.length)
        }

    @Test
    fun `estimateWithdrawalFee rejects a Solana chain id instead of taking the EVM path`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)

            assertThrows(RainError.InternalError::class.java) {
                runBlocking {
                    manager.estimateWithdrawalFee(
                        chainId = RainChain.SOLANA_DEVNET,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = TestFixtures.adminSignature()
                    )
                }
            }
            // The guard fires before any signing or estimation.
            assertThat(stub.signTypedDataCalls).isEmpty()
            assertThat(stub.estimateTransactionFeeCalls).isEmpty()
        }
}
