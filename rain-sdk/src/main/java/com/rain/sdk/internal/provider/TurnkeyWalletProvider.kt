package com.rain.sdk.internal.provider

import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.JsonRpcClient
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.solana.SolanaConverter
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.internal.solana.SolanaTransactionBuilder
import com.rain.sdk.internal.solana.SolanaTransactionDecoder
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.internal.utils.ChainIdFormat
import com.rain.sdk.internal.utils.strippingHexPrefix
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function as Web3jFunction
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    tokenStore: TokenMetadataStore? = null
) : WalletProvider {

    private val jsonRpcClient: JsonRpcClient = jsonRpcClient
    private val chainReader: ChainReader = chainReader
        ?: EvmChainReader(rpcEndpoints = rpcEndpoints, jsonRpcClient = jsonRpcClient)

    private val solanaRpcClient: SolanaRpcClient =
        solanaRpcClient ?: SolanaRpcClient(jsonRpcClient)
    private val solanaChainReader: ChainReader = solanaChainReader
        ?: SolanaChainReader(rpcEndpoints = rpcEndpoints, solanaRpcClient = this.solanaRpcClient)

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

    /** True when Turnkey's `get-balances` API covers [chainId]. */
    private fun usesTurnkeyForBalances(chainId: Int): Boolean =
        chainId in RainConstants.TURNKEY_SUPPORTED_CHAINS

    // ---------- address ----------

    override suspend fun getAddress(): String {
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
    override suspend fun getAddress(chainId: Int): String =
        if (SolanaChains.isSolanaChain(chainId)) getSolanaAddress() else getAddress()

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
        amountInEth: Double
    ): String {
        if (SolanaChains.isSolanaChain(chainId)) {
            return sendSolanaNative(chainId, toAddress, amountInEth)
        }
        val from = getAddress(chainId)
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
        amount: Double,
        decimals: Int
    ): String {
        if (SolanaChains.isSolanaChain(chainId)) {
            throw RainError.InvalidConfig("SPL token transfers are not supported on Solana chainId=$chainId")
        }
        val from = getAddress(chainId)
        val tokenAmount = amount.toBigDecimal()
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigInteger()
        val function = Web3jFunction(
            "transfer",
            listOf(Address(toAddress), Uint256(tokenAmount)),
            emptyList<TypeReference<*>>()
        )
        val data = FunctionEncoder.encode(function)
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
    ): Double {
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

        val gasLimit = BigInteger(estimateHex.strippingHexPrefix().ifEmpty { "0" }, 16)
        val gasPrice = BigInteger(gasPriceHex.strippingHexPrefix().ifEmpty { "0" }, 16)
        return EthereumConverter.convertWeiToEth(gasLimit.multiply(gasPrice))
    }

    // ---------- balances ----------

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        val walletAddress = getAddress(chainId)

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
                    nativeBalance(chainId, balances, ChainIdFormat.EIP155.format(chainId))
                }
            }
        }
    }

    override suspend fun getBalances(chainId: Int): List<Balance> {
        val walletAddress = getAddress(chainId)

        if (!usesTurnkeyForBalances(chainId)) {
            val tokens = tokenStore.registeredTokens(chainId)
            val all = chainReaderFor(chainId).getBalances(chainId, walletAddress, tokens)
            return all.filter { balance ->
                balance.token is Token.Native || balance.rawAmount > BigInteger.ZERO
            }
        }

        val balances = fetchBalances(chainId, walletAddress)
        val caip2 = ChainIdFormat.EIP155.format(chainId)

        val output = mutableListOf(nativeBalance(chainId, balances, caip2))
        for (balance in balances) {
            val caip19 = balance.caip19 ?: continue
            val tokenAddress = tokenAddressFromCaip19(caip19, caip2) ?: continue
            val raw = runCatching { BigInteger(balance.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
            if (raw <= BigInteger.ZERO) continue
            val info = tokenStore.tokenInfo(chainId, tokenAddress)
            output += Balance(
                token = Token.Contract(tokenAddress),
                chainId = chainId,
                rawAmount = raw,
                decimals = balance.decimals?.toInt() ?: info.decimals,
                symbol = balance.symbol ?: info.symbol,
                name = balance.name ?: info.name
            )
        }
        return output
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
    ): RainTransactionResult {
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
                blockNumber = null,
                blockTimestamp = iso8601(draft.timestampSeconds),
                from = draft.from,
                to = draft.to,
                value = decimalStringToDouble(draft.value, DEFAULT_NATIVE_DECIMALS).toString(),
                gas = null,
                gasPrice = null,
                chainId = draft.chainId.toString(),
                symbol = null,
                tokenAddress = draft.to.takeIf { !draft.data.isNullOrEmpty() && draft.data != "0x" },
                metadata = null
            )
        }
        return RainTransactionResult(transactions = transactions)
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
    ): RainTransactionResult {
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
            val transfer = SolanaTransactionDecoder.decodeTransfer(intent.unsignedTransaction)
            SolanaActivityDraft(
                id = activity.id,
                timestampSeconds = seconds + nanos / 1_000_000_000.0,
                from = intent.signWith,
                to = transfer?.to,
                lamports = transfer?.lamports,
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

        val symbol = SolanaChains.NATIVE_CURRENCY.symbol
        val transactions = sliced.map { draft ->
            val value = draft.lamports?.let { lamports ->
                if (lamports == 0L) "0"
                else SolanaConverter.lamportsToSol(BigInteger.valueOf(lamports)).stripTrailingZeros().toPlainString()
            }
            RainTransaction(
                hash = draft.sendTransactionStatusId ?: draft.id,
                blockNumber = null,
                blockTimestamp = iso8601(draft.timestampSeconds),
                from = draft.from,
                to = draft.to,
                value = value,
                gas = null,
                gasPrice = null,
                chainId = chainId.toString(),
                symbol = symbol,
                tokenAddress = null,
                metadata = null
            )
        }
        return RainTransactionResult(transactions = transactions)
    }

    private data class SolanaActivityDraft(
        val id: String,
        val timestampSeconds: Double,
        val from: String,
        val to: String?,
        val lamports: Long?,
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
                caip2 = ChainIdFormat.EIP155.format(chainId)
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
        throw RainError.InternalError("Turnkey transaction status polling timed out")
    }

    // ---------- Solana send ----------

    private suspend fun sendSolanaNative(
        chainId: Int,
        toAddress: String,
        amountInSol: Double
    ): String {
        val from = getAddress(chainId)
        val rpcUrl = rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")
        val (session, client) = resolveSessionAndClient()

        val blockhash = solanaRpcClient.getLatestBlockhash(rpcUrl)
        val lamports = SolanaConverter.solToLamports(amountInSol)
        val unsignedTransaction = SolanaTransactionBuilder.buildTransferHex(
            fromAddress = from,
            toAddress = toAddress,
            lamports = lamports,
            recentBlockhash = blockhash
        )

        val response = client.solSendTransaction(
            TSolSendTransactionBody(
                organizationId = session.organizationId,
                unsignedTransaction = unsignedTransaction,
                signWith = from,
                sponsor = false,
                caip2 = SolanaChains.caip2(chainId),
                recentBlockhash = blockhash
            )
        )
        val statusId = response.result.sendTransactionStatusId
        pollForSolanaCompletion(client, session.organizationId, statusId)

        // Turnkey's send-status response carries no Solana signature; recover the signature of
        // the just-submitted transfer from the chain so callers get an explorer-usable hash.
        // getSignaturesForAddress lags broadcast slightly, so retry briefly before falling back
        // to the status id.
        for (attempt in 0 until SOLANA_SIGNATURE_LOOKUP_ATTEMPTS) {
            val signature = solanaRpcClient.getLatestSignature(rpcUrl, from)
            if (signature != null) return signature
            if (attempt + 1 < SOLANA_SIGNATURE_LOOKUP_ATTEMPTS) delay(pollingIntervalMs)
        }
        return statusId
    }

    /**
     * Polls Turnkey for the terminal status of a Solana submission. Throws on explicit
     * failure/rejection; returns on a recognized success status, or once attempts are exhausted
     * (the submission was already accepted, so the caller reads the signature from chain rather
     * than failing a transfer that most likely landed).
     */
    private suspend fun pollForSolanaCompletion(
        client: TurnkeyClientProtocol,
        organizationId: String,
        sendTransactionStatusId: String
    ) {
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

            val succeeded = normalized.contains("CONFIRMED") ||
                normalized.contains("FINALIZED") ||
                normalized.contains("INCLUDED") ||
                normalized.contains("SUCCESS") ||
                normalized.contains("COMPLETE") ||
                normalized.contains("BROADCAST") ||
                normalized.contains("MINED")
            if (succeeded) return

            if (attempt + 1 < DEFAULT_POLLING_ATTEMPTS) {
                delay(pollingIntervalMs)
            }
        }
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
        if (!caip19.startsWith("$caip2/erc20:")) return null
        return caip19.substringAfterLast(":", "").takeIf { it.isNotEmpty() }
    }

    private fun chainIdFromCaip2(caip2: String): Int {
        return caip2.substringAfterLast(":", "").toIntOrNull() ?: 0
    }

    private fun decimalStringToDouble(balance: String?, decimals: Int): Double {
        if (balance.isNullOrEmpty()) return 0.0
        return BigDecimal(balance).movePointLeft(decimals).toDouble()
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

    private fun iso8601(seconds: Double): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date((seconds * 1000).toLong()))
    }
}
