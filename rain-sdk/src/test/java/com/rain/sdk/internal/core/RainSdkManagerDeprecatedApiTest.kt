package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Backward-compatibility guard for the @Deprecated shims kept so clients built against the
 * pre-consolidation `main` API keep working. Each shim must delegate to the current rich API
 * and reproduce the old shape exactly. If a shim is removed or its delegation drifts, a
 * client upgrading the AAR breaks — these tests catch that.
 */
@Suppress("DEPRECATION")
class RainSdkManagerDeprecatedApiTest {

    private val usdcBalance = Balance(
        token = Token.Contract(TestFixtures.USDC_ADDRESS),
        chainId = 1,
        rawAmount = BigInteger("100000000"), // 100 USDC at 6 decimals
        decimals = 6,
        symbol = "USDC",
        name = "USDC"
    )

    private val ethBalance = Balance(
        token = Token.Native,
        chainId = 1,
        rawAmount = BigInteger("1500000000000000000"), // 1.5 ETH at 18 decimals
        decimals = 18,
        symbol = "ETH",
        name = "Ether"
    )

    // Checksummed (mixed-case) contract address — proves the shim keys the map by the
    // provider's address verbatim (no lowercasing), exactly as main's getBalances did.
    private val daiChecksummed = "0x6B175474E89094C44Da98b954EedeAC495271d0F"
    private val daiBalance = Balance(
        token = Token.Contract(daiChecksummed),
        chainId = 1,
        rawAmount = BigInteger("2000000000000000000"), // 2 DAI at 18 decimals
        decimals = 18,
        symbol = "DAI",
        name = "Dai"
    )

    @Before
    fun setUp() = RainConfig.reset()

    @After
    fun tearDown() = RainConfig.reset()

    @Test
    fun `deprecated getBalances keys native under empty string and contracts by provider address verbatim`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager()
            stub.balancesToReturn = listOf(ethBalance, daiBalance)

            val map = manager.getBalances(chainId = 1)

            assertThat(map[""]).isEqualTo(1.5)
            assertThat(map[daiChecksummed]).isEqualTo(2.0)         // exact case preserved
            assertThat(map[daiChecksummed.lowercase()]).isNull()   // NOT lowercased — matches main
            assertThat(map).hasSize(2)
        }

    @Test
    fun `deprecated getERC20Balances drops native and keeps only contract tokens`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balancesToReturn = listOf(ethBalance, usdcBalance)

        val map = manager.getERC20Balances(chainId = 1)

        assertThat(map).containsExactly(TestFixtures.USDC_ADDRESS, 100.0)
    }

    @Test
    fun `deprecated getAddress delegates to getWalletAddress`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.addressToReturn = TestFixtures.WALLET_ADDRESS

        assertThat(manager.getAddress()).isEqualTo(TestFixtures.WALLET_ADDRESS)
    }

    @Test
    fun `deprecated getNativeBalance collapses the native Balance to a Double`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balanceToReturn = ethBalance

        assertThat(manager.getNativeBalance(chainId = 1)).isEqualTo(1.5)
    }
}
