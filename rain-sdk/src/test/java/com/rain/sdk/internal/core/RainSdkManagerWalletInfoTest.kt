package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Manager-contract tests for wallet-info APIs — covers `getAddress` and `getTransactions`.
 * `generateAddressQRCode` is excluded here because it returns an Android `Bitmap`, which
 * requires the Android runtime — that case belongs in `androidTest`, not pure-JVM `test`.
 */
class RainSdkManagerWalletInfoTest {

    @Before
    fun setUp() {
        RainConfig.reset()
    }

    @After
    fun tearDown() {
        RainConfig.reset()
    }

    // ---- getAddress --------------------------------------------------------------

    @Test
    fun `getAddress throws SdkNotInitialized before initialization`() {
        val manager = RainSdkManager()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { manager.getWalletAddress() }
        }
    }

    @Test
    fun `getAddress returns address from active provider`(): Unit = runBlocking {
        val (manager, _) = TestManagers.stubProviderManager()
        assertThat(manager.getWalletAddress()).isEqualTo(TestFixtures.WALLET_ADDRESS)
    }

    @Test
    fun `getAddress rethrows RainError WalletUnavailable without re-wrapping`() {
        val failing = object : StubWalletProvider() {
            override suspend fun getWalletAddress(): String {
                throw RainError.WalletUnavailable("no eth account")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)
        assertThrows(RainError.WalletUnavailable::class.java) {
            runBlocking { manager.getWalletAddress() }
        }
    }

    @Test
    fun `getAddress wraps non-RainError provider failures via ErrorMapper`() {
        assumeJdk24()
        val failing = object : StubWalletProvider() {
            override suspend fun getWalletAddress(): String {
                throw RuntimeException("network down")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)
        val ex = runCatching { runBlocking { manager.getWalletAddress() } }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- getTransactions ---------------------------------------------------------

    @Test
    fun `getTransactions throws SdkNotInitialized before initialization`() {
        val manager = RainSdkManager()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { manager.getTransactions(chainId = 1) }
        }
    }

    @Test
    fun `getTransactions returns empty result when provider has none`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.transactionsToReturn = RainTransactionResult(transactions = emptyList())

        val result = manager.getTransactions(chainId = 1)
        assertThat(result.transactions).isEmpty()
    }

    @Test
    fun `getTransactions forwards pagination + order to the provider and returns its list`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        val tx = RainTransaction(
            hash = "0xabc",
            blockNumber = "100",
            blockTimestamp = "2024-01-01T00:00:00Z",
            from = "0xfrom",
            to = "0xto",
            value = "1.0",
            chainId = "1"
        )
        stub.transactionsToReturn = RainTransactionResult(transactions = listOf(tx))

        val result = manager.getTransactions(
            chainId = 1,
            limit = 5,
            offset = 2,
            order = RainTransactionOrder.ASC
        )

        assertThat(result.transactions).hasSize(1)
        assertThat(result.transactions[0].hash).isEqualTo("0xabc")
        assertThat(stub.getTransactionsCalls).hasSize(1)
        val call = stub.getTransactionsCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.limit).isEqualTo(5)
        assertThat(call.offset).isEqualTo(2)
        assertThat(call.order).isEqualTo(RainTransactionOrder.ASC)
    }

    @Test
    fun `getTransactions wraps unexpected provider failures as ProviderError`() {
        assumeJdk24()
        val failing = object : StubWalletProvider() {
            override suspend fun getTransactions(
                chainId: Int,
                limit: Int?,
                offset: Int?,
                order: RainTransactionOrder?
            ): RainTransactionResult {
                throw RuntimeException("service unavailable")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)
        val ex = runCatching {
            runBlocking { manager.getTransactions(chainId = 1) }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
    }
}
