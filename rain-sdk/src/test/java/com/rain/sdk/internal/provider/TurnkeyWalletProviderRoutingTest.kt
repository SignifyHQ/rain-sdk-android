package com.rain.sdk.internal.provider

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.turnkey.types.V1AssetBalance
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Verifies the chain-routing decision in [TurnkeyWalletProvider]:
 *  - Chains in [com.rain.sdk.internal.constants.RainConstants.TURNKEY_SUPPORTED_CHAINS] go
 *    through Turnkey's `get_wallet_address_balances`.
 *  - Anything else falls through to the injected [com.rain.sdk.internal.network.chainreader.ChainReader].
 *  - `getERC20Balance` is delegated to the ChainReader unconditionally — same RPC call
 *    everywhere, no reason to maintain two implementations.
 */
class TurnkeyWalletProviderRoutingTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private fun makeProvider(
        chainReader: MockChainReader,
        chainId: Int = 1
    ): TurnkeyWalletProvider {
        val turnkey = MockTurnkey()
        // The MockTurnkeyClient on `turnkey` returns no balances by default — fine for chain
        // ID 1 since we override behavior via the chainReader only when off-allowlist.
        return TurnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(chainId to "https://eth.example/rpc"),
            walletAddressOverride = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            httpClient = OkHttpClient(),
            chainReader = chainReader
        )
    }

    // ---------- supported-chain path ----------

    @Test
    fun `getBalance native on Ethereum 1 routes through Turnkey balances, not ChainReader`() = runBlocking {
        val chainReader = MockChainReader()
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "500000000000000000", // 0.5 ETH
                    caip19 = "eip155:1/slip44:60",
                    decimals = 18L,
                    display = null,
                    name = "Ethereum",
                    symbol = "ETH"
                )
            )
        )
        val provider = TurnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            walletAddressOverride = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            httpClient = OkHttpClient(),
            chainReader = chainReader
        )

        val balance = provider.getBalance(chainId = 1, token = Token.Native)

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(0.5)
        // Turnkey-supported chain shouldn't have touched the ChainReader at all.
        assertThat(chainReader.balanceCalls).isEmpty()
    }

    // ---------- unsupported-chain fallback ----------

    @Test
    fun `getBalance native on Avalanche Fuji 43113 routes through ChainReader`() = runBlocking {
        val chainReader = MockChainReader(
            balance = Balance(Token.Native, 43113, BigInteger("2500000000000000000"), 18, "AVAX", "Avalanche")
        )
        val provider = makeProvider(chainReader, chainId = 43113)

        val balance = provider.getBalance(chainId = 43113, token = Token.Native)

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)
        assertThat(chainReader.balanceCalls).hasSize(1)
        val call = chainReader.balanceCalls.single()
        assertThat(call.chainId).isEqualTo(43113)
        assertThat(call.token).isEqualTo(Token.Native)
    }

    @Test
    fun `getBalances on unsupported chain delegates to ChainReader and filters zero balances`() = runBlocking {
        val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        val zero = "0x6b175474e89094c44da98b954eedeac495271d0f"
        val chainReader = MockChainReader(
            balances = listOf(
                Balance(Token.Native, 43113, BigInteger("1000000000000000000"), 18, "AVAX", "Avalanche"),
                Balance(Token.Contract(usdc), 43113, BigInteger("100000000"), 6, "USDC", "USDC"),
                Balance(Token.Contract(zero), 43113, BigInteger.ZERO, 18, "ZERO", "Zero") // filtered out
            )
        )
        val provider = makeProvider(chainReader, chainId = 43113)

        val balances = provider.getBalances(chainId = 43113)

        // Native always kept; zero-balance contract dropped.
        assertThat(balances.map { it.token })
            .containsExactly(Token.Native, Token.Contract(usdc))
        assertThat(chainReader.balancesCalls).hasSize(1)
    }

    // ---------- unconditional delegation ----------

    @Test
    fun `getBalance contract always delegates to ChainReader, even on Turnkey-supported chains`() = runBlocking {
        val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        val chainReader = MockChainReader(
            balance = Balance(Token.Contract(usdc), 1, BigInteger("42000000"), 6, "USDC", "USDC")
        )
        val provider = makeProvider(chainReader, chainId = 1)

        val balance = provider.getBalance(chainId = 1, token = Token.Contract(usdc))

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(42.0)
        assertThat(chainReader.balanceCalls).hasSize(1)
        val call = chainReader.balanceCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.token).isEqualTo(Token.Contract(usdc))
    }

    // ---------- address cache ----------

    @Test
    fun `getAddress is cached after first resolve and skips wallet refresh on subsequent calls`() = runBlocking {
        val turnkey = MockTurnkey()
        var refreshCount = 0
        val cached = object : TurnkeyContextProtocol by turnkey {
            override suspend fun refreshWallets() {
                refreshCount++
                turnkey.wallets = listOf(MockTurnkey.defaultWallet())
            }
        }
        val provider = TurnkeyWalletProvider(
            turnkey = cached,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            httpClient = OkHttpClient(),
            chainReader = MockChainReader()
        )

        val first = provider.getWalletAddress()
        val second = provider.getWalletAddress()
        val third = provider.getWalletAddress()

        assertThat(first).isEqualTo(second)
        assertThat(second).isEqualTo(third)
        // wallets already contained a usable address, so refresh shouldn't have run at all.
        assertThat(refreshCount).isEqualTo(0)
    }
}
