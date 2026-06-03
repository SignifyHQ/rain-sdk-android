package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Manager-contract tests for the rich balance API — mode guards and routing through the
 * active [com.rain.sdk.internal.provider.WalletProvider]. Provider-specific success paths
 * live in `PortalWalletProviderTest` / `TurnkeyWalletProviderTest`.
 *
 * Note: an un-initialized manager surfaces every balance call as
 * [RainError.SdkNotInitialized] (via the `walletProvider ?: throw SdkNotInitialized()`
 * guard), not as `WalletUnavailable`.
 */
class RainSdkManagerBalanceTest {

    private val usdcBalance = Balance(
        token = Token.Contract(TestFixtures.USDC_ADDRESS),
        chainId = 1,
        rawAmount = BigInteger("100000000"),
        decimals = 6,
        symbol = "USDC",
        name = "USDC"
    )

    private val ethBalance = Balance(
        token = Token.Native,
        chainId = 1,
        rawAmount = BigInteger("1500000000000000000"),
        decimals = 18,
        symbol = "ETH",
        name = "Ether"
    )

    @Before
    fun setUp() {
        RainConfig.reset()
    }

    @After
    fun tearDown() {
        RainConfig.reset()
    }

    // ---- guards: not initialized --------------------------------------------------

    @Test
    fun `getBalance throws SdkNotInitialized before initialization`() {
        val manager = RainSdkManager()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { manager.getBalance(chainId = 1, token = Token.Native) }
        }
    }

    @Test
    fun `getBalances throws SdkNotInitialized before initialization`() {
        val manager = RainSdkManager()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { manager.getBalances(chainId = 1) }
        }
    }

    // ---- happy paths via the stub provider ----------------------------------------

    @Test
    fun `getBalance forwards chainId and token and returns provider result`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balanceToReturn = ethBalance

        val balance = manager.getBalance(chainId = 1, token = Token.Native)

        assertThat(balance).isEqualTo(ethBalance)
        assertThat(stub.getBalanceCalls).hasSize(1)
        val call = stub.getBalanceCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.token).isEqualTo(Token.Native)
    }

    @Test
    fun `getBalance forwards a contract token`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balanceToReturn = usdcBalance

        val token = Token.Contract(TestFixtures.USDC_ADDRESS)
        val balance = manager.getBalance(chainId = 1, token = token)

        assertThat(balance).isEqualTo(usdcBalance)
        assertThat(stub.getBalanceCalls.single().token).isEqualTo(token)
    }

    @Test
    fun `getBalances returns whatever the provider returned`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balancesToReturn = listOf(ethBalance, usdcBalance)

        val balances = manager.getBalances(chainId = 1)

        assertThat(balances).containsExactly(ethBalance, usdcBalance).inOrder()
        assertThat(stub.getBalancesCalls).containsExactly(1)
    }

    // ---- error handling -----------------------------------------------------------

    @Test
    fun `getBalances wraps unexpected provider failures via ErrorMapper`() {
        // ErrorMapper.mapTransactionError transitively loads Turnkey (JDK-24) classes.
        assumeJdk24()
        val failing = object : StubWalletProvider() {
            override suspend fun getBalances(chainId: Int): List<Balance> {
                throw RuntimeException("indexer 503")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)

        val ex = runCatching { runBlocking { manager.getBalances(chainId = 1) } }.exceptionOrNull()
        // Generic RuntimeException → ProviderError per ErrorMapper.mapTransactionError.
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `getBalance rethrows RainError WalletUnavailable without re-wrapping`() {
        val failing = object : StubWalletProvider() {
            override suspend fun getBalance(chainId: Int, token: Token): Balance {
                throw RainError.WalletUnavailable("no wallet")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)
        assertThrows(RainError.WalletUnavailable::class.java) {
            runBlocking { manager.getBalance(chainId = 1, token = Token.Native) }
        }
    }
}
