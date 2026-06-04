package com.rain.sdk.internal.provider

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.solana.SolanaTransactionBuilder
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Solana behaviour of [TurnkeyWalletProvider]: chain-aware address resolution, balance reads
 * routed to the Solana reader, and the native SOL send path through Turnkey's
 * `sol_send_transaction`.
 */
class TurnkeySolanaProviderTest {

    private lateinit var rpc: MockRpcServer
    private val devnet = RainChain.SOLANA_DEVNET
    private val devnetCaip2 = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"

    @Before
    fun setUp() {
        // Skips on pre-JDK-24 JVMs (Turnkey AAR is Java-24 bytecode) before touching `rpc`.
        assumeJdk24()
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        if (::rpc.isInitialized) rpc.shutdown()
    }

    private fun makeProvider(
        client: MockTurnkeyClient = MockTurnkeyClient(),
        evmReader: MockChainReader = MockChainReader(),
        solanaReader: MockChainReader = MockChainReader()
    ): TurnkeyWalletProvider {
        val turnkey = MockTurnkey(
            wallets = listOf(MockTurnkey.walletWithEthAndSolana()),
            turnkeyClient = client
        )
        return TurnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(devnet to rpc.urlFor(devnet)),
            httpClient = OkHttpClient(),
            chainReader = evmReader,
            solanaChainReader = solanaReader
        )
    }

    @Test
    fun `getAddress is chain-aware - solana chain returns the solana account`() = runBlocking {
        val provider = makeProvider()

        assertThat(provider.getAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(provider.getAddress(devnet)).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
    }

    @Test
    fun `getBalance native on solana routes to the solana reader, not the evm reader`() = runBlocking {
        val solanaReader = MockChainReader(
            balance = Balance(Token.Native, devnet, BigInteger.valueOf(2_500_000_000L), 9, "SOL", "Solana")
        )
        val evmReader = MockChainReader()
        val provider = makeProvider(evmReader = evmReader, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Native)

        assertThat(balance.symbol).isEqualTo("SOL")
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)
        assertThat(solanaReader.balanceCalls).hasSize(1)
        assertThat(solanaReader.balanceCalls.single().walletAddress)
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(evmReader.balanceCalls).isEmpty()
    }

    @Test
    fun `sendNativeToken on solana submits an unsigned transfer and returns the chain signature`() = runBlocking {
        val blockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS // a valid 32-byte base58 stand-in
        val signature = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put("value", JSONObject().put("blockhash", blockhash).put("lastValidBlockHeight", 150))
        )
        rpc.stubObject(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", signature).put("slot", 150))
        )

        val client = MockTurnkeyClient() // default getSendTransactionStatus = BROADCASTED
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, 0.5)

        assertThat(result).isEqualTo(signature)
        assertThat(client.ethSendTransactionCalls).isEmpty()
        assertThat(client.solSendTransactionCalls).hasSize(1)
        val body = client.solSendTransactionCalls.single()
        assertThat(body.signWith).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(body.caip2).isEqualTo(devnetCaip2)
        assertThat(body.recentBlockhash).isEqualTo(blockhash)
        assertThat(body.sponsor).isEqualTo(false)
        assertThat(body.unsignedTransaction).isNotEmpty()
    }

    @Test
    fun `getTransactions on solana reads sol_send activities and decodes recipient and amount`(): Unit = runBlocking {
        val unsignedTx = SolanaTransactionBuilder.buildTransferHex(
            fromAddress = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            toAddress = MockTurnkey.DEFAULT_SOLANA_RECIPIENT,
            lamports = 1_000_000_000L, // 1 SOL
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS // valid 32-byte base58 stand-in
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-1",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-1"
                )
            )
        )
        val provider = makeProvider(client = client)

        val result = provider.getTransactions(devnet, limit = 10, order = RainTransactionOrder.DESC)

        assertThat(result.transactions).hasSize(1)
        val tx = result.transactions.single()
        assertThat(tx.hash).isEqualTo("sol-status-1") // Turnkey status id, not an on-chain signature
        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(tx.value).isEqualTo("1")
        assertThat(tx.symbol).isEqualTo("SOL")
        assertThat(tx.chainId).isEqualTo("103")
        // History is sourced from the SOL_SEND activity filter, not chain RPC.
        assertThat(client.getActivitiesCalls.single().filterByType)
            .containsExactly(com.turnkey.types.V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION)
    }

    @Test
    fun `getTransactions on solana ignores activities from a different cluster`(): Unit = runBlocking {
        val unsignedTx = SolanaTransactionBuilder.buildTransferHex(
            MockTurnkey.DEFAULT_SOLANA_ADDRESS, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, 1L,
            MockTurnkey.DEFAULT_SOLANA_ADDRESS
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "mainnet-act",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", // mainnet, not devnet
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "x"
                )
            )
        )
        val provider = makeProvider(client = client)

        assertThat(provider.getTransactions(devnet, limit = 10).transactions).isEmpty()
    }

    @Test(expected = com.rain.sdk.internal.error.RainError.InvalidConfig::class)
    fun `sendToken (SPL) is unsupported on solana`() = runBlocking {
        makeProvider().sendToken(devnet, "anyMint", MockTurnkey.DEFAULT_SOLANA_RECIPIENT, 1.0, 6)
        Unit
    }
}
