package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Tests for `RainSdkManager.getAllBalances` — fan-out across configured chains flattened
 * into one list, per-chain failure isolation, and the `reset()` lifecycle hook.
 */
class RainSdkManagerGetAllBalancesTest {

    private fun nativeBalance(chainId: Int) = Balance(
        token = Token.Native,
        chainId = chainId,
        rawAmount = BigInteger("1500000000000000000"),
        decimals = 18,
        symbol = "ETH",
        name = "Ether"
    )

    private fun usdcBalance(chainId: Int) = Balance(
        token = Token.Contract(TestFixtures.USDC_ADDRESS),
        chainId = chainId,
        rawAmount = BigInteger("100000000"),
        decimals = 6,
        symbol = "USDC",
        name = "USDC"
    )

    @Before
    fun setUp() {
        RainConfig.reset()
    }

    @After
    fun tearDown() {
        RainConfig.reset()
    }

    @Test
    fun `getAllBalances throws SdkNotInitialized before initialization`() {
        val manager = RainSdkManager()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { manager.getAllBalances() }
        }
    }

    @Test
    fun `getAllBalances returns empty list when no chains were configured`(): Unit = runBlocking {
        // stubProviderManager marks the SDK initialized but doesn't go through
        // `initializePortal/Turnkey`, so configuredChainIds stays empty.
        val (manager, _) = TestManagers.stubProviderManager()
        assertThat(manager.getAllBalances()).isEmpty()
    }

    @Test
    fun `getAllBalances tolerates per-chain failures and flattens healthy chains`(): Unit = runBlocking {
        val failingChain = 137
        val workingChain = 43114
        val stub = object : StubWalletProvider() {
            override suspend fun getBalances(chainId: Int): List<Balance> = when (chainId) {
                workingChain -> listOf(nativeBalance(workingChain), usdcBalance(workingChain))
                else -> throw RuntimeException("indexer down for $chainId")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(stub)
        // Inject chain IDs the same way `initializePortal` would.
        manager.setConfiguredChainIdsForTest(listOf(workingChain, failingChain))

        val balances = manager.getAllBalances()

        // The failing chain contributes nothing; only the working chain's balances survive.
        assertThat(balances).containsExactly(nativeBalance(workingChain), usdcBalance(workingChain))
        assertThat(balances.map { it.chainId }.toSet()).containsExactly(workingChain)
    }

    @Test
    fun `reset clears wallet provider and configured chain IDs`(): Unit = runBlocking {
        val (manager, _) = TestManagers.stubProviderManager()
        manager.setConfiguredChainIdsForTest(listOf(1, 137))

        manager.reset()

        // After reset, getAddress / getAllBalances see no provider and no init state.
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { manager.getWalletAddress() }
        }
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { manager.getAllBalances() }
        }
    }

    @Test
    fun `reset is idempotent`() {
        val manager = RainSdkManager()
        manager.reset()
        manager.reset()
        // No exception thrown — done.
    }
}
