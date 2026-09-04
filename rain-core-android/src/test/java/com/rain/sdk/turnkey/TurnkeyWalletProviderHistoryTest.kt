package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.RainTransactionOrder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class TurnkeyWalletProviderHistoryTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private class FakeTurnkeyHistory(
        var ethResponse: TurnkeyEthHistoryResponse = TurnkeyEthHistoryResponse(),
        var solResponse: TurnkeySolHistoryResponse = TurnkeySolHistoryResponse(),
        var error: Exception? = null
    ) : TurnkeyHistoryProtocol {

        data class Call(
            val organizationId: String,
            val sessionPublicKey: String,
            val address: String,
            val caip2: String,
            val limit: Int
        )

        val ethCalls = mutableListOf<Call>()
        val solCalls = mutableListOf<Call>()

        override suspend fun listEthTransactionHistory(
            organizationId: String,
            sessionPublicKey: String,
            address: String,
            caip2: String,
            limit: Int
        ): TurnkeyEthHistoryResponse {
            ethCalls += Call(organizationId, sessionPublicKey, address, caip2, limit)
            error?.let { throw it }
            return ethResponse
        }

        override suspend fun listSolTransactionHistory(
            organizationId: String,
            sessionPublicKey: String,
            address: String,
            caip2: String,
            limit: Int
        ): TurnkeySolHistoryResponse {
            solCalls += Call(organizationId, sessionPublicKey, address, caip2, limit)
            error?.let { throw it }
            return solResponse
        }
    }

    private fun makeProvider(
        turnkey: MockTurnkey = MockTurnkey(),
        history: TurnkeyHistoryProtocol = FakeTurnkeyHistory(),
        rpcEndpoints: Map<Int, String> = mapOf(1 to "https://eth.example/rpc")
    ): TurnkeyWalletProvider = TurnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = rpcEndpoints,
        httpClient = OkHttpClient(),
        chainReader = MockChainReader(),
        history = history
    )

    private fun ethTransaction(
        hash: String = "0xhash",
        timestamp: String = "2026-08-12T10:00:00Z",
        blockNumber: String = "123",
        status: String? = "CONFIRMED",
        from: String? = "0xsender",
        to: String? = "0xrecipient",
        transfers: List<TurnkeyHistoryTransfer> = emptyList(),
        sponsored: Boolean? = false
    ) = TurnkeyEthHistoryTransaction(
        transactionHash = hash,
        block = TurnkeyHistoryBlock(number = blockNumber, hash = "0xblock", timestamp = timestamp),
        status = status,
        from = from,
        to = to,
        transfers = transfers,
        turnkey = sponsored?.let { TurnkeyHistoryOrigin(sponsored = it) }
    )

    private fun nativeOut(
        amount: String = "1500000000000000000",
        counterparty: String = "0xrecipient"
    ) = TurnkeyHistoryTransfer(
        direction = "OUT",
        asset = TurnkeyHistoryAsset(
            caip19 = "eip155:1/slip44:60",
            symbol = "ETH",
            name = "Ether",
            decimals = 18
        ),
        amount = amount,
        counterparty = counterparty,
        display = TurnkeyHistoryDisplay(crypto = "1.5", usd = "5000.00")
    )

    // ---------- EVM mapping ----------

    @Test
    fun `evm native OUT transfer maps hash block value and counterparty`() = runBlocking<Unit> {
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(transfers = listOf(nativeOut()))))
        )
        val provider = makeProvider(history = history)

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.hash).isEqualTo("0xhash")
        assertThat(tx.uniqueId).isEqualTo("0xhash")
        assertThat(tx.blockNumber).isEqualTo("123")
        assertThat(tx.timestamp).isEqualTo("2026-08-12T10:00:00Z")
        // OUT is relative to the queried wallet, so the wallet is the sender.
        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(tx.to).isEqualTo("0xrecipient")
        assertThat(tx.value).isEqualTo(BigDecimal("1.5"))
        assertThat(tx.rawValue).isEqualTo("1500000000000000000")
        assertThat(tx.decimals).isEqualTo(18)
        assertThat(tx.asset).isEqualTo("ETH")
        assertThat(tx.tokenAddress).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        assertThat(tx.chainId).isEqualTo(1)
        assertThat(tx.metadata?.caip2).isEqualTo("eip155:1")
        assertThat(tx.metadata?.status).isEqualTo("confirmed")
        assertThat(tx.metadata?.sponsored).isFalse()
        assertThat(tx.metadata?.type).isEqualTo("transferSent")
        assertThat(tx.metadata?.displayValues).containsExactly("crypto", "1.5", "usd", "5000.00")
    }

    @Test
    fun `evm IN transfer swaps counterparty into from and wallet into to`() = runBlocking {
        val incoming = TurnkeyHistoryTransfer(
            direction = "IN",
            asset = TurnkeyHistoryAsset(caip19 = "eip155:1/slip44:60", symbol = "ETH", decimals = 18),
            amount = "1000000000000000000",
            counterparty = "0xpayer"
        )
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(
                listOf(ethTransaction(from = "0xpayer", to = null, transfers = listOf(incoming)))
            )
        )
        val provider = makeProvider(history = history)

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.from).isEqualTo("0xpayer")
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(tx.metadata?.type).isEqualTo("transferReceived")
    }

    @Test
    fun `evm erc20 transfer carries token address and erc20 category`() = runBlocking {
        val tokenOut = TurnkeyHistoryTransfer(
            direction = "OUT",
            asset = TurnkeyHistoryAsset(
                caip19 = "eip155:1/erc20:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                symbol = "USDC",
                decimals = 6
            ),
            amount = "2500000",
            counterparty = "0xrecipient"
        )
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(transfers = listOf(tokenOut))))
        )
        val provider = makeProvider(history = history)

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.tokenAddress).isEqualTo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")
        assertThat(tx.category).isEqualTo(RainTransactionCategory.Erc20)
        assertThat(tx.value).isEqualTo(BigDecimal("2.5"))
        assertThat(tx.asset).isEqualTo("USDC")
    }

    @Test
    fun `evm row without transfers keeps transaction addresses and carries no amount`() = runBlocking {
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(transfers = emptyList())))
        )
        val provider = makeProvider(history = history)

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.from).isEqualTo("0xsender")
        assertThat(tx.to).isEqualTo("0xrecipient")
        assertThat(tx.value).isNull()
        assertThat(tx.rawValue).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        assertThat(tx.metadata?.type).isNull()
    }

    @Test
    fun `evm large amount scales exactly without Double precision loss`() = runBlocking {
        val transfer = nativeOut(amount = "123456789012345678901")
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(transfers = listOf(transfer))))
        )
        val provider = makeProvider(history = history)

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.value).isEqualTo(BigDecimal("123.456789012345678901"))
    }

    // ---------- request parameters ----------

    @Test
    fun `evm history request carries session wallet caip2 and clamped limit`() = runBlocking {
        val history = FakeTurnkeyHistory()
        val provider = makeProvider(history = history)

        provider.getTransactions(1, 7, 5, null)

        val call = history.ethCalls.single()
        assertThat(call.organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)
        assertThat(call.sessionPublicKey).isEqualTo("pubkey")
        assertThat(call.address).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(call.caip2).isEqualTo("eip155:1")
        assertThat(call.limit).isEqualTo(12)
    }

    @Test
    fun `history limit defaults to 10 and caps at 100`() = runBlocking {
        val history = FakeTurnkeyHistory()
        val provider = makeProvider(history = history)

        provider.getTransactions(1, null, null, null)
        provider.getTransactions(1, 90, 40, null)

        assertThat(history.ethCalls[0].limit).isEqualTo(10)
        assertThat(history.ethCalls[1].limit).isEqualTo(100)
    }

    // ---------- ordering and slicing ----------

    @Test
    fun `rows sort DESC by default and honor ASC offset and limit`() = runBlocking<Unit> {
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(
                listOf(
                    ethTransaction(hash = "0xnewest", timestamp = "2026-08-12T12:00:00Z"),
                    ethTransaction(hash = "0xoldest", timestamp = "2026-08-12T10:00:00Z"),
                    ethTransaction(hash = "0xmiddle", timestamp = "2026-08-12T11:00:00Z")
                )
            )
        )
        val provider = makeProvider(history = history)

        val desc = provider.getTransactions(1, null, null, null)
        assertThat(desc.map { it.hash }).containsExactly("0xnewest", "0xmiddle", "0xoldest").inOrder()

        val asc = provider.getTransactions(1, null, null, RainTransactionOrder.ASC)
        assertThat(asc.map { it.hash }).containsExactly("0xoldest", "0xmiddle", "0xnewest").inOrder()

        val sliced = provider.getTransactions(1, 1, 1, RainTransactionOrder.DESC)
        assertThat(sliced.map { it.hash }).containsExactly("0xmiddle")
    }

    @Test
    fun `offset timestamps with explicit zone parse for sorting`() = runBlocking {
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(
                listOf(
                    ethTransaction(hash = "0xolder", timestamp = "2026-08-12T10:00:00+00:00"),
                    ethTransaction(hash = "0xnewer", timestamp = "2026-08-12T11:00:00+00:00")
                )
            )
        )
        val provider = makeProvider(history = history)

        val desc = provider.getTransactions(1, null, null, null)
        assertThat(desc.map { it.hash }).containsExactly("0xnewer", "0xolder").inOrder()
    }

    // ---------- Solana ----------

    @Test
    fun `solana history maps signature native SOL and SPL rows`() = runBlocking {
        val wallet = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        val caip2Devnet = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"
        val history = FakeTurnkeyHistory(
            solResponse = TurnkeySolHistoryResponse(
                listOf(
                    TurnkeySolHistoryTransaction(
                        signature = "5solSig",
                        block = TurnkeyHistoryBlock(number = "9", timestamp = "2026-08-12T11:00:00Z"),
                        status = "FINALIZED",
                        feePayer = wallet,
                        transfers = listOf(
                            TurnkeyHistoryTransfer(
                                direction = "OUT",
                                asset = TurnkeyHistoryAsset(
                                    caip19 = "$caip2Devnet/slip44:501",
                                    symbol = "SOL",
                                    decimals = 9
                                ),
                                amount = "1000000000",
                                counterparty = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
                            )
                        ),
                        turnkey = TurnkeyHistoryOrigin(sponsored = false)
                    ),
                    TurnkeySolHistoryTransaction(
                        signature = "5splSig",
                        block = TurnkeyHistoryBlock(number = "8", timestamp = "2026-08-12T10:00:00Z"),
                        status = "CONFIRMED",
                        feePayer = wallet,
                        transfers = listOf(
                            TurnkeyHistoryTransfer(
                                direction = "IN",
                                asset = TurnkeyHistoryAsset(
                                    caip19 = "$caip2Devnet/token:MintAddr111",
                                    symbol = "USDC",
                                    decimals = 6
                                ),
                                amount = "2500000",
                                counterparty = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
                            )
                        )
                    )
                )
            )
        )
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val provider = makeProvider(
            turnkey = turnkey,
            history = history,
            rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "https://sol.example/rpc")
        )

        val txs = provider.getTransactions(RainChain.SOLANA_DEVNET, null, null, null)

        val call = history.solCalls.single()
        assertThat(call.address).isEqualTo(wallet)
        assertThat(call.caip2).isEqualTo(caip2Devnet)
        assertThat(history.ethCalls).isEmpty()

        val native = txs[0]
        assertThat(native.hash).isEqualTo("5solSig")
        assertThat(native.from).isEqualTo(wallet)
        assertThat(native.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(native.value).isEqualTo(BigDecimal("1"))
        assertThat(native.asset).isEqualTo("SOL")
        assertThat(native.tokenAddress).isNull()
        assertThat(native.category).isEqualTo(RainTransactionCategory.External)
        assertThat(native.metadata?.status).isEqualTo("finalized")

        val spl = txs[1]
        assertThat(spl.hash).isEqualTo("5splSig")
        assertThat(spl.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(spl.to).isEqualTo(wallet)
        assertThat(spl.tokenAddress).isEqualTo("MintAddr111")
        assertThat(spl.category).isEqualTo(RainTransactionCategory.Token)
        assertThat(spl.value).isEqualTo(BigDecimal("2.5"))
        assertThat(spl.metadata?.type).isEqualTo("transferReceived")
    }

    @Test
    fun `erc721 transfer maps the collection address and erc721 category`() = runBlocking<Unit> {
        val nftIn = TurnkeyHistoryTransfer(
            direction = "IN",
            asset = TurnkeyHistoryAsset(caip19 = "eip155:1/erc721:0xNftContract/1234", symbol = "COOL"),
            amount = "1",
            counterparty = "0xminter"
        )
        val provider = makeProvider(
            history = FakeTurnkeyHistory(
                ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(transfers = listOf(nftIn))))
            )
        )

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.tokenAddress).isEqualTo("0xNftContract")
        assertThat(tx.category).isEqualTo(RainTransactionCategory.Erc721)
    }

    @Test
    fun `amount without decimals keeps value null and rawValue set`() = runBlocking<Unit> {
        val unknownScale = TurnkeyHistoryTransfer(
            direction = "OUT",
            asset = TurnkeyHistoryAsset(caip19 = "eip155:1/erc20:0xtoken", symbol = "MYS"),
            amount = "12345",
            counterparty = "0xrecipient"
        )
        val provider = makeProvider(
            history = FakeTurnkeyHistory(
                ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(transfers = listOf(unknownScale))))
            )
        )

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.value).isNull()
        assertThat(tx.rawValue).isEqualTo("12345")
    }

    @Test
    fun `multi-transfer row renders its first transfer only`() = runBlocking<Unit> {
        val swap = ethTransaction(
            transfers = listOf(
                nativeOut(amount = "1000000000000000000"),
                TurnkeyHistoryTransfer(
                    direction = "IN",
                    asset = TurnkeyHistoryAsset(caip19 = "eip155:1/erc20:0xweth", symbol = "WETH", decimals = 18),
                    amount = "300000000000000000",
                    counterparty = "0xpool"
                )
            )
        )
        val provider = makeProvider(
            history = FakeTurnkeyHistory(ethResponse = TurnkeyEthHistoryResponse(listOf(swap)))
        )

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.asset).isEqualTo("ETH")
        assertThat(tx.metadata?.type).isEqualTo("transferSent")
    }

    @Test
    fun `multi-word status maps to the privy-style camelCase vocabulary`() = runBlocking<Unit> {
        val provider = makeProvider(
            history = FakeTurnkeyHistory(
                ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(status = "EXECUTION_REVERTED")))
            )
        )

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.metadata?.status).isEqualTo("executionReverted")
    }

    @Test
    fun `fractional timestamp is normalized to second-precision Zulu`() = runBlocking<Unit> {
        val provider = makeProvider(
            history = FakeTurnkeyHistory(
                ethResponse = TurnkeyEthHistoryResponse(
                    listOf(ethTransaction(timestamp = "2026-08-12T10:00:00.123Z"))
                )
            )
        )

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.timestamp).isEqualTo("2026-08-12T10:00:00Z")
    }

    @Test
    fun `row without a block sorts newest so a pending send stays on the first page`() = runBlocking<Unit> {
        val pending = TurnkeyEthHistoryTransaction(transactionHash = "0xpending")
        val provider = makeProvider(
            history = FakeTurnkeyHistory(
                ethResponse = TurnkeyEthHistoryResponse(
                    listOf(
                        ethTransaction(hash = "0xmined", timestamp = "2026-08-12T12:00:00Z"),
                        pending
                    )
                )
            )
        )

        val firstPage = provider.getTransactions(1, 1, null, null)

        assertThat(firstPage.single().hash).isEqualTo("0xpending")
        assertThat(firstPage.single().timestamp).isNull()
    }

    @Test
    fun `rows sharing a timestamp keep API order under DESC and reverse under ASC`() = runBlocking<Unit> {
        val provider = makeProvider(
            history = FakeTurnkeyHistory(
                ethResponse = TurnkeyEthHistoryResponse(
                    listOf(
                        ethTransaction(hash = "0xfirstListed", timestamp = "2026-08-12T10:00:00Z"),
                        ethTransaction(hash = "0xsecondListed", timestamp = "2026-08-12T10:00:00Z")
                    )
                )
            )
        )

        val desc = provider.getTransactions(1, null, null, RainTransactionOrder.DESC)
        assertThat(desc.map { it.hash }).containsExactly("0xfirstListed", "0xsecondListed").inOrder()

        val asc = provider.getTransactions(1, null, null, RainTransactionOrder.ASC)
        assertThat(asc.map { it.hash }).containsExactly("0xsecondListed", "0xfirstListed").inOrder()
    }

    @Test
    fun `sponsored solana OUT reports the wallet as sender, not the fee payer`() = runBlocking<Unit> {
        val wallet = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        val caip2Devnet = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"
        val provider = makeProvider(
            turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana())),
            history = FakeTurnkeyHistory(
                solResponse = TurnkeySolHistoryResponse(
                    listOf(
                        TurnkeySolHistoryTransaction(
                            signature = "sponsoredSig",
                            block = TurnkeyHistoryBlock(timestamp = "2026-08-13T18:31:47Z"),
                            feePayer = "SponsorFeePayer111",
                            transfers = listOf(
                                TurnkeyHistoryTransfer(
                                    direction = "OUT",
                                    asset = TurnkeyHistoryAsset(
                                        caip19 = "$caip2Devnet/slip44:501",
                                        symbol = "SOL",
                                        decimals = 9
                                    ),
                                    amount = "1000000000",
                                    counterparty = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
                                )
                            ),
                            turnkey = TurnkeyHistoryOrigin(sponsored = true)
                        )
                    )
                )
            ),
            rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "https://sol.example/rpc")
        )

        val tx = provider.getTransactions(RainChain.SOLANA_DEVNET, null, null, null).single()

        assertThat(tx.from).isEqualTo(wallet)
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(tx.metadata?.sponsored).isTrue()
    }

    @Test
    fun `hostile payload shapes render defensively instead of crashing or scaling absurdly`() = runBlocking<Unit> {
        val hostile = TurnkeyHistoryTransfer(
            direction = "OUT",
            // Malformed CAIP-19: empty reference before a trailing slash.
            asset = TurnkeyHistoryAsset(caip19 = "eip155:1/erc20:/", symbol = "EVIL", decimals = 999_999_999),
            amount = "12345",
            counterparty = "0xrecipient"
        )
        val provider = makeProvider(
            history = FakeTurnkeyHistory(
                ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction(transfers = listOf(hostile))))
            )
        )

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.tokenAddress).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        assertThat(tx.value).isNull()
        assertThat(tx.decimals).isNull()
        assertThat(tx.rawValue).isEqualTo("12345")
    }

    @Test
    fun `blank counterparty falls back to transaction addresses instead of empty strings`() = runBlocking<Unit> {
        val wallet = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        val caip2Devnet = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"

        // Live shape: Turnkey sends counterparty as "" (not null) when it is unknown.
        fun transfer(direction: String) = TurnkeyHistoryTransfer(
            direction = direction,
            asset = TurnkeyHistoryAsset(caip19 = "$caip2Devnet/token:Mint111", symbol = "USDC", decimals = 6),
            amount = "1000000",
            counterparty = ""
        )
        val history = FakeTurnkeyHistory(
            solResponse = TurnkeySolHistoryResponse(
                listOf(
                    TurnkeySolHistoryTransaction(
                        signature = "inSig",
                        block = TurnkeyHistoryBlock(timestamp = "2026-08-13T18:31:47Z"),
                        feePayer = wallet,
                        transfers = listOf(transfer("IN"))
                    ),
                    TurnkeySolHistoryTransaction(
                        signature = "outSig",
                        block = TurnkeyHistoryBlock(timestamp = "2026-08-13T18:14:24Z"),
                        feePayer = wallet,
                        transfers = listOf(transfer("OUT"))
                    )
                )
            )
        )
        val provider = makeProvider(
            turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana())),
            history = history,
            rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "https://sol.example/rpc")
        )

        val txs = provider.getTransactions(RainChain.SOLANA_DEVNET, null, null, null)

        val incoming = txs.first { it.hash == "inSig" }
        assertThat(incoming.from).isEqualTo(wallet)
        assertThat(incoming.to).isEqualTo(wallet)
        val outgoing = txs.first { it.hash == "outSig" }
        assertThat(outgoing.from).isEqualTo(wallet)
        assertThat(outgoing.to).isNull()
    }

    // ---------- fallback ----------

    @Test
    fun `history failure falls back to the activity log`() = runBlocking {
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeActivity(
                    id = "activity-1",
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = "0xrecipient",
                    caip2 = "eip155:1",
                    value = "1000000000000000000",
                    data = "0x",
                    sendTransactionStatusId = "status-1"
                )
            )
        )
        val turnkey = MockTurnkey(turnkeyClient = client)
        val history = FakeTurnkeyHistory(error = TurnkeyHistoryError(403, "feature is not enabled"))
        val provider = makeProvider(turnkey = turnkey, history = history)

        val txs = provider.getTransactions(1, null, null, null)

        assertThat(history.ethCalls).hasSize(1)
        assertThat(client.getActivitiesCalls).hasSize(1)
        assertThat(txs.single().uniqueId).isEqualTo("activity-1")
        assertThat(txs.single().value).isEqualTo(BigDecimal("1"))
    }

    @Test
    fun `history success does not touch the activity log`() = runBlocking {
        val client = MockTurnkeyClient()
        val turnkey = MockTurnkey(turnkeyClient = client)
        val history = FakeTurnkeyHistory(
            ethResponse = TurnkeyEthHistoryResponse(listOf(ethTransaction()))
        )
        val provider = makeProvider(turnkey = turnkey, history = history)

        provider.getTransactions(1, null, null, null)

        assertThat(client.getActivitiesCalls).isEmpty()
    }

    @Test
    fun `missing session throws TokenExpired without consulting the activity log`() {
        val client = MockTurnkeyClient()
        val provider = makeProvider(turnkey = MockTurnkey(session = null, turnkeyClient = client))

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getTransactions(1, null, null, null) }
        }
        assertThat(client.getActivitiesCalls).isEmpty()
    }
}
