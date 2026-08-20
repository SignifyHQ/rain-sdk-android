package com.rain.sdk.internal.network.chainreader

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Covers the two read paths the reader exposes:
 *  - Multicall3 batched aggregate3 (canonically-deployed chain — Ethereum mainnet, id = 1)
 *  - Parallel `eth_call` fallback (chain outside the deployment list — Avalanche Fuji 43113)
 *
 * Plus the rich [Token]/[com.rain.sdk.models.Balance] surface and metadata reads
 * (`getDecimals` / `getSymbol`).
 */
class EvmChainReaderTest {

    private lateinit var rpc: MockRpcServer

    private val wallet = "0x1111111111111111111111111111111111111111"
    private val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val dai = "0x6b175474e89094c44da98b954eedeac495271d0f"

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        rpc.shutdown()
    }

    private fun makeReader(chainId: Int): EvmChainReader =
        EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

    // ---------- single-token paths ----------

    @Test
    fun `getNativeBalance parses eth_getBalance hex into ether units`(): Unit = runBlocking {
        // 0xDE0B6B3A7640000 = 1e18 wei = 1 ETH
        rpc.stub("eth_getBalance", "0xde0b6b3a7640000")
        val reader = makeReader(chainId = 1)

        val balance = reader.getNativeBalance(1, wallet)

        assertThat(balance).isEqualToIgnoringScale("1")
        assertThat(rpc.recordedMethods).containsExactly("eth_getBalance")
    }

    @Test
    fun `getNativeBalance keeps full 18-decimal precision above Double range`(): Unit = runBlocking {
        // 12345678901234567890123 wei — above 2^53, so a Double round-trip would corrupt it.
        rpc.stub("eth_getBalance", "0x29d42b64e76714244cb")
        val reader = makeReader(chainId = 1)

        val balance = reader.getNativeBalance(1, wallet)

        assertThat(balance).isEqualToIgnoringScale("12345.678901234567890123")
    }

    @Test
    fun `getERC20Balance issues eth_call and scales by token decimals`() = runBlocking {
        // 0xf4240 = 1_000_000 (1.00 USDC at 6 decimals), padded to a 32-byte uint256.
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240")
        val reader = makeReader(chainId = 1)

        val balance = reader.getERC20Balance(1, usdc, wallet, decimals = 6)

        assertThat(balance).isEqualToIgnoringScale("1")
    }

    // ---------- rich getBalances: parallel-fallback path ----------

    @Test
    fun `getBalances on a non-Multicall3 chain fans out one balanceOf per token`() = runBlocking {
        // Use a chain not in CANONICALLY_DEPLOYED_CHAIN_IDS so the parallel fallback runs.
        val chainId = 43113 // Avalanche Fuji testnet
        rpc.stub("eth_getBalance", "0x0") // native = 0
        // 0xf4240 = 1_000_000 → exact raw. Both tokens share the same stubbed eth_call
        // response (MockRpcServer dispatches by method, not by request body).
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val balances = reader.getBalances(
            chainId = chainId,
            walletAddress = wallet,
            tokens = listOf(
                TokenInfo(chainId, usdc, "USDC", 6),
                TokenInfo(chainId, dai, "DAI", 6)
            )
        )

        assertThat(balances).hasSize(3)
        val native = balances.single { it.token is Token.Native }
        assertThat(native.rawAmount).isEqualTo(BigInteger.ZERO)
        assertThat(native.decimals).isEqualTo(18)

        val usdcBalance = balances.single { it.token == Token.Contract(usdc) }
        assertThat(usdcBalance.rawAmount).isEqualTo(BigInteger("1000000"))
        assertThat(usdcBalance.decimals).isEqualTo(6)
        assertThat(usdcBalance.symbol).isEqualTo("USDC")
        assertThat(usdcBalance.formatted).isEqualTo("1")

        assertThat(balances.single { it.token == Token.Contract(dai) }.rawAmount)
            .isEqualTo(BigInteger("1000000"))
    }

    @Test
    fun `getBalances surfaces native success but omits per-token failures`() = runBlocking {
        // Native call works; the eth_call shared by both tokens returns an error.
        val chainId = 43113
        rpc.stub("eth_getBalance", "0xde0b6b3a7640000") // 1 ETH
        rpc.stubNetworkFailure("eth_call")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val balances = reader.getBalances(
            chainId = chainId,
            walletAddress = wallet,
            tokens = listOf(TokenInfo(chainId, usdc, "USDC", 6))
        )

        assertThat(balances).hasSize(1)
        assertThat(balances.single().token).isEqualTo(Token.Native)
        assertThat(balances.single().rawAmount).isEqualTo(BigInteger("1000000000000000000"))
    }

    @Test
    fun `getBalances drops a malformed token address instead of failing the batch`() = runBlocking {
        val chainId = 43113
        rpc.stub("eth_getBalance", "0x0")
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val balances = reader.getBalances(
            chainId = chainId,
            walletAddress = wallet,
            tokens = listOf(
                TokenInfo(chainId, "0x7f5c764c", "BAD", 6), // truncated address
                TokenInfo(chainId, usdc, "USDC", 6)
            )
        )

        // Native + USDC; the malformed entry loses only its own balance.
        assertThat(balances).hasSize(2)
        assertThat(balances.single { it.token is Token.Contract }.token)
            .isEqualTo(Token.Contract(usdc))
    }

    @Test
    fun `getBalances on a Multicall3 chain excludes a malformed token address from the batch`() = runBlocking {
        // The response below carries exactly two results (native + USDC). It only decodes if
        // the malformed token never entered the batch: the reader demands one result per
        // submitted call, so a three-call batch against this payload would throw.
        fun slot(v: String) = "0".repeat(64 - v.length) + v
        rpc.stub(
            "eth_call",
            "0x" +
                slot("20") +              // outer offset
                slot("2") +               // count = 2
                slot("40") +              // offset to tuple 0
                slot("c0") +              // offset to tuple 1 (0x40 + 0x80 tuple size)
                slot("1") +               // t0 success
                slot("40") +              // t0 returnData offset
                slot("20") +              // t0 returnData length
                slot("de0b6b3a7640000") + // t0 = 1 ETH in wei
                slot("1") +               // t1 success
                slot("40") +              // t1 returnData offset
                slot("20") +              // t1 returnData length
                slot("f4240")             // t1 = 1_000_000 (1 USDC at 6 decimals)
        )
        val reader = makeReader(chainId = 1)

        val balances = reader.getBalances(
            chainId = 1,
            walletAddress = wallet,
            tokens = listOf(
                TokenInfo(1, "0x7f5c764c", "BAD", 6),
                TokenInfo(1, usdc, "USDC", 6)
            )
        )

        assertThat(balances).hasSize(2)
        assertThat(balances.single { it.token is Token.Native }.rawAmount)
            .isEqualTo(BigInteger("1000000000000000000"))
        assertThat(balances.single { it.token == Token.Contract(usdc) }.rawAmount)
            .isEqualTo(BigInteger("1000000"))
    }

    @Test
    fun `getBalances treats native eth_getBalance failure as fatal`() {
        val chainId = 43113
        rpc.stubNetworkFailure("eth_getBalance")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val ex = runCatching {
            runBlocking {
                reader.getBalances(
                    chainId = chainId,
                    walletAddress = wallet,
                    tokens = listOf(TokenInfo(chainId, usdc, "USDC", 6))
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.NetworkError::class.java)
    }

    // ---------- rich getBalance (single) ----------

    @Test
    fun `getBalance native builds a Balance from eth_getBalance with registry metadata`() = runBlocking {
        rpc.stub("eth_getBalance", "0xde0b6b3a7640000") // 1 ETH
        val reader = makeReader(chainId = 1)

        val balance = reader.getBalance(1, wallet, Token.Native, tokenInfo = null)

        assertThat(balance.token).isEqualTo(Token.Native)
        assertThat(balance.rawAmount).isEqualTo(BigInteger("1000000000000000000"))
        assertThat(balance.symbol).isEqualTo("ETH")
        assertThat(balance.decimals).isEqualTo(18)
    }

    @Test
    fun `getBalance contract builds a Balance from balanceOf with supplied metadata`() = runBlocking {
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240") // 1_000_000
        val reader = makeReader(chainId = 1)

        val balance = reader.getBalance(
            chainId = 1,
            walletAddress = wallet,
            token = Token.Contract(usdc),
            tokenInfo = TokenInfo(1, usdc, "USDC", 6, "USD Coin")
        )

        assertThat(balance.token).isEqualTo(Token.Contract(usdc))
        assertThat(balance.rawAmount).isEqualTo(BigInteger("1000000"))
        assertThat(balance.decimals).isEqualTo(6)
        assertThat(balance.symbol).isEqualTo("USDC")
        assertThat(balance.name).isEqualTo("USD Coin")
    }

    // ---------- metadata reads ----------

    @Test
    fun `getDecimals parses the eth_call uint into an Int`() = runBlocking {
        rpc.stub("eth_call", "0x" + "6".padStart(64, '0')) // 0x...06 = 6
        val reader = makeReader(chainId = 1)

        assertThat(reader.getDecimals(1, usdc)).isEqualTo(6)
    }

    @Test
    fun `getSymbol decodes an ABI-encoded string`() = runBlocking {
        // ABI string: [offset=0x20][length=4]["USDC" right-padded]
        val symbolHex = "0x" +
            "20".padStart(64, '0') +
            "4".padStart(64, '0') +
            "55534443".padEnd(64, '0') // "USDC"
        rpc.stub("eth_call", symbolHex)
        val reader = makeReader(chainId = 1)

        assertThat(reader.getSymbol(1, usdc)).isEqualTo("USDC")
    }

    // ---------- guards ----------

    @Test
    fun `getNativeBalance throws InvalidConfig when chain has no rpc configured`() {
        val reader = EvmChainReader(rpcEndpoints = emptyMap())
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { reader.getNativeBalance(1, wallet) }
        }
    }

    @Test
    fun `getNativeBalance throws InternalError on syntactically invalid wallet address`() {
        val reader = makeReader(chainId = 1)
        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getNativeBalance(1, walletAddress = "not-an-address") }
        }
    }
}
