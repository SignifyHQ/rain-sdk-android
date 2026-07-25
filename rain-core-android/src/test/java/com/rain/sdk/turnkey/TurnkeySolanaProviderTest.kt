package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.solana.SolanaAddresses
import com.rain.sdk.internal.solana.SolanaInstructions
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.solana.SolanaTransactionBuilder
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.turnkey.types.V1AssetBalance
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Solana behaviour of [TurnkeyWalletProvider]: chain-aware address resolution, balance reads
 * sourced from Turnkey (with an RPC fallback for native SOL), and the native SOL send path
 * through Turnkey's `sol_send_transaction`.
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

    /**
     * @param solanaReader pass `null` to exercise the real [SolanaChainReader] against the mock
     *        RPC server — needed by the balance tests, where SPL holdings are discovered on chain.
     */
    private fun makeProvider(
        client: MockTurnkeyClient = MockTurnkeyClient(),
        evmReader: MockChainReader = MockChainReader(),
        solanaReader: MockChainReader? = MockChainReader()
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

        assertThat(provider.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(provider.getWalletAddress(devnet)).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
    }

    @Test
    fun `getBalance native on solana reads from Turnkey with the devnet caip2`() = runBlocking {
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "2500000000", // 2.5 SOL in lamports
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L,
                    display = null,
                    name = "Solana",
                    symbol = "SOL"
                )
            )
        )
        val solanaReader = MockChainReader()
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Native)

        assertThat(balance.symbol).isEqualTo("SOL")
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)
        assertThat(client.walletAddressBalanceCalls).hasSize(1)
        assertThat(client.walletAddressBalanceCalls.single().caip2).isEqualTo(devnetCaip2)
        assertThat(client.walletAddressBalanceCalls.single().address)
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        // The RPC reader is untouched when Turnkey succeeds.
        assertThat(solanaReader.balanceCalls).isEmpty()
    }

    @Test
    fun `getBalance native on solana falls back to the RPC reader when Turnkey fails`() = runBlocking {
        val client = MockTurnkeyClient().apply {
            walletAddressBalancesError = RuntimeException("turnkey unavailable")
        }
        val solanaReader = MockChainReader(
            balance = Balance(Token.Native, devnet, BigInteger.valueOf(1_000_000_000L), 9, "SOL", "Solana")
        )
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Native)

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(1.0)
        assertThat(solanaReader.balanceCalls).hasSize(1)
        assertThat(solanaReader.balanceCalls.single().walletAddress)
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
    }

    @Test
    fun `getBalance native on solana shows zero when Turnkey omits the asset`() = runBlocking {
        // Turnkey returns only non-zero balances; an empty list means a 0-SOL wallet, not a failure.
        val client = MockTurnkeyClient(mockBalances = emptyList())
        val solanaReader = MockChainReader()
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Native)

        assertThat(balance.symbol).isEqualTo("SOL")
        assertThat(balance.rawAmount).isEqualTo(BigInteger.ZERO)
        assertThat(solanaReader.balanceCalls).isEmpty()
    }

    @Test
    fun `getBalance for an spl mint Turnkey lists uses Turnkey's amount and metadata`() = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "100500000",
                    caip19 = "$devnetCaip2/token:$mint",
                    decimals = 6L, display = null, name = "USD Coin", symbol = "USDC"
                )
            )
        )
        val solanaReader = MockChainReader()
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Contract(mint))

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(100_500_000L))
        assertThat(balance.decimals).isEqualTo(6)
        assertThat(balance.symbol).isEqualTo("USDC")
        assertThat(balance.name).isEqualTo("USD Coin")
        // The node is never consulted when Turnkey lists the mint.
        assertThat(solanaReader.balanceCalls).isEmpty()
    }

    @Test
    fun `getBalance for an spl mint Turnkey does not list falls back to the node`() = runBlocking {
        // Turnkey omits zero balances, and on a cluster it does not index every mint looks like
        // a zero — so a missing entry is re-read from the node.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient(mockBalances = emptyList())
        val solanaReader = MockChainReader(
            balance = Balance(Token.Contract(mint), devnet, BigInteger.valueOf(2_500_000L), 6)
        )
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Contract(mint))

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(2_500_000L))
        assertThat(solanaReader.balanceCalls).hasSize(1)
    }

    @Test
    fun `getBalance for an spl mint falls back to the node when Turnkey fails`() = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient().apply {
            walletAddressBalancesError = RuntimeException("turnkey unavailable")
        }
        val solanaReader = MockChainReader(
            balance = Balance(Token.Contract(mint), devnet, BigInteger.valueOf(2_500_000L), 6)
        )
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Contract(mint))

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(2_500_000L))
        assertThat(solanaReader.balanceCalls).hasSize(1)
    }

    @Test
    fun `getBalances on solana uses Turnkey's SPL list when it indexes the cluster`() = runBlocking {
        val usdcMint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT // valid 32-byte base58 stand-in for a mint
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "2500000000", // 2.5 SOL
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L, display = null, name = "Solana", symbol = "SOL"
                ),
                V1AssetBalance(
                    balance = "100500000",
                    caip19 = "$devnetCaip2/token:$usdcMint",
                    decimals = 6L, display = null, name = "USD Coin", symbol = "USDC"
                )
            )
        )
        // No RPC stubs installed: the node is never consulted when Turnkey lists SPL assets.
        val provider = makeProvider(client = client, solanaReader = null)

        val balances = provider.getBalances(devnet)

        assertThat(balances).hasSize(2)
        val sol = balances.single { it.token is Token.Native }
        assertThat(sol.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)

        val usdc = balances.single { it.token == Token.Contract(usdcMint) }
        // Amount and naming both from Turnkey's asset entry.
        assertThat(usdc.rawAmount).isEqualTo(BigInteger.valueOf(100_500_000L))
        assertThat(usdc.symbol).isEqualTo("USDC")
        assertThat(usdc.name).isEqualTo("USD Coin")
        assertThat(client.walletAddressBalanceCalls.single().caip2).isEqualTo(devnetCaip2)
    }

    @Test
    fun `getBalances on solana still lists SPL tokens Turnkey does not index`() = runBlocking {
        // Devnet mints are absent from Turnkey's asset list. Because it also omits zero balances,
        // an SPL-less answer is treated as "not indexed" and the list is read from the node.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "5000000000",
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L, display = null, name = "Solana", symbol = "SOL"
                )
            )
        )
        rpc.stubObject("getBalance", balanceResult(5_000_000_000L))
        stubDiscoveredTokenAccounts(mint = mint, amount = "20000000", decimals = 6)
        val provider = makeProvider(client = client, solanaReader = null)

        val balances = provider.getBalances(devnet)

        val token = balances.single { it.token == Token.Contract(mint) }
        assertThat(token.decimalAmount.toDouble()).isWithin(1e-6).of(20.0)
        assertThat(token.decimals).isEqualTo(6)
        // Unindexed by Turnkey, so it has no symbol — but the balance is still correct.
        assertThat(token.symbol).isNull()
    }

    @Test
    fun `getBalances on solana names a mint from host-registered tokens`(): Unit = runBlocking {
        // Neither the chain nor Turnkey can name an SPL mint on devnet, so `registerTokens` is
        // how a caller labels it — otherwise the balance shows only a mint address.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val store = TokenMetadataStore(MockChainReader())
        store.register(listOf(TokenInfo(devnet, mint, "USDC", 6, "USD Coin")))

        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "5000000000",
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L, display = null, name = "Solana", symbol = "SOL"
                )
            )
        )
        rpc.stubObject("getBalance", balanceResult(5_000_000_000L))
        stubDiscoveredTokenAccounts(mint = mint, amount = "20000000", decimals = 6)
        val provider = TurnkeyWalletProvider(
            turnkey = MockTurnkey(
                wallets = listOf(MockTurnkey.walletWithEthAndSolana()),
                turnkeyClient = client
            ),
            rpcEndpoints = mapOf(devnet to rpc.urlFor(devnet)),
            httpClient = OkHttpClient(),
            chainReader = MockChainReader(),
            solanaChainReader = null,
            tokenStore = store
        )

        val token = provider.getBalances(devnet).single { it.token == Token.Contract(mint) }

        assertThat(token.symbol).isEqualTo("USDC")
        assertThat(token.name).isEqualTo("USD Coin")
        // The amount still comes from the chain, not from the registration.
        assertThat(token.decimalAmount.toDouble()).isWithin(1e-6).of(20.0)
    }

    @Test
    fun `getBalances on solana keeps the native balance when token discovery fails`() = runBlocking {
        // Turnkey lists no SPL assets, so the list is read from the node; discovery is
        // per-program tolerant, so a failed enumeration still yields the native balance.
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "5000000000",
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L, display = null, name = "Solana", symbol = "SOL"
                )
            )
        )
        rpc.stubObject("getBalance", balanceResult(5_000_000_000L))
        // getTokenAccountsByOwner left unstubbed -> the RPC call fails.
        val provider = makeProvider(client = client, solanaReader = null)

        val balances = provider.getBalances(devnet)

        assertThat(balances).hasSize(1)
        assertThat(balances.single().decimalAmount.toDouble()).isWithin(1e-9).of(5.0)
    }

    private fun balanceResult(lamports: Long): JSONObject =
        JSONObject()
            .put("context", JSONObject().put("slot", 1))
            .put("value", lamports)

    /** Stubs `getTokenAccountsByOwner` so the real Solana reader discovers one holding. */
    private fun stubDiscoveredTokenAccounts(mint: String, amount: String, decimals: Int) {
        val entry = JSONObject()
            .put("pubkey", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
            .put(
                "account",
                JSONObject().put("owner", SolanaPrograms.TOKEN_ADDRESS).put(
                    "data",
                    JSONObject().put("program", "spl-token").put(
                        "parsed",
                        JSONObject().put("type", "account").put(
                            "info",
                            JSONObject()
                                .put("mint", mint)
                                .put("owner", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                                .put(
                                    "tokenAmount",
                                    JSONObject().put("amount", amount).put("decimals", decimals)
                                )
                        )
                    )
                )
            )
        rpc.stubObjectWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_ADDRESS,
            JSONObject().put("context", JSONObject().put("slot", 1))
                .put("value", JSONArray().put(entry))
        )
        rpc.stubObjectWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_2022_ADDRESS,
            JSONObject().put("context", JSONObject().put("slot", 1)).put("value", JSONArray())
        )
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

        // Included with no hash in the status -> we recover the signature from chain (RPC).
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture(txHash = null, txStatus = "TX_STATUS_INCLUDED")
            )
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

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
    fun `sendNativeToken on solana returns the signature from the Turnkey status response`() = runBlocking {
        val turnkeySignature = "TurnkeyProvidedSolanaSig111111111111111111"
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put("value", JSONObject().put("blockhash", MockTurnkey.DEFAULT_SOLANA_ADDRESS).put("lastValidBlockHeight", 150))
        )
        // Turnkey SDK 2.0 reports the signature in solana.signature once Included -> returned
        // directly, without the RPC fallback.
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture(solanaSignature = turnkeySignature, txStatus = "TX_STATUS_INCLUDED")
            )
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(turnkeySignature)
    }

    @Test
    fun `sendNativeToken on solana rejects sub-lamport precision before contacting anything`() {
        val client = MockTurnkeyClient()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                makeProvider(client = client)
                    .sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.0000000015"))
            }
        }
        assertThat(client.solSendTransactionCalls).isEmpty()
    }

    @Test
    fun `sendNativeToken on solana rejects a negative amount as a typed error`() {
        val client = MockTurnkeyClient()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                makeProvider(client = client)
                    .sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("-1"))
            }
        }
        assertThat(client.solSendTransactionCalls).isEmpty()
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
        assertThat(tx.chainId).isEqualTo(devnet.toString())
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

    @Test
    fun `getTransactions on solana decodes an spl transfer and resolves the recipient wallet`(): Unit = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val recipientWallet = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val destinationAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(recipientWallet), Base58.decode(mint), SolanaPrograms.TOKEN
            )
        )
        val unsignedTx = SolanaTransactionBuilder.buildUnsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                SolanaInstructions.transferChecked(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    source = SolanaAddresses.associatedTokenAddress(
                        Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                        Base58.decode(mint),
                        SolanaPrograms.TOKEN
                    ),
                    mint = Base58.decode(mint),
                    destination = Base58.decode(destinationAta),
                    owner = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    amount = java.math.BigInteger.valueOf(1_500_000L),
                    decimals = 6
                )
            )
        )
        // The recipient's token account resolves back to the wallet that owns it.
        rpc.stubObjectFor(
            "getAccountInfo",
            destinationAta,
            JSONObject().put("context", JSONObject().put("slot", 1)).put(
                "value",
                JSONObject().put("owner", SolanaPrograms.TOKEN_ADDRESS).put(
                    "data",
                    JSONObject().put("program", "spl-token").put(
                        "parsed",
                        JSONObject().put("type", "account").put(
                            "info",
                            JSONObject().put("mint", mint).put("owner", recipientWallet).put(
                                "tokenAmount",
                                JSONObject().put("amount", "1500000").put("decimals", 6)
                            )
                        )
                    )
                )
            )
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-spl",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-spl"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).transactions.single()

        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        // The recipient is reported as their wallet, not the token account the transfer targets.
        assertThat(tx.to).isEqualTo(recipientWallet)
        assertThat(tx.value).isEqualTo("1.5")
        assertThat(tx.tokenAddress).isEqualTo(mint)
        assertThat(tx.symbol).isNull()
        assertThat(tx.metadata?.get("destinationTokenAccount")).isEqualTo(destinationAta)
    }

    @Test
    fun `getTransactions on solana falls back to the token account when the owner is unreadable`(): Unit = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val destinationAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(mint), Base58.decode(mint), SolanaPrograms.TOKEN
            )
        )
        val unsignedTx = SolanaTransactionBuilder.buildUnsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                SolanaInstructions.transferChecked(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    source = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    mint = Base58.decode(mint),
                    destination = Base58.decode(destinationAta),
                    owner = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    amount = java.math.BigInteger.valueOf(250L),
                    decimals = 2
                )
            )
        )
        // getAccountInfo left unstubbed -> the lookup fails.
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-spl-2",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-spl-2"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).transactions.single()

        // A real, lookup-able address beats reporting nothing.
        assertThat(tx.to).isEqualTo(destinationAta)
        assertThat(tx.value).isEqualTo("2.5")
    }

    @Test
    fun `getTransactions on solana recovers the recipient wallet from the create-account instruction`(): Unit = runBlocking {
        // A first-time transfer carries the recipient's wallet in its own ATA-creation
        // instruction, so no node read is needed to resolve it.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val recipientWallet = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val destinationAta = SolanaAddresses.associatedTokenAddress(
            Base58.decode(recipientWallet), Base58.decode(mint), SolanaPrograms.TOKEN
        )
        val unsignedTx = SolanaTransactionBuilder.buildUnsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                SolanaInstructions.createAssociatedTokenAccountIdempotent(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    payer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    associatedAccount = destinationAta,
                    owner = Base58.decode(recipientWallet),
                    mint = Base58.decode(mint)
                ),
                SolanaInstructions.transferChecked(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    source = SolanaAddresses.associatedTokenAddress(
                        Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                        Base58.decode(mint),
                        SolanaPrograms.TOKEN
                    ),
                    mint = Base58.decode(mint),
                    destination = destinationAta,
                    owner = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    amount = java.math.BigInteger.valueOf(1_500_000L),
                    decimals = 6
                )
            )
        )
        // getAccountInfo deliberately unstubbed: the wallet must come from the blob itself.
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-spl-create",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-spl-create"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).transactions.single()

        assertThat(tx.to).isEqualTo(recipientWallet)
        assertThat(tx.value).isEqualTo("1.5")
        assertThat(tx.tokenAddress).isEqualTo(mint)
    }

    @Test
    fun `getTransactions on solana decodes a bare transfer built by other tooling`(): Unit = runBlocking {
        // TokenInstruction::Transfer (tag 3): @solana/spl-token's default, never produced by this
        // SDK but present in the same Turnkey activity feed when other tooling drives the wallet.
        // It carries neither mint nor decimals.
        val source = ByteArray(32) { (it + 11).toByte() }
        val destination = ByteArray(32) { (it + 55).toByte() }
        val data = ByteArray(9)
        data[0] = 3
        var amount = 250L
        for (i in 0 until 8) {
            data[1 + i] = (amount and 0xFF).toByte()
            amount = amount ushr 8
        }
        val unsignedTx = SolanaTransactionBuilder.buildUnsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                com.rain.sdk.internal.solana.Instruction(
                    programId = SolanaPrograms.TOKEN,
                    accounts = listOf(
                        com.rain.sdk.internal.solana.AccountMeta.writable(source),
                        com.rain.sdk.internal.solana.AccountMeta.writable(destination),
                        com.rain.sdk.internal.solana.AccountMeta.signer(
                            Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                        )
                    ),
                    data = data
                )
            )
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-bare",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-bare"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).transactions.single()

        // The row lists rather than showing as undecodable; the destination token account is the
        // best recoverable recipient and the unscaled amount stays in the metadata.
        assertThat(tx.hash).isEqualTo("sol-status-bare")
        assertThat(tx.to).isEqualTo(Base58.encode(destination))
        assertThat(tx.value).isNull() // no decimals on the wire and no mint to read them from
        assertThat(tx.tokenAddress).isNull()
        assertThat(tx.metadata?.get("rawAmount")).isEqualTo("250")
    }

    // ---------- SPL transfers ----------

    @Test
    fun `sendToken on solana transfers between existing token accounts`(): Unit = runBlocking {
        val fixture = splFixture(recipientAccountExists = true)
        val client = includedStatusClient()
        val provider = makeProvider(client = client)

        val result = provider.sendToken(devnet, mint, recipient, BigDecimal("1.5"), decimals = 18)

        assertThat(result).isEqualTo(SIGNATURE)
        val body = client.solSendTransactionCalls.single()
        assertThat(body.caip2).isEqualTo(devnetCaip2)
        assertThat(body.signWith).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)

        // One instruction (the transfer) — no account creation, since the recipient has one.
        val instructions = decodeInstructions(body.unsignedTransaction)
        assertThat(instructions).hasSize(1)
        // transfer_checked: tag 12, 1.5 at the mint's 6 decimals = 1_500_000 base units, and the
        // decimals byte comes from the mint, NOT the 18 the caller passed in.
        assertThat(instructions.single().first).isEqualTo(SolanaPrograms.TOKEN_ADDRESS)
        assertThat(instructions.single().second)
            .isEqualTo(byteArrayOf(12, 0x60, 0xE3.toByte(), 0x16, 0, 0, 0, 0, 0, 6))
        // The transfer is dry-run before it is handed to Turnkey.
        assertThat(rpc.recordedMethods).contains("simulateTransaction")
        assertThat(fixture).isTrue()
    }

    @Test
    fun `sendToken on solana creates the recipient token account when missing`(): Unit = runBlocking {
        splFixture(recipientAccountExists = false)
        val client = includedStatusClient()
        val provider = makeProvider(client = client)

        provider.sendToken(devnet, mint, recipient, BigDecimal("1.5"), decimals = 6)

        val instructions = decodeInstructions(client.solSendTransactionCalls.single().unsignedTransaction)
        assertThat(instructions).hasSize(2)
        // Create-idempotent (discriminator 1) against the associated-token program, ahead of the
        // transfer — the sender pays the new account's rent.
        assertThat(instructions[0].first).isEqualTo(SolanaPrograms.ASSOCIATED_TOKEN_ADDRESS)
        assertThat(instructions[0].second).isEqualTo(byteArrayOf(1))
        assertThat(instructions[1].first).isEqualTo(SolanaPrograms.TOKEN_ADDRESS)
    }

    @Test
    fun `sendToken on solana addresses token-2022 mints to their own program`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, tokenProgram = SolanaPrograms.TOKEN_2022_ADDRESS)
        val client = includedStatusClient()
        val provider = makeProvider(client = client)

        provider.sendToken(devnet, mint, recipient, BigDecimal("1.5"), decimals = 6)

        val instructions = decodeInstructions(client.solSendTransactionCalls.single().unsignedTransaction)
        assertThat(instructions.single().first).isEqualTo(SolanaPrograms.TOKEN_2022_ADDRESS)
    }

    @Test
    fun `sendToken on solana rejects a mint that does not exist on this cluster`(): Unit = runBlocking {
        rpc.stubObjectFor("getAccountInfo", mint, accountEnvelope(JSONObject.NULL))

        val error = assertThrows(RainError.TokenNotFound::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        assertThat(error.token).isEqualTo(mint)
    }

    @Test
    fun `sendToken on solana rejects a wallet that holds none of the token`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, senderAccountExists = false)

        assertThrows(RainError.TokenAccountNotFound::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        Unit
    }

    @Test
    fun `sendToken on solana rejects an amount larger than the balance`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, senderBaseUnits = "1000000") // 1.0 at 6dp

        val error = assertThrows(RainError.InsufficientTokenBalance::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("2.5"), decimals = 6) }
        }
        assertThat(error.requested).isEqualTo("2.5")
        assertThat(error.available).isEqualTo("1")
    }

    @Test
    fun `sendToken on solana rejects an amount finer than the mint's decimals`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true)

        // The mint has 6 decimals; 7 would be silently truncated by a naive conversion.
        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1.0000001"), decimals = 6) }
        }
        Unit
    }

    @Test
    fun `sendToken on solana rejects a token account as the recipient`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true)
        // The recipient address itself resolves to a token account, not a wallet.
        rpc.stubObjectFor("getAccountInfo", recipient, accountEnvelope(tokenAccountValue("1")))

        assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        Unit
    }

    @Test
    fun `sendToken on solana rejects sending to the wallet itself`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true)

        assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking {
                makeProvider().sendToken(
                    devnet, mint, MockTurnkey.DEFAULT_SOLANA_ADDRESS, BigDecimal("1"), decimals = 6
                )
            }
        }
        Unit
    }

    @Test
    fun `sendToken on solana surfaces a failed simulation instead of broadcasting`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, simulationError = "InstructionError")
        val client = includedStatusClient()

        assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking { makeProvider(client = client).sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        // Nothing reached Turnkey.
        assertThat(client.solSendTransactionCalls).isEmpty()
    }

    @Test
    fun `sendToken on solana rejects a wallet that cannot cover the fee`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, lamports = 100L)

        assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        Unit
    }

    // ---------- EVM-only entry points ----------

    @Test
    fun `low-level EVM operations are rejected on solana chain ids`(): Unit = runBlocking {
        val provider = makeProvider()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { provider.sendTransaction(devnet, "from", "to", "0x", "0x0") }
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { provider.estimateTransactionFee(devnet, "from", "to", "0x", "0x0") }
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { provider.signTypedData(devnet, "wallet", "{}") }
        }
        Unit
    }

    // ---------- SPL fixtures ----------

    private val mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val recipient get() = MockTurnkey.DEFAULT_SOLANA_RECIPIENT

    /**
     * Stubs every read the SPL path makes: the mint, the recipient wallet, both token accounts,
     * the fee balance, a blockhash and a simulation. Returns true so callers can assert on it.
     */
    private fun splFixture(
        recipientAccountExists: Boolean,
        senderAccountExists: Boolean = true,
        senderBaseUnits: String = "5000000", // 5.0 at 6 decimals
        tokenProgram: String = SolanaPrograms.TOKEN_ADDRESS,
        simulationError: String? = null,
        lamports: Long = 1_000_000_000L
    ): Boolean {
        val tokenProgramKey = Base58.decode(tokenProgram)
        val mintKey = Base58.decode(mint)
        val senderAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS), mintKey, tokenProgramKey
            )
        )
        val recipientAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(recipient), mintKey, tokenProgramKey
            )
        )

        rpc.stubObjectFor("getAccountInfo", mint, accountEnvelope(mintValue(tokenProgram)))
        // The recipient is a plain wallet: parsed data absent, owned by the system program.
        rpc.stubObjectFor("getAccountInfo", recipient, accountEnvelope(walletValue()))
        rpc.stubObjectFor(
            "getAccountInfo",
            senderAta,
            accountEnvelope(
                if (senderAccountExists) tokenAccountValue(senderBaseUnits, tokenProgram)
                else JSONObject.NULL
            )
        )
        rpc.stubObjectFor(
            "getAccountInfo",
            recipientAta,
            accountEnvelope(
                if (recipientAccountExists) tokenAccountValue("0", tokenProgram) else JSONObject.NULL
            )
        )
        rpc.stubObject(
            "getBalance",
            JSONObject().put("context", JSONObject().put("slot", 1)).put("value", lamports)
        )
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put(
                    "value",
                    JSONObject()
                        .put("blockhash", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                        .put("lastValidBlockHeight", 150)
                )
        )
        rpc.stubObject(
            "simulateTransaction",
            JSONObject().put("context", JSONObject().put("slot", 1)).put(
                "value",
                JSONObject()
                    .put("err", simulationError?.let { JSONObject().put(it, JSONArray()) } ?: JSONObject.NULL)
                    .put("logs", JSONArray())
            )
        )
        rpc.stubObject(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", SIGNATURE).put("slot", 150))
        )
        return true
    }

    private fun includedStatusClient() = MockTurnkeyClient().apply {
        sendTransactionStatusQueue = mutableListOf(
            MockTurnkeyClient.StatusFixture(solanaSignature = SIGNATURE, txStatus = "TX_STATUS_INCLUDED")
        )
    }

    private fun accountEnvelope(value: Any): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", value)

    private fun walletValue(): JSONObject = JSONObject()
        .put("owner", SolanaPrograms.SYSTEM_ADDRESS)
        .put("lamports", 1_000_000_000L)
        .put("data", JSONArray().put("").put("base64"))

    private fun mintValue(program: String): JSONObject = JSONObject()
        .put("owner", program)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "mint").put("info", JSONObject().put("decimals", 6))
            )
        )

    private fun tokenAccountValue(
        amount: String,
        program: String = SolanaPrograms.TOKEN_ADDRESS
    ): JSONObject = JSONObject()
        .put("owner", program)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "account").put(
                    "info",
                    JSONObject()
                        .put("mint", mint)
                        .put("owner", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                        .put(
                            "tokenAmount",
                            JSONObject().put("amount", amount).put("decimals", 6)
                        )
                )
            )
        )

    /**
     * Pulls `(programId, data)` out of each instruction in a serialized unsigned transaction, so
     * tests can assert what was actually submitted without re-implementing the whole parser.
     */
    private fun decodeInstructions(unsignedTransactionHex: String): List<Pair<String, ByteArray>> {
        val bytes = unsignedTransactionHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var i = 0
        fun byte(): Int = bytes[i++].toInt() and 0xFF

        val signatureCount = byte()
        i += signatureCount * 64
        i += 3 // header
        val accountCount = byte()
        val accounts = (0 until accountCount).map {
            Base58.encode(bytes.copyOfRange(i + it * 32, i + it * 32 + 32))
        }
        i += accountCount * 32
        i += 32 // blockhash

        val instructionCount = byte()
        return (0 until instructionCount).map {
            val programId = accounts[byte()]
            val accountIndexCount = byte()
            i += accountIndexCount
            val dataLength = byte()
            val data = bytes.copyOfRange(i, i + dataLength)
            i += dataLength
            programId to data
        }
    }

    private companion object {
        const val SIGNATURE = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"
    }
}
