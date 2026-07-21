package com.rain.sdk.privy

import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.utils.EthereumConverter
import io.privy.wallet.transactions.GetTransactionsParams
import io.privy.wallet.transactions.Transaction as PrivyTransaction
import io.privy.wallet.transactions.TransactionChain
import io.privy.wallet.transactions.TransactionStatus
import io.privy.wallet.transactions.TransactionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.time.Instant
import timber.log.Timber
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function as Web3jFunction
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Privy-backed [WalletProvider].
 *
 * Splits responsibilities the way the SDK's other adapters do: custody (signing, broadcasting) goes
 * through Privy's EIP-1193 embedded wallet via [PrivyManager]; balance and fee reads run through
 * [PrivyRpcClient] against Rain's configured RPC, with metadata resolved by [tokenStore].
 *
 * Transaction history comes from Privy's indexer via the wallet's `getTransactions` (TEE wallets
 * only, and only on chains Privy indexes — see [privyChain]); unsupported chains return an empty
 * result, matching the pre-indexer behavior.
 */
internal class PrivyWalletProvider(
    private val manager: PrivyManager,
    private val rpcEndpoints: Map<Int, String>,
    private val tokenStore: TokenMetadataStore,
    private val walletAddressOverride: String? = null,
    private val rpcClient: PrivyRpcClient = PrivyRpcClient(),
) : WalletProvider {

    override val id: ProviderId get() = ProviderId.PRIVY

    /** Privy holds an exportable embedded key with a recovery flow. */
    override val capabilities: Set<Capability>
        get() = setOf(Capability.EXPORT, Capability.RECOVERY)

    @Volatile
    private var cachedAddress: String? = null
    private val cachedAddressLock = Mutex()

    // ---------- address ----------

    override suspend fun getWalletAddress(): String {
        walletAddressOverride?.takeIf { it.isNotEmpty() }?.let { return it }
        cachedAddress?.let { return it }
        // Lock so concurrent first-access callers resolve the address once (parity with Turnkey),
        // rather than each firing a redundant Privy lookup.
        return cachedAddressLock.withLock {
            cachedAddress?.let { return@withLock it }
            manager.getAddress(walletAddressOverride).also { cachedAddress = it }
        }
    }

    // ---------- high-level send ----------

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal,
    ): String {
        val from = getWalletAddress()
        val valueHex = EthereumConverter.convertEthToWeiHex(amountInEth)
        return sendTransaction(chainId = chainId, from = from, to = toAddress, data = "0x", value = valueHex)
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int,
    ): String {
        val from = getWalletAddress()
        val data = Erc20Abi.encodeTransfer(toAddress, amount, decimals)
        return sendTransaction(chainId = chainId, from = from, to = contractAddress, data = data, value = "0x0")
    }

    // ---------- low-level send / sign / fee ----------

    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): String {
        val rpcUrl = rpcUrlFor(chainId)

        // Simulate the transaction first via eth_call to catch failures
        // (e.g. insufficient funds, contract reverts) — no balance fetch needed,
        // the node validates it for free. Mirrors the Portal adapter and the iOS Privy adapter.
        try {
            rpcClient.callForHexResult(
                rpcUrl, "eth_call", listOf(rpcTransactionObject(from, to, data, value), "latest")
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Rain SDK: Transaction simulation failed (eth_call)")
            throw RainError.TransactionSimulationFailed(e)
        }

        val transactionJson = JSONObject().apply {
            put("from", from)
            put("to", to)
            put("value", value.ifEmpty { "0x0" })
            if (data.isNotEmpty() && data != "0x") put("data", data)
            put("chainId", "0x${chainId.toString(16)}")
        }.toString()
        return manager.sendTransaction(walletAddress = from, rpcUrl = rpcUrl, transactionJson = transactionJson)
    }

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String,
    ): String = manager.signTypedData(walletAddress = walletAddress, typedDataJson = typedDataJson)

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): Double {
        val rpcUrl = rpcUrlFor(chainId)
        val gasLimitHex = rpcClient.callForHexResult(
            rpcUrl, "eth_estimateGas", listOf(rpcTransactionObject(from, to, data, value))
        )
        val gasPriceHex = rpcClient.callForHexResult(rpcUrl, "eth_gasPrice", emptyList())
        val gasLimit = EthereumConverter.parseHexToBigInteger(gasLimitHex)
        val gasPrice = EthereumConverter.parseHexToBigInteger(gasPriceHex)
        return EthereumConverter.convertWeiToEth(gasLimit.multiply(gasPrice))
    }

    // ---------- balances ----------

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        val walletAddress = getWalletAddress()
        return when (token) {
            is Token.Native -> fetchNativeBalance(chainId, walletAddress)
            is Token.Contract -> fetchContractBalance(chainId, walletAddress, token.address)
        }
    }

    override suspend fun getBalances(chainId: Int): List<Balance> = supervisorScope {
        val walletAddress = getWalletAddress()
        // Native is essential: its failure propagates (it is awaited directly below). Per-token
        // reads are best-effort — a single bad/failing contract must not drop the whole list, so
        // each is wrapped and skipped on failure. A semaphore caps concurrent RPC calls; without
        // it a large token registry would fan out one connection per token at once.
        val nativeDeferred = async { fetchNativeBalance(chainId, walletAddress) }
        val gate = Semaphore(MAX_CONCURRENT_BALANCE_READS)
        val contractDeferred = tokenStore.registeredTokens(chainId).map { info ->
            async {
                runCatching { gate.withPermit { fetchContractBalance(chainId, walletAddress, info.address) } }
                    .onFailure { e ->
                        Timber.w(e, "Rain SDK: Privy balance read failed for token=${info.address} chainId=$chainId; skipping")
                    }
                    .getOrNull()
            }
        }
        val output = mutableListOf(nativeDeferred.await())
        output += contractDeferred.awaitAll().filterNotNull().filter { it.rawAmount > BigInteger.ZERO }
        output
    }

    private suspend fun fetchNativeBalance(chainId: Int, walletAddress: String): Balance {
        val rpcUrl = rpcUrlFor(chainId)
        val hex = rpcClient.callForHexResult(rpcUrl, "eth_getBalance", listOf(walletAddress, "latest"))
        val native = tokenStore.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigInteger(hex),
            decimals = native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    private suspend fun fetchContractBalance(
        chainId: Int,
        walletAddress: String,
        address: String,
    ): Balance {
        val rpcUrl = rpcUrlFor(chainId)
        val info = tokenStore.tokenInfo(chainId, address)
        val function = Web3jFunction(
            "balanceOf",
            listOf(Address(walletAddress)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val callObject = mapOf("to" to address, "data" to FunctionEncoder.encode(function))
        val hex = rpcClient.callForHexResult(rpcUrl, "eth_call", listOf(callObject, "latest"))
        return Balance(
            token = Token.Contract(address),
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigInteger(hex),
            decimals = info.decimals,
            symbol = info.symbol,
            name = info.name
        )
    }

    // ---------- transactions ----------

    /**
     * Wallet history from Privy's transaction indexer.
     *
     * Privy's endpoint requires exactly one of an `asset` or `token` filter per request, so full
     * history is assembled from one native-asset query plus token-address queries over the
     * registered tokens (chunked to the server's 10-filter cap). The native query is essential
     * (its failure propagates); token queries are best-effort, mirroring [getBalances]. Privy
     * paginates by cursor while the SDK contract is limit/offset, so each query collects pages
     * until `offset + limit` rows are gathered (or history is exhausted); the merged rows are
     * deduped, sorted by `createdAt` per [order] (newest first by default, matching Turnkey) and
     * sliced. Chains Privy does not index (e.g. Base Sepolia) return an empty result rather than
     * failing, preserving the previous always-empty behavior.
     */
    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?,
    ): RainTransactionResult {
        val chain = privyChain(chainId)
            ?: return RainTransactionResult(transactions = emptyList())
        val walletAddress = getWalletAddress()
        val needed = maxOf((limit ?: DEFAULT_TRANSACTION_LIMIT) + (offset ?: 0), 1)

        val collected = mutableListOf<PrivyTransaction>()
        collected += collectTransactions(
            walletAddress, chain.chain, assets = listOf(chain.nativeAsset), tokens = null, needed = needed
        )
        tokenStore.registeredTokens(chainId)
            .map { it.address }
            .chunked(MAX_TRANSACTION_FILTERS_PER_REQUEST)
            .forEach { chunk ->
                runCatching {
                    collectTransactions(walletAddress, chain.chain, assets = null, tokens = chunk, needed = needed)
                }
                    .onSuccess { collected += it }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }

        val deduped = collected.distinctBy { it.privyTransactionId ?: it.transactionHash ?: it }
        val sorted = when (order ?: RainTransactionOrder.DESC) {
            RainTransactionOrder.ASC -> deduped.sortedBy { it.createdAt }
            RainTransactionOrder.DESC -> deduped.sortedByDescending { it.createdAt }
        }
        val sliced = sorted
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }

        return RainTransactionResult(transactions = sliced.map { toRainTransaction(chainId, it) })
    }

    /** Collects up to [needed] rows for one asset-or-token filter, following Privy's cursor. */
    private suspend fun collectTransactions(
        walletAddress: String,
        chain: TransactionChain.Evm,
        assets: List<String>?,
        tokens: List<String>?,
        needed: Int,
    ): List<PrivyTransaction> {
        val collected = mutableListOf<PrivyTransaction>()
        var cursor: String? = null
        do {
            val page = manager.getTransactions(
                walletAddress,
                GetTransactionsParams(
                    chain = chain,
                    assets = assets,
                    tokens = tokens,
                    limit = minOf(needed - collected.size, MAX_TRANSACTIONS_PAGE_SIZE),
                    cursor = cursor,
                ),
            )
            collected += page.transactions
            cursor = page.nextCursor
        } while (cursor != null && collected.size < needed)
        return collected
    }

    private fun toRainTransaction(chainId: Int, transaction: PrivyTransaction): RainTransaction {
        val details = transaction.details
        // `asset` is either a named asset ("eth", "usdc") or a token contract address; route it to
        // the field that matches its shape.
        val assetIsAddress = details?.asset?.startsWith("0x") == true
        return RainTransaction(
            hash = transaction.transactionHash
                ?: transaction.userOperationHash
                ?: transaction.privyTransactionId
                ?: "",
            blockNumber = null,
            blockTimestamp = Instant.ofEpochMilli(transaction.createdAt).toString(),
            from = details?.sender ?: "",
            to = details?.recipient,
            value = details?.let {
                BigDecimal(it.rawValue).movePointLeft(it.rawValueDecimals).stripTrailingZeros().toPlainString()
            },
            gas = null,
            gasPrice = null,
            chainId = chainId.toString(),
            symbol = details?.asset?.takeUnless { assetIsAddress },
            tokenAddress = details?.asset?.takeIf { assetIsAddress },
            metadata = buildMap {
                put("caip2", transaction.caip2)
                put("status", transaction.status.toRainValue())
                put("sponsored", transaction.sponsored)
                transaction.privyTransactionId?.let { put("privyTransactionId", it) }
                transaction.userOperationHash?.let { put("userOperationHash", it) }
                details?.type?.let { put("type", it.toRainValue()) }
                details?.displayValues?.takeIf { it.isNotEmpty() }?.let { put("displayValues", it) }
            },
        )
    }

    private fun TransactionStatus.toRainValue(): String = when (this) {
        TransactionStatus.Broadcasted -> "broadcasted"
        TransactionStatus.Confirmed -> "confirmed"
        TransactionStatus.ExecutionReverted -> "executionReverted"
        TransactionStatus.Failed -> "failed"
        TransactionStatus.Replaced -> "replaced"
        TransactionStatus.Finalized -> "finalized"
        TransactionStatus.ProviderError -> "providerError"
        TransactionStatus.Pending -> "pending"
        is TransactionStatus.Unknown -> rawValue
    }

    private fun TransactionType.toRainValue(): String = when (this) {
        TransactionType.TransferSent -> "transferSent"
        TransactionType.TransferReceived -> "transferReceived"
        is TransactionType.Unknown -> rawValue
    }

    /**
     * The [TransactionChain] and native-asset filter name Privy's indexer accepts for [chainId],
     * or null when the chain is not indexed (verified against the endpoint's server-side enum:
     * ethereum, arbitrum, avalanche, base, bsc, tempo, linea, optimism, polygon, solana, sepolia).
     * Privy identifies chains by slug, not chain id; when Privy adds a slug with no SDK case yet
     * (e.g. a testnet), map it here via [TransactionChain.Evm.Custom].
     */
    private fun privyChain(chainId: Int): PrivyIndexedChain? = when (chainId) {
        1 -> PrivyIndexedChain(TransactionChain.Evm.Ethereum, "eth")
        10 -> PrivyIndexedChain(TransactionChain.Evm.Optimism, "eth")
        56 -> PrivyIndexedChain(TransactionChain.Evm.Bsc, "bnb")
        137 -> PrivyIndexedChain(TransactionChain.Evm.Polygon, "pol")
        8453 -> PrivyIndexedChain(TransactionChain.Evm.Base, "eth")
        42161 -> PrivyIndexedChain(TransactionChain.Evm.Arbitrum, "eth")
        43114 -> PrivyIndexedChain(TransactionChain.Evm.Avalanche, "avax")
        59144 -> PrivyIndexedChain(TransactionChain.Evm.Linea, "eth")
        11155111 -> PrivyIndexedChain(TransactionChain.Evm.Sepolia, "eth")
        else -> null
    }

    private data class PrivyIndexedChain(val chain: TransactionChain.Evm, val nativeAsset: String)

    // ---------- helpers ----------

    private fun rpcUrlFor(chainId: Int): String =
        rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")

    private fun rpcTransactionObject(
        from: String,
        to: String,
        data: String,
        value: String,
    ): Map<String, String> {
        val tx = mutableMapOf("from" to from, "to" to to, "value" to value.ifEmpty { "0x0" })
        if (data.isNotEmpty() && data != "0x") tx["data"] = data
        return tx
    }

    private companion object {
        /** Upper bound on simultaneous per-token balance RPC calls in [getBalances]. */
        const val MAX_CONCURRENT_BALANCE_READS = 8

        /** Rows returned by [getTransactions] when the caller passes no limit (parity with Turnkey). */
        const val DEFAULT_TRANSACTION_LIMIT = 10

        /** Privy's server-side maximum page size for its transactions endpoint. */
        const val MAX_TRANSACTIONS_PAGE_SIZE = 100

        /** Privy's server-side maximum number of asset/token filters per request. */
        const val MAX_TRANSACTION_FILTERS_PER_REQUEST = 10
    }
}
