package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.NativeCurrency
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.math.BigDecimal

class PrivyWalletProviderTest {

    @Test
    fun `sendNativeToken builds an eth value transfer with the chainId hex and no data`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val txJson = slot<String>()
        coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xHASH"

        val wallet = provider(manager)
        val hash = wallet.sendNativeToken(chainId = 1, toAddress = TO, amountInEth = BigDecimal.ONE)

        assertThat(hash).isEqualTo("0xHASH")
        val tx = JSONObject(txJson.captured)
        assertThat(tx.getString("from")).isEqualTo(WALLET)
        assertThat(tx.getString("to")).isEqualTo(TO)
        // 1 ETH = 1e18 wei = 0xde0b6b3a7640000
        assertThat(tx.getString("value")).isEqualTo("0xde0b6b3a7640000")
        assertThat(tx.getString("chainId")).isEqualTo("0x1")
        assertThat(tx.has("data")).isFalse()
    }

    @Test
    fun `sendToken encodes an ERC-20 transfer to the contract with zero value`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val txJson = slot<String>()
        coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xHASH"

        val wallet = provider(manager)
        wallet.sendToken(
            chainId = 1,
            contractAddress = CONTRACT,
            toAddress = TO,
            amount = BigDecimal.ONE,
            decimals = 6,
        )

        val tx = JSONObject(txJson.captured)
        assertThat(tx.getString("to")).isEqualTo(CONTRACT)
        assertThat(tx.getString("value")).isEqualTo("0x0")
        // transfer(address,uint256) selector.
        assertThat(tx.getString("data")).startsWith("0xa9059cbb")
        // recipient in the first arg slot, then 1 * 10^6 = 0xf4240 base units.
        assertThat(tx.getString("data")).contains(TO.removePrefix("0x").lowercase())
        assertThat(tx.getString("data")).endsWith("f4240")
    }

    @Test
    fun `sendTransaction defaults an empty value to 0x0 and drops empty data`() = runBlocking {
        val manager = mockk<PrivyManager>()
        val txJson = slot<String>()
        coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xHASH"

        val wallet = provider(manager)
        wallet.sendTransaction(chainId = 1, from = WALLET, to = TO, data = "0x", value = "")

        val tx = JSONObject(txJson.captured)
        assertThat(tx.getString("value")).isEqualTo("0x0")
        assertThat(tx.has("data")).isFalse()
    }

    @Test
    fun `sendTransaction simulates via eth_call before broadcasting`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.sendTransaction(WALLET, RPC, any()) } returns "0xHASH"
        val rpc = mockk<PrivyRpcClient>()
        val simulationParams = slot<List<Any>>()
        coEvery { rpc.callForHexResult(RPC, "eth_call", capture(simulationParams)) } returns "0x"

        val wallet = provider(manager, rpc)
        val hash = wallet.sendTransaction(chainId = 1, from = WALLET, to = TO, data = "0xabcdef", value = "0x1")

        assertThat(hash).isEqualTo("0xHASH")
        // The simulation call carries the same transaction object, against the latest block.
        val callObject = simulationParams.captured.first() as Map<*, *>
        assertThat(callObject["from"]).isEqualTo(WALLET)
        assertThat(callObject["to"]).isEqualTo(TO)
        assertThat(callObject["data"]).isEqualTo("0xabcdef")
        assertThat(callObject["value"]).isEqualTo("0x1")
        assertThat(simulationParams.captured.last()).isEqualTo("latest")
        coVerify(exactly = 1) { manager.sendTransaction(WALLET, RPC, any()) }
    }

    @Test
    fun `a failing eth_call simulation surfaces as TransactionSimulationFailed without broadcasting`() = runBlocking {
        val manager = mockk<PrivyManager>()
        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_call", any()) } throws RuntimeException("execution reverted")

        val wallet = provider(manager, rpc)
        val error = runCatching {
            wallet.sendTransaction(chainId = 1, from = WALLET, to = TO, data = "0x", value = "0x1")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        coVerify(exactly = 0) { manager.sendTransaction(any(), any(), any()) }
    }

    @Test
    fun `estimateTransactionFee multiplies gas limit by gas price`() = runBlocking {
        val manager = mockk<PrivyManager>()
        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_estimateGas", any()) } returns "0x5208" // 21000
        coEvery { rpc.callForHexResult(RPC, "eth_gasPrice", any()) } returns "0x3b9aca00" // 1 gwei

        val wallet = PrivyWalletProvider(manager, mapOf(1 to RPC), mockk(), rpcClient = rpc)
        val fee = wallet.estimateTransactionFee(1, WALLET, TO, "0x", "0x0")

        // 21000 * 1e9 wei = 2.1e13 wei = 0.000021 ETH
        assertThat(fee).isWithin(1e-12).of(0.000021)
    }

    @Test
    fun `getBalances keeps native and healthy tokens when one token read fails`() = runBlocking<Unit> {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrency(1) } returns NativeCurrency("ETH", "Ether", 18)
        coEvery { tokenStore.registeredTokens(1) } returns listOf(
            TokenInfo(chainId = 1, address = "0xUSDC", symbol = "USDC", decimals = 6, name = "USD Coin"),
            TokenInfo(chainId = 1, address = "0xBAD", symbol = "BAD", decimals = 18, name = "Bad"),
        )
        coEvery { tokenStore.tokenInfo(1, "0xUSDC") } returns TokenInfo(1, "0xUSDC", "USDC", 6, "USD Coin")
        coEvery { tokenStore.tokenInfo(1, "0xBAD") } returns TokenInfo(1, "0xBAD", "BAD", 18, "Bad")

        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_getBalance", any()) } returns "0x1"
        // eth_call routed by the "to" address inside the call object; the bad token throws.
        coEvery {
            rpc.callForHexResult(RPC, "eth_call", match<List<Any>> { callTarget(it) == "0xUSDC" })
        } returns "0x5"
        coEvery {
            rpc.callForHexResult(RPC, "eth_call", match<List<Any>> { callTarget(it) == "0xBAD" })
        } throws RuntimeException("rpc down for this token")

        val wallet = PrivyWalletProvider(manager, mapOf(1 to RPC), tokenStore, rpcClient = rpc)
        val balances = wallet.getBalances(1)

        // Native + USDC survive; the failing token is dropped, not fatal.
        assertThat(balances.map { it.token }).containsExactly(Token.Native, Token.Contract("0xUSDC"))
    }

    private fun provider(manager: PrivyManager, rpc: PrivyRpcClient = simulationPassingRpc()) =
        PrivyWalletProvider(manager, mapOf(1 to RPC), mockk<TokenMetadataStore>(), rpcClient = rpc)

    /** An RPC client whose pre-broadcast `eth_call` simulation always succeeds. */
    private fun simulationPassingRpc(): PrivyRpcClient {
        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_call", any()) } returns "0x"
        return rpc
    }

    /** Pulls the `to` field out of an `eth_call` params list (first param is the call object). */
    private fun callTarget(params: List<Any>): String? =
        (params.firstOrNull() as? Map<*, *>)?.get("to") as? String

    private companion object {
        const val RPC = "https://rpc.example/1"
        const val WALLET = "0x000000000000000000000000000000000000dEaD"
        const val TO = "0x1111111111111111111111111111111111111111"
        const val CONTRACT = "0x2222222222222222222222222222222222222222"
    }
}
