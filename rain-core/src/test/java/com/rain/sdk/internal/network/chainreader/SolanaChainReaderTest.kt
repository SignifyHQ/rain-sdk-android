package com.rain.sdk.internal.network.chainreader

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.models.Token
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Verifies the Solana reader parses lamports from Solana JSON-RPC `getBalance` (a JSON number,
 * not the hex EVM returns) and exposes SOL with 9-decimal metadata.
 */
class SolanaChainReaderTest {

    private lateinit var rpc: MockRpcServer
    private val devnet = RainChain.SOLANA_DEVNET
    private val wallet = "So11111111111111111111111111111111111111112"

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    private fun reader(): SolanaChainReader =
        SolanaChainReader(rpcEndpoints = mapOf(devnet to rpc.urlFor(devnet)))

    private fun balanceResult(lamports: Long): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", lamports)

    @Test
    fun `getBalance native parses lamports into a SOL balance`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(2_500_000_000L)) // 2.5 SOL

        val balance = reader().getBalance(devnet, wallet, Token.Native, null)

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(2_500_000_000L))
        assertThat(balance.decimals).isEqualTo(9)
        assertThat(balance.symbol).isEqualTo("SOL")
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)
        assertThat(rpc.recordedMethods).containsExactly("getBalance")
    }

    @Test
    fun `getNativeBalance returns human-readable SOL`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(1_000_000_000L))
        assertThat(reader().getNativeBalance(devnet, wallet)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `getBalances returns just the native SOL balance`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(750_000_000L))

        val balances = reader().getBalances(devnet, wallet, emptyList())

        assertThat(balances).hasSize(1)
        assertThat(balances.single().token).isEqualTo(Token.Native)
        assertThat(balances.single().decimalAmount.toDouble()).isWithin(1e-9).of(0.75)
    }

    @Test
    fun `contract balance is unsupported on Solana`() {
        rpc.stubObject("getBalance", balanceResult(1L))
        assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                reader().getBalance(devnet, wallet, Token.Contract("anyMint"), null)
            }
        }
    }

    @Test
    fun `rejects a non-base58 wallet address`() {
        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader().getNativeBalance(devnet, "0xnot-base58!") }
        }
    }
}
