package com.rain.sdk.turnkey

import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.JsonRpcClient
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.solana.SolanaConverter
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.solana.SolanaTransactionDecoder
import com.rain.sdk.internal.solana.SolanaTransferComposer
import com.rain.sdk.internal.solana.UnsignedSolanaTransfer
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.internal.utils.ChainIdFormat
import com.rain.sdk.internal.utils.strippingHexPrefix
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.utils.EthereumConverter
import com.turnkey.types.TEthSendTransactionBody
import com.turnkey.types.TGetActivitiesBody
import com.turnkey.types.TGetSendTransactionStatusBody
import com.turnkey.types.TGetWalletAddressBalancesBody
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.V1ActivityType
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1Pagination
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1SignRawPayloadResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turnkey-based implementation of [WalletProvider]. Used when the SDK is initialized with
 * `initializeTurnkey(...)`.
 *
 * Balance reads route through Turnkey's `get_wallet_address_balances` when the chain is in
 * [RainConstants.TURNKEY_SUPPORTED_CHAINS]; everything else falls through to the injected
 * [ChainReader] (parallel `eth_call` + Multicall3 where deployed).
 */
internal class TurnkeyWalletProvider(
    private val turnkey: TurnkeyContextProtocol,
    private val rpcEndpoints: Map<Int, String>,
    private val walletAddressOverride: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val pollingIntervalMs: Long = POLLING_INTERVAL_MS,
    jsonRpcClient: JsonRpcClient = JsonRpcClient(httpClient),
    chainReader: ChainReader? = null,
    solanaChainReader: ChainReader? = null,
    solanaRpcClient: SolanaRpcClient? = null,
    solanaSupport: SolanaSupport? = null,
    tokenStore: TokenMetadataStore? = null
) : WalletProvider {

    override val id: ProviderId get() = ProviderId.TURNKEY

    /** Turnkey holds EVM + Solana accounts (multi-chain) and gates signing behind passkeys/biometrics. */
    override val capabilities: Set<Capability>
        get() = setOf(Capability.MULTI_CHAIN, Capability.BIOMETRIC_GATE)

    private val jsonRpcClient: JsonRpcClient = jsonRpcClient
    private val chainReader: ChainReader = chainReader
        ?: EvmChainReader(rpcEndpoints = rpcEndpoints, jsonRpcClient = jsonRpcClient)

    // Explicit test doubles win; then the shared Solana stack; a private stack is the last resort.
    private val solanaRpcClient: SolanaRpcClient =
        solanaRpcClient ?: solanaSupport?.rpc ?: SolanaRpcClient(jsonRpcClient)
    private val solanaChainReader: ChainReader = solanaChainReader
        ?: solanaSupport?.chainReader
        ?: SolanaChainReader(rpcEndpoints = rpcEndpoints, solanaRpcClient = this.solanaRpcClient)
    private val solanaTransferComposer = solanaSupport?.composer
        ?: SolanaTransferComposer(this.solanaRpcClient, rpcEndpoints::get)

    /** Picks the reader for [chainId]'s chain family. */
    private fun chainReaderFor(chainId: Int): ChainReader =
        if (SolanaChains.isSolanaChain(chainId)) solanaChainReader else chainReader

    // Resolves token metadata (decimals / symbol / name) and enriches unknown tokens once.
    private val tokenStore: TokenMetadataStore = tokenStore ?: TokenMetadataStore(this.chainReader)

    // Once resolved, the wallet address is stable for the provider's lifetime, so cache
    // it. Mutex (rather than synchronized) so the suspend-friendly address() doesn't block
    // a thread while it's waiting on Turnkey's refresh.
    private val cachedAddressLock = Mutex()
    @Volatile
    private var cachedAddress: String? = null
    @Volatile
    private var cachedSolanaAddress: String? = null

    private companion object {
        const val DEFAULT_NATIVE_DECIMALS = 18
        const val DEFAULT_POLLING_ATTEMPTS = 30
        const val POLLING_INTERVAL_MS = 1_000L
        const val FALLBACK_GAS_LIMIT = 21_000L

        // Turnkey returns a status id, not a Solana signature, so the signature is read back
        // from chain — which lags broadcast by a beat. Retry briefly before giving up.
        const val SOLANA_SIGNATURE_LOOKUP_ATTEMPTS = 8
    }

    private data class ActivityDraft(
        val id: String,
        val timestampSeconds: Double,
        val from: String,
        val to: String,
        val value: String?,
        val data: String?,
        val chainId: Int,
        val sendTransactionStatusId: String?
    )

    /** True when Turnkey's `get-balances` API covers [chainId] (EVM allowlist or any Solana cluster). */
    private fun usesTurnkeyForBalances(chainId: Int): Boolean =
        chainId in RainConstants.TURNKEY_SUPPORTED_CHAINS || SolanaChains.isSolanaChain(chainId)

    /** CAIP-2 for [chainId]: EIP-155 for EVM, genesis-hash form for Solana clusters. */
    private fun caip2For(chainId: Int): String =
        ChainIdFormat.namespaceFor(chainId).format(chainId)

    // ---------- address ----------

    override suspend fun getWalletAddress(): String {
        walletAddressOverride?.takeIf { it.isNotEmpty() }?.let { return it }

        cachedAddress?.let { return it }

        return cachedAddressLock.withLock {
            cachedAddress?.let { return@withLock it }

            resolveEthereumWalletAddress(turnkey.wallets)?.also { cachedAddress = it }
                ?: run {
                    turnkey.refreshWallets()
                    resolveEthereumWalletAddress(turnkey.wallets)?.also { cachedAddress = it }
                        ?: throw RainError.WalletUnavailable("No Ethereum wallet available from Turnkey context")
                }
        }
    }

    /**
     * Chain-aware address. Solana chains resolve the Turnkey Solana account (base58, ed25519);
     * every other chain shares the Ethereum account. Internal balance / send paths use this so
     * a Solana request never reads or signs with the EVM address.
     */
    override suspend fun getWalletAddress(chainId: Int): String =
        if (SolanaChains.isSolanaChain(chainId)) getSolanaAddress() else getWalletAddress()

    private suspend fun getSolanaAddress(): String {
        cachedSolanaAddress?.let { return it }

        return cachedAddressLock.withLock {
            cachedSolanaAddress?.let { return@withLock it }

            resolveSolanaWalletAddress(turnkey.wallets)?.also { cachedSolanaAddress = it }
                ?: run {
                    turnkey.refreshWallets()
                    resolveSolanaWalletAddress(turnkey.wallets)?.also { cachedSolanaAddress = it }
                        ?: throw RainError.WalletUnavailable("No Solana wallet available from Turnkey context")
                }
        }
    }

    private fun resolveEthereumWalletAddress(wallets: List<com.turnkey.core.models.Wallet>): String? {
        return wallets
            .flatMap { it.accounts }
            .firstOrNull { it.addressFormat == V1AddressFormat.ADDRESS_FORMAT_ETHEREUM }
            ?.address
    }

    private fun resolveSolanaWalletAddress(wallets: List<com.turnkey.core.models.Wallet>): String? {
        return wallets
            .flatMap { it.accounts }
            .firstOrNull { it.addressFormat == V1AddressFormat.ADDRESS_FORMAT_SOLANA }
            ?.address
    }

    // ---------- high-level send ----------

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal
    ): String {
        if (SolanaChains.isSolanaChain(chainId)) {
            return sendSolanaNative(chainId, toAddress, amountInEth)
        }
        val from = getWalletAddress(chainId)
        val valueHex = EthereumConverter.convertEthToWeiHex(amountInEth)
        return sendTransaction(
            chainId = chainId,
            from = from,
            to = toAddress,
            data = "0x",
            value = valueHex
        )
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int
    ): String {
        if (SolanaChains.isSolanaChain(chainId)) {
            // `decimals` is deliberately unread — it is not authoritative here. sendSolanaSplToken
            // reads the mint's own scale from the chain, which `TransferChecked` then enforces.
            return sendSolanaSplToken(chainId, contractAddress, toAddress, amount)
        }
        val from = getWalletAddress(chainId)
        val data = Erc20Abi.encodeTransfer(toAddress, amount, decimals)
        return sendTransaction(
            chainId = chainId,
            from = from,
            to = contractAddress,
            data = data,
            value = "0x0"
        )
    }

    // ---------- low-level send / sign / fee ----------

    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String {
        requireEvmChain(chainId, "sendTransaction")
        val (session, client) = resolveSessionAndClient()
        val sendBody = buildSendTransactionBody(
            session = session,
            chainId = chainId,
            from = from,
            to = to,
            data = data,
            value = value
        )

        val response = client.ethSendTransaction(sendBody)
        val statusId = response.result.sendTransactionStatusId
        return pollForTransactionHash(client, session.organizationId, statusId)
    }

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        requireEvmChain(chainId, "signTypedData")
        val signature = turnkey.signRawPayload(
            signWith = walletAddress,
            payload = typedDataJson,
            encoding = V1PayloadEncoding.PAYLOAD_ENCODING_EIP712,
            hashFunction = V1HashFunction.HASH_FUNCTION_NO_OP
        )
        return ethereumSignatureHex(signature)
    }

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): BigDecimal {
        requireEvmChain(chainId, "estimateTransactionFee")
        val estimateHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_estimateGas",
            params = listOf(rpcTransactionObject(from, to, data, value))
        )
        val gasPriceHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_gasPrice",
            params = emptyList()
        )

        // Strict: an empty/garbage RPC payload must fail, not silently estimate a zero fee.
        val gasLimit = EthereumConverter.parseHexToBigIntegerStrict(estimateHex)
        val gasPrice = EthereumConverter.parseHexToBigIntegerStrict(gasPriceHex)
        return EthereumConverter.convertWeiToEthDecimal(gasLimit.multiply(gasPrice))
    }

    // ---------- balances ----------

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        val walletAddress = getWalletAddress(chainId)

        // Solana has its own balance policy (Turnkey-first with an RPC fallback),
        // so it branches out before the EVM logic below.
        if (SolanaChains.isSolanaChain(chainId)) {
            return solanaBalance(chainId, walletAddress, token)
        }

        return when (token) {
            is Token.Contract -> {
                // `eth_call balanceOf` is the same operation everywhere — delegate to the
                // chain reader so the SDK has one implementation rather than per-adapter copies.
                val info = tokenStore.tokenInfo(chainId, token.address)
                chainReaderFor(chainId).getBalance(
                    chainId = chainId,
                    walletAddress = walletAddress,
                    token = token,
                    tokenInfo = info
                )
            }
            is Token.Native -> {
                if (!usesTurnkeyForBalances(chainId)) {
                    chainReaderFor(chainId).getBalance(
                        chainId = chainId,
                        walletAddress = walletAddress,
                        token = Token.Native,
                        tokenInfo = null
                    )
                } else {
                    val balances = fetchBalances(chainId, walletAddress)
                    nativeBalance(chainId, balances, caip2For(chainId))
                }
            }
        }
    }

    /**
     * Solana balance read. Turnkey is the primary source, with the Solana RPC reader as the
     * fallback for both native SOL and SPL tokens — Turnkey does not index every cluster (devnet
     * in particular), and the node always does.
     */
    private suspend fun solanaBalance(chainId: Int, walletAddress: String, token: Token): Balance =
        runCatching {
            val balances = fetchBalances(chainId, walletAddress)
            val caip2 = caip2For(chainId)
            when (token) {
                is Token.Native -> nativeBalance(chainId, balances, caip2)
                is Token.Contract -> splBalance(chainId, walletAddress, balances, caip2, token.address)
            }
        }.getOrElse {
            chainReaderFor(chainId).getBalance(
                chainId = chainId,
                walletAddress = walletAddress,
                token = token,
                tokenInfo = (token as? Token.Contract)?.let { registeredSplToken(chainId, it.address) }
            )
        }

    /**
     * Builds an SPL [Balance] for [mint] from a Turnkey asset list.
     *
     * Turnkey omits zero balances, and on a cluster it does not index every mint looks like a
     * zero — so a missing entry is re-read from the node rather than reported as zero with
     * unknown decimals.
     */
    private suspend fun splBalance(
        chainId: Int,
        walletAddress: String,
        balances: List<V1AssetBalance>,
        caip2: String,
        mint: String
    ): Balance {
        val asset = balances.firstOrNull { tokenAddressFromCaip19(it.caip19 ?: "", caip2) == mint }
            ?: return chainReaderFor(chainId).getBalance(
                chainId = chainId,
                walletAddress = walletAddress,
                token = Token.Contract(mint),
                tokenInfo = registeredSplToken(chainId, mint)
            )
        val raw = runCatching { BigInteger(asset.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
        return contractBalanceFrom(chainId, mint, raw, asset)
    }

    /**
     * Host-registered metadata for [mint], if any.
     *
     * Reads only the registry — never [TokenMetadataStore.tokenInfo], whose enrichment path goes
     * through the EVM reader and cannot describe an SPL mint. This is how a caller names a token
     * that no indexer covers, via `registerTokens`.
     */
    private suspend fun registeredSplToken(chainId: Int, mint: String): TokenInfo? =
        tokenStore.registeredTokens(chainId).firstOrNull { it.address.equals(mint, ignoreCase = true) }

    override suspend fun getBalances(chainId: Int): List<Balance> {
        val walletAddress = getWalletAddress(chainId)

        if (!usesTurnkeyForBalances(chainId)) {
            val tokens = tokenStore.registeredTokens(chainId)
            val all = chainReaderFor(chainId).getBalances(chainId, walletAddress, tokens)
            return all.filter { balance ->
                balance.token is Token.Native || balance.rawAmount > BigInteger.ZERO
            }
        }

        if (SolanaChains.isSolanaChain(chainId)) {
            return solanaBalances(chainId, walletAddress)
        }

        val caip2 = caip2For(chainId)
        val balances = fetchBalances(chainId, walletAddress)

        val output = mutableListOf(nativeBalance(chainId, balances, caip2))
        for (balance in balances) {
            val caip19 = balance.caip19 ?: continue
            val tokenAddress = tokenAddressFromCaip19(caip19, caip2) ?: continue
            val raw = runCatching { BigInteger(balance.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
            if (raw <= BigInteger.ZERO) continue
            output += contractBalanceFrom(chainId, tokenAddress, raw, balance)
        }
        return output
    }

    /**
     * Native SOL plus every SPL token the wallet holds — Turnkey-first.
     *
     * Turnkey does not index every cluster: on devnet / testnet it errors, or answers with SOL
     * and no SPL assets at all. Discovering the wallet's token accounts from the node covers
     * both cases; on mainnet, where Turnkey does list SPL assets, its richer metadata wins.
     */
    private suspend fun solanaBalances(chainId: Int, walletAddress: String): List<Balance> {
        val caip2 = caip2For(chainId)
        val turnkeyAssets = runCatching { fetchBalances(chainId, walletAddress) }.getOrNull()
        val listsSplAssets = turnkeyAssets.orEmpty().any {
            tokenAddressFromCaip19(it.caip19 ?: "", caip2) != null
        }
        if (turnkeyAssets == null || !listsSplAssets) {
            return solanaBalancesFromNode(chainId, walletAddress)
        }

        val output = mutableListOf(nativeBalance(chainId, turnkeyAssets, caip2))
        for (balance in turnkeyAssets) {
            val caip19 = balance.caip19 ?: continue
            val tokenAddress = tokenAddressFromCaip19(caip19, caip2) ?: continue
            val raw = runCatching { BigInteger(balance.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
            if (raw <= BigInteger.ZERO) continue
            output += contractBalanceFrom(chainId, tokenAddress, raw, balance)
        }
        return output
    }

    /**
     * Native SOL plus the SPL tokens the wallet holds, read from the node. Zero balances are
     * dropped, matching every other chain. Naming falls back to host-registered tokens, so a
     * mint no indexer covers can still be labelled by the caller rather than shown as a bare
     * address.
     */
    private suspend fun solanaBalancesFromNode(chainId: Int, walletAddress: String): List<Balance> {
        val all = chainReaderFor(chainId).getBalances(
            chainId,
            walletAddress,
            tokenStore.registeredTokens(chainId)
        )
        return all.filter { balance ->
            balance.token is Token.Native || balance.rawAmount > BigInteger.ZERO
        }
    }

    /**
     * Builds a contract-token [Balance] from a Turnkey asset entry. EVM tokens are enriched via
     * [tokenStore] (registry / on-chain `decimals()`+`symbol()`) with Turnkey's values taking
     * precedence; Solana SPL tokens use Turnkey's metadata directly, since the Solana reader can't
     * enrich (`getDecimals`/`getSymbol` throw) and would only cache misleading defaults.
     */
    private suspend fun contractBalanceFrom(
        chainId: Int,
        tokenAddress: String,
        raw: BigInteger,
        balance: V1AssetBalance?
    ): Balance {
        if (SolanaChains.isSolanaChain(chainId)) {
            return Balance(
                token = Token.Contract(tokenAddress),
                chainId = chainId,
                rawAmount = raw,
                decimals = balance?.decimals?.toInt() ?: 0,
                symbol = balance?.symbol,
                name = balance?.name
            )
        }
        val info = tokenStore.tokenInfo(chainId, tokenAddress)
        return Balance(
            token = Token.Contract(tokenAddress),
            chainId = chainId,
            rawAmount = raw,
            decimals = balance?.decimals?.toInt() ?: info.decimals,
            symbol = balance?.symbol ?: info.symbol,
            name = balance?.name ?: info.name
        )
    }

    /**
     * Builds the native [Balance] from a Turnkey asset list. Turnkey reports balances in raw
     * base units, so the string is parsed directly as [BigInteger] (no decimal reconstruction).
     */
    private suspend fun nativeBalance(
        chainId: Int,
        balances: List<V1AssetBalance>,
        caip2: String
    ): Balance {
        val nativeAsset = balances.firstOrNull { isNativeAsset(it, caip2) }
        val raw = runCatching { BigInteger(nativeAsset?.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
        val native = tokenStore.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = raw,
            decimals = nativeAsset?.decimals?.toInt() ?: native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    // ---------- transactions ----------

    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        if (SolanaChains.isSolanaChain(chainId)) {
            return getSolanaTransactions(chainId, limit, offset, order)
        }
        val (session, client) = resolveSessionAndClient()
        val requestedLimit = minOf(maxOf(((limit ?: 10) + (offset ?: 0)), 1), 100)
        val activities = client.getActivities(
            TGetActivitiesBody(
                organizationId = session.organizationId,
                filterByType = listOf(V1ActivityType.ACTIVITY_TYPE_ETH_SEND_TRANSACTION),
                paginationOptions = V1Pagination(limit = requestedLimit.toString())
            )
        )

        val drafts = activities.activities.mapNotNull { activity ->
            val intent = activity.intent.ethSendTransactionIntent ?: return@mapNotNull null
            val txChainId = chainIdFromCaip2(intent.caip2)
            if (txChainId != chainId) return@mapNotNull null

            val seconds = activity.createdAt.seconds.toDoubleOrNull() ?: 0.0
            val nanos = activity.createdAt.nanos.toDoubleOrNull() ?: 0.0
            ActivityDraft(
                id = activity.id,
                timestampSeconds = seconds + nanos / 1_000_000_000.0,
                from = intent.from,
                to = intent.to,
                value = intent.value,
                data = intent.data,
                chainId = txChainId,
                sendTransactionStatusId = activity.result.ethSendTransactionResult?.sendTransactionStatusId
            )
        }

        val sorted = when (order ?: RainTransactionOrder.DESC) {
            RainTransactionOrder.ASC -> drafts.sortedBy { it.timestampSeconds }
            RainTransactionOrder.DESC -> drafts.sortedByDescending { it.timestampSeconds }
        }

        val sliced = sorted
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }

        val transactions = sliced.map { draft ->
            val txHash = runCatching {
                resolveTransactionHash(client, session.organizationId, draft.sendTransactionStatusId)
            }.getOrNull()

            RainTransaction(
                hash = txHash ?: draft.id,
                uniqueId = draft.id,
                timestamp = iso8601(draft.timestampSeconds),
                from = draft.from,
                to = draft.to,
                value = scaledDecimal(draft.value, DEFAULT_NATIVE_DECIMALS),
                tokenAddress = draft.to.takeIf { !draft.data.isNullOrEmpty() && draft.data != "0x" },
                rawValue = draft.value,
                decimals = DEFAULT_NATIVE_DECIMALS,
                category = RainTransactionCategory.External,
                chainId = draft.chainId
            )
        }
        return transactions
    }

    /**
     * Solana transaction history, sourced from Turnkey activities (`ACTIVITY_TYPE_SOL_SEND_TRANSACTION`)
     * for consistency with the EVM path — so it shows only transactions this wallet sent through
     * Turnkey (no receives). Turnkey's Solana activity carries only the hex unsigned transaction
     * (no recipient/amount) and no on-chain signature, so `to`/`value` are decoded from that blob
     * and the row's hash is the Turnkey status id (not an explorer-resolvable signature).
     */
    private suspend fun getSolanaTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        val (session, client) = resolveSessionAndClient()
        val caip2 = SolanaChains.caip2(chainId)
        val requestedLimit = minOf(maxOf(((limit ?: 10) + (offset ?: 0)), 1), 100)
        val activities = client.getActivities(
            TGetActivitiesBody(
                organizationId = session.organizationId,
                filterByType = listOf(V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION),
                paginationOptions = V1Pagination(limit = requestedLimit.toString())
            )
        )

        val drafts = activities.activities.mapNotNull { activity ->
            val intent = activity.intent.solSendTransactionIntent ?: return@mapNotNull null
            if (intent.caip2 != caip2) return@mapNotNull null

            val seconds = activity.createdAt.seconds.toDoubleOrNull() ?: 0.0
            val nanos = activity.createdAt.nanos.toDoubleOrNull() ?: 0.0
            SolanaActivityDraft(
                id = activity.id,
                timestampSeconds = seconds + nanos / 1_000_000_000.0,
                from = intent.signWith,
                transfer = SolanaTransactionDecoder.decode(intent.unsignedTransaction),
                sendTransactionStatusId = activity.result.solSendTransactionResult?.sendTransactionStatusId
            )
        }

        val sorted = when (order ?: RainTransactionOrder.DESC) {
            RainTransactionOrder.ASC -> drafts.sortedBy { it.timestampSeconds }
            RainTransactionOrder.DESC -> drafts.sortedByDescending { it.timestampSeconds }
        }
        val sliced = sorted
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }

        // An SPL row's recipient is a token account; resolving it to the owner's wallet needs a
        // read per row, so the page is resolved concurrently and only after slicing.
        val transactions = coroutineScope {
            sliced
                .map { draft -> async { solanaTransaction(chainId, draft) } }
                .awaitAll()
        }
        return transactions
    }

    /**
     * Renders one decoded Solana activity as a [RainTransaction]. SPL rows resolve their decimals
     * and recipient wallet from the node when the transaction itself didn't carry them; both
     * reads are best-effort, so a row always lists.
     */
    private suspend fun solanaTransaction(chainId: Int, draft: SolanaActivityDraft): RainTransaction {
        val hash = draft.sendTransactionStatusId ?: draft.id
        val timestamp = iso8601(draft.timestampSeconds)

        return when (val transfer = draft.transfer) {
            is SolanaTransactionDecoder.SplTransfer -> {
                // TransferChecked carries the decimals; the bare Transfer instruction does not.
                val decimals = transfer.decimals
                    ?: transfer.mint?.let { resolveMintDecimals(chainId, it) }
                RainTransaction(
                    hash = hash,
                    uniqueId = draft.id,
                    timestamp = timestamp,
                    from = draft.from,
                    // The recipient wallet comes from the transaction's own account-creation
                    // instruction when it has one, and from the node otherwise. Falls back to the
                    // token account when the owner cannot be read — a real address the user can
                    // look up, rather than nothing.
                    to = transfer.destinationOwner
                        ?: resolveTokenAccountOwner(chainId, transfer.destination)
                        ?: transfer.destination,
                    // Without decimals the base-unit amount cannot be scaled honestly; the raw
                    // amount stays available in the metadata.
                    value = decimals?.let {
                        BigDecimal(transfer.amount).movePointLeft(it).stripTrailingZeros()
                    },
                    // SPL symbols live in off-chain metadata the SDK does not read; the mint
                    // identifies the asset.
                    asset = null,
                    tokenAddress = transfer.mint,
                    rawValue = transfer.amount.toString(),
                    decimals = decimals,
                    category = RainTransactionCategory.Token,
                    chainId = chainId,
                    // The token accounts are not reconstructible from `to`, which holds the wallet
                    // when the owner read succeeded and the token account when it didn't.
                    metadata = RainTransaction.Metadata(
                        sourceTokenAccount = transfer.source,
                        destinationTokenAccount = transfer.destination
                    )
                )
            }

            is SolanaTransactionDecoder.NativeTransfer -> RainTransaction(
                hash = hash,
                uniqueId = draft.id,
                timestamp = timestamp,
                from = draft.from,
                to = transfer.to,
                value = if (transfer.lamports.signum() == 0) BigDecimal.ZERO else {
                    SolanaConverter.lamportsToSol(transfer.lamports).stripTrailingZeros()
                },
                asset = SolanaChains.NATIVE_CURRENCY.symbol,
                rawValue = transfer.lamports.toString(),
                decimals = SolanaConverter.SOL_DECIMALS,
                category = RainTransactionCategory.External,
                chainId = chainId
            )

            // Undecodable payload: report the activity rather than dropping it from history.
            null -> RainTransaction(
                hash = hash,
                uniqueId = draft.id,
                timestamp = timestamp,
                from = draft.from,
                asset = SolanaChains.NATIVE_CURRENCY.symbol,
                category = RainTransactionCategory.External,
                chainId = chainId
            )
        }
    }

    /** The wallet owning [tokenAccount], or null when it cannot be read. Never fatal. */
    private suspend fun resolveTokenAccountOwner(chainId: Int, tokenAccount: String): String? {
        val rpcUrl = rpcEndpoints[chainId] ?: return null
        return runCatching {
            solanaRpcClient.getTokenAccount(rpcUrl, tokenAccount)?.owner?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** The mint's decimals, or null when they cannot be read. Never fatal. */
    private suspend fun resolveMintDecimals(chainId: Int, mint: String): Int? {
        val rpcUrl = rpcEndpoints[chainId] ?: return null
        return runCatching { solanaRpcClient.getMintInfo(rpcUrl, mint)?.decimals }.getOrNull()
    }

    private data class SolanaActivityDraft(
        val id: String,
        val timestampSeconds: Double,
        val from: String,
        /** What the activity's unsigned transaction turned out to be; null if undecodable. */
        val transfer: SolanaTransactionDecoder.Transfer?,
        val sendTransactionStatusId: String?
    )

    // ---------- helpers ----------

    private fun resolveSessionAndClient(): Pair<com.turnkey.core.models.Session, TurnkeyClientProtocol> {
        val session = turnkey.session
            ?: throw RainError.TokenExpired()
        val client = turnkey.turnkeyClient
            ?: throw RainError.TokenExpired()
        return session to client
    }

    private suspend fun fetchBalances(chainId: Int, walletAddress: String): List<V1AssetBalance> {
        val (session, client) = resolveSessionAndClient()
        val response = client.getWalletAddressBalances(
            TGetWalletAddressBalancesBody(
                organizationId = session.organizationId,
                address = walletAddress,
                caip2 = caip2For(chainId)
            )
        )
        return response.balances.orEmpty()
    }

    private suspend fun buildSendTransactionBody(
        session: com.turnkey.core.models.Session,
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): TEthSendTransactionBody {
        val nonceHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_getTransactionCount",
            params = listOf(from, "pending")
        )
        val estimateGasHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_estimateGas",
            params = listOf(rpcTransactionObject(from, to, data, value))
        )
        val gasPriceHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_gasPrice",
            params = emptyList()
        )

        val nonce = decimalStringFromHex(nonceHex)
        val estimatedGas = BigInteger(
            estimateGasHex.strippingHexPrefix().ifEmpty { "0" },
            16
        ).takeIf { it > BigInteger.ZERO } ?: BigInteger.valueOf(FALLBACK_GAS_LIMIT)
        val bufferedGas = estimatedGas.add(estimatedGas.divide(BigInteger.valueOf(5)))
        val gasLimit = (if (bufferedGas == BigInteger.ZERO) estimatedGas else bufferedGas).toString()
        val gasPrice = decimalStringFromHex(gasPriceHex)

        return TEthSendTransactionBody(
            organizationId = session.organizationId,
            caip2 = ChainIdFormat.EIP155.format(chainId),
            data = data.ifEmpty { "0x" },
            from = from,
            gasLimit = gasLimit,
            maxFeePerGas = gasPrice,
            maxPriorityFeePerGas = gasPrice,
            nonce = nonce,
            sponsor = false,
            to = to,
            value = decimalStringFromHex(value)
        )
    }

    private suspend fun resolveTransactionHash(
        client: TurnkeyClientProtocol,
        organizationId: String,
        sendTransactionStatusId: String?
    ): String? {
        val statusId = sendTransactionStatusId ?: return null
        val status = client.getSendTransactionStatus(
            TGetSendTransactionStatusBody(
                organizationId = organizationId,
                sendTransactionStatusId = statusId
            )
        )
        return status.eth?.txHash
    }

    private suspend fun pollForTransactionHash(
        client: TurnkeyClientProtocol,
        organizationId: String,
        sendTransactionStatusId: String
    ): String {
        for (attempt in 0 until DEFAULT_POLLING_ATTEMPTS) {
            val status = client.getSendTransactionStatus(
                TGetSendTransactionStatusBody(
                    organizationId = organizationId,
                    sendTransactionStatusId = sendTransactionStatusId
                )
            )

            val txHash = status.eth?.txHash
            if (!txHash.isNullOrEmpty()) return txHash

            val normalized = status.txStatus.uppercase()
            val failed = normalized.contains("FAILED") ||
                normalized.contains("REJECTED") ||
                status.txError != null ||
                status.error?.message != null
            if (failed) {
                val message = status.txError
                    ?: status.error?.message
                    ?: "Turnkey transaction submission failed"
                throw RainError.ProviderError(IllegalStateException(message))
            }

            if (attempt + 1 < DEFAULT_POLLING_ATTEMPTS) {
                delay(pollingIntervalMs)
            }
        }

        // A poll timeout is not a failure: Turnkey accepted the submission and the transaction
        // may still confirm. Carrying the status id lets the host resume polling instead of
        // resending, which would risk a duplicate transfer.
        throw RainError.TransactionPending(sendTransactionStatusId)
    }

    // ---------- Solana send ----------

    private suspend fun sendSolanaNative(
        chainId: Int,
        toAddress: String,
        amountInSol: BigDecimal
    ): String {
        val from = getWalletAddress(chainId)
        val unsigned = solanaTransferComposer.composeNative(chainId, from, toAddress, amountInSol)
        return submitSolanaTransaction(chainId, from, unsigned)
    }

    /**
     * Sends SPL tokens. Composition and every preflight (mint resolution, token-account
     * derivation/creation, balance and fee checks, simulation) live in [SolanaTransferComposer];
     * this method only signs and broadcasts through Turnkey.
     */
    private suspend fun sendSolanaSplToken(
        chainId: Int,
        mintAddress: String,
        toAddress: String,
        amount: BigDecimal
    ): String {
        val from = getWalletAddress(chainId)
        val unsigned =
            solanaTransferComposer.composeSplToken(chainId, from, mintAddress, toAddress, amount)
        return submitSolanaTransaction(chainId, from, unsigned)
    }

    /**
     * Signs and broadcasts a core-composed Solana transaction (e.g. a collateral withdrawal)
     * with the Turnkey Solana account. The fee payer is always this wallet.
     */
    override suspend fun sendSolanaTransaction(
        chainId: Int,
        unsigned: UnsignedSolanaTransfer
    ): String = submitSolanaTransaction(chainId, getWalletAddress(chainId), unsigned)

    /**
     * Signs and broadcasts a composed transfer through Turnkey, then resolves the signature:
     * from the send-status response (Turnkey SDK 2.0 populates it once Included), with a chain
     * lookup as defensive fallback, and the status id as the last resort.
     */
    private suspend fun submitSolanaTransaction(
        chainId: Int,
        from: String,
        unsigned: UnsignedSolanaTransfer
    ): String {
        val rpcUrl = rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")
        val (session, client) = resolveSessionAndClient()

        // Baseline for the signature recovery below: the wallet's newest signature before this send.
        val priorSignature = runCatching { solanaRpcClient.getLatestSignature(rpcUrl, from) }.getOrNull()

        val response = client.solSendTransaction(
            TSolSendTransactionBody(
                organizationId = session.organizationId,
                unsignedTransaction = unsigned.transactionHex,
                signWith = from,
                sponsor = false,
                caip2 = SolanaChains.caip2(chainId),
                recentBlockhash = unsigned.recentBlockhash
            )
        )
        val statusId = response.result.sendTransactionStatusId
        pollForSolanaCompletion(client, session.organizationId, statusId)?.let { return it }

        // getSignaturesForAddress lags broadcast slightly, so retry briefly before falling back
        // to the status id. Only a signature that differs from the pre-send baseline can belong
        // to this send; returning the baseline would report an older transaction as this one.
        for (attempt in 0 until SOLANA_SIGNATURE_LOOKUP_ATTEMPTS) {
            val signature = solanaRpcClient.getLatestSignature(rpcUrl, from)
            if (signature != null && signature != priorSignature) return signature
            if (attempt + 1 < SOLANA_SIGNATURE_LOOKUP_ATTEMPTS) delay(pollingIntervalMs)
        }
        return statusId
    }

    /** Rejects a Solana chain id on the EVM-only entry points, which have no Solana equivalent. */
    private fun requireEvmChain(chainId: Int, operation: String) {
        if (SolanaChains.isSolanaChain(chainId)) {
            throw RainError.InvalidConfig(
                "$operation is EVM-only; use sendNativeToken/sendToken on Solana chainId=$chainId"
            )
        }
    }

    /**
     * Polls Turnkey for the terminal status of a Solana submission. Keeps
     * polling through `Broadcasted` until the signature appears or a terminal state
     * (Included/Confirmed/Finalized). Returns `solana.signature` (populated once Included on
     * Turnkey SDK 2.0), null at a terminal status without it or on timeout (caller then recovers
     * the signature from chain), and throws on explicit failure.
     */
    private suspend fun pollForSolanaCompletion(
        client: TurnkeyClientProtocol,
        organizationId: String,
        sendTransactionStatusId: String
    ): String? {
        for (attempt in 0 until DEFAULT_POLLING_ATTEMPTS) {
            val status = client.getSendTransactionStatus(
                TGetSendTransactionStatusBody(
                    organizationId = organizationId,
                    sendTransactionStatusId = sendTransactionStatusId
                )
            )

            val normalized = status.txStatus.uppercase()
            val failed = status.txError != null ||
                status.error?.message != null ||
                normalized.contains("FAILED") ||
                normalized.contains("REJECTED")
            if (failed) {
                val message = status.txError
                    ?: status.error?.message
                    ?: "Turnkey Solana transaction submission failed"
                throw RainError.ProviderError(IllegalStateException(message))
            }

            // Turnkey SDK 2.0 populates solana.signature once the tx is Included.
            status.solana?.signature?.takeIf { it.isNotEmpty() }?.let { return it }

            // Terminal status but no signature yet: stop; the caller recovers it from chain.
            val terminal = normalized.contains("INCLUDED") ||
                normalized.contains("CONFIRMED") ||
                normalized.contains("FINALIZED") ||
                normalized.contains("MINED")
            if (terminal) return null

            if (attempt + 1 < DEFAULT_POLLING_ATTEMPTS) {
                delay(pollingIntervalMs)
            }
        }
        return null
    }

    // ---------- RPC ----------

    /**
     * Issues a JSON-RPC call against the chain's configured RPC URL, returning the hex
     * `result` field. Promotes [RainError.InvalidRpcUrl] to [RainError.InvalidConfig] so
     * the caller gets the chain ID alongside the bad URL.
     */
    private suspend fun rpcCallForHex(
        chainId: Int,
        method: String,
        params: List<Any>
    ): String {
        val rpcUrl = rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")
        return try {
            jsonRpcClient.callForHexResult(rpcUrl, method, params)
        } catch (e: RainError.InvalidRpcUrl) {
            throw RainError.InvalidConfig("Invalid RPC URL for chainId=$chainId: $rpcUrl")
        }
    }

    private fun rpcTransactionObject(
        from: String,
        to: String,
        data: String,
        value: String
    ): Map<String, String> {
        // Native transfers carry no calldata. Omit the "data" field entirely (rather than
        // sending "0x" or "") so eth_estimateGas matches the request shape RPC nodes expect
        // for a value-only transfer.
        val tx = mutableMapOf("from" to from, "to" to to, "value" to value)
        if (data.isNotEmpty() && data != "0x") tx["data"] = data
        return tx
    }

    // ---------- formatting ----------

    private fun isNativeAsset(balance: V1AssetBalance, caip2: String): Boolean {
        val caip19 = balance.caip19 ?: return false
        return caip19.startsWith("$caip2/slip44:")
    }

    private fun tokenAddressFromCaip19(caip19: String, caip2: String): String? {
        // EVM tokens use the `erc20` asset namespace; Solana SPL tokens use `token`.
        val prefixes = listOf("$caip2/erc20:", "$caip2/token:")
        if (prefixes.none { caip19.startsWith(it) }) return null
        return caip19.substringAfterLast(":", "").takeIf { it.isNotEmpty() }
    }

    private fun chainIdFromCaip2(caip2: String): Int {
        return caip2.substringAfterLast(":", "").toIntOrNull() ?: 0
    }

    /**
     * Scales a base-unit amount down without going through [Double] — a wei value above 2^53
     * would otherwise be inexact. Null when the amount is not a number.
     */
    private fun scaledDecimal(balance: String?, decimals: Int): BigDecimal? {
        if (balance.isNullOrEmpty()) return BigDecimal.ZERO
        return runCatching { BigDecimal(balance).movePointLeft(decimals).stripTrailingZeros() }
            .getOrNull()
    }

    private fun decimalStringFromHex(hex: String): String {
        val cleaned = hex.strippingHexPrefix().ifEmpty { "0" }
        return BigInteger(cleaned, 16).toString()
    }

    private fun ethereumSignatureHex(signature: V1SignRawPayloadResult): String {
        val r = normalizeHexComponent(signature.r, 64)
        val s = normalizeHexComponent(signature.s, 64)
        val v = String.format(Locale.ROOT, "%02x", normalizedRecoveryId(signature.v))
        return "0x$r$s$v"
    }

    private fun normalizeHexComponent(value: String, length: Int): String {
        // Strip every "0x" occurrence (not just a leading prefix) — Turnkey occasionally
        // returns components like "0x...0x..." that need full normalization before padding.
        val clean = value.lowercase().replace("0x", "")
        return if (clean.length >= length) clean.takeLast(length)
        else clean.padStart(length, '0')
    }

    private fun normalizedRecoveryId(value: String): Int {
        val clean = value.lowercase()
        val parsed = when {
            clean.startsWith("0x") -> clean.removePrefix("0x").toIntOrNull(16)
            else -> clean.toIntOrNull() ?: clean.toIntOrNull(16)
        } ?: return 27
        return if (parsed >= 27) parsed else parsed + 27
    }

    /** Second-precision UTC Zulu ISO-8601, e.g. `2026-07-26T12:34:56Z`. */
    private fun iso8601(seconds: Double): String =
        Instant.ofEpochMilli((seconds * 1000).toLong())
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()
}
