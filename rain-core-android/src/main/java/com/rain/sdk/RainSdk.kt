package com.rain.sdk

import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.core.ConfigManager
import com.rain.sdk.internal.core.RainSdkManager
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.rainapi.RainApiConfigStore
import com.rain.sdk.internal.network.rainapi.RainApiService
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainEIP712Message
import com.rain.sdk.models.RainApiEnvironment
import com.rain.sdk.models.NetworkConfig
import com.rain.sdk.models.RainCollateralContract
import com.rain.sdk.models.RainTransactionParameters
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.models.RainWithdrawAddresses
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.RainProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Entry point for the modular Rain SDK.
 *
 * Built via [builder]; the host registers exactly the provider adapters it ships
 * ([com.rain.sdk.provider.RainProvider] descriptors such as `rain-portal-android`'s `PortalProvider` or
 * core's `TurnkeyProvider`) and the chains it talks to. Nothing here references a concrete vendor
 * type — a provider whose module isn't on the classpath simply can't be registered.
 *
 * ```kotlin
 * val rain = RainSdk.builder()
 *     .rpcEndpoints(mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com"))
 *     .register(PortalProvider(PortalConfig(sessionToken)))
 *     .build()
 *
 * // resolve by id…
 * val client = rain.provider(ProviderId.PORTAL)
 * // …or by capability
 * val exporter = rain.first { Capability.EXPORT in it.capabilities }
 * ```
 *
 * The multi-provider case is the design point; a single-provider app is just the trivial `N = 1`
 * instance — register one adapter and never call [provider] with a second.
 */
class RainSdk private constructor(
    private val rpcEndpoints: Map<Int, String>,
    private val registered: Map<ProviderId, RainProvider>,
    private val seedTokens: List<TokenInfo>,
    rainApiEnvironment: RainApiEnvironment,
    initialRainApiCredentials: Pair<String, String>?,
) {
    private val mutex = Mutex()

    // Concurrent so [reset] can evict safely without holding the resolution mutex.
    private val clients = ConcurrentHashMap<ProviderId, RainClient>()

    private val rainApiConfig = RainApiConfigStore(baseUrl = rainApiEnvironment.baseUrl)

    @Volatile
    private var rainApiService: RainApiService? = null

    /**
     * Withdrawal-building primitives bound to this instance's endpoints. Instance-scoped, so two
     * SDKs configured differently never read each other's chain configuration.
     */
    private val builderImpl = RainTransactionBuilderImpl(rpcEndpoints)

    init {
        // Fail fast on a bad URL / chain id, before anything is resolved.
        ConfigManager().validateRpcEndpoints(rpcEndpoints)
        initialRainApiCredentials?.let { (apiKey, userId) ->
            rainApiConfig.setCredentials(apiKey, userId)
        }
    }

    /**
     * Shared, vendor-free infrastructure handed to every provider. Built exactly once, after
     * endpoint validation, so clients and the Rain API service always see one [TokenMetadataStore].
     */
    private val sharedContext: ProviderContext = run {
        val evm = EvmChainReader(rpcEndpoints = rpcEndpoints)
        ProviderContext(
            rpcEndpoints = rpcEndpoints,
            tokenStore = TokenMetadataStore(chainReader = evm, seedTokens = seedTokens),
            evmChainReader = evm,
            solanaSupport = SolanaSupport(rpcEndpoints),
        )
    }

    /** Ids of every provider the host registered. */
    val providerIds: Set<ProviderId> get() = registered.keys

    /** The capability-advertising descriptors the host registered, for capability resolution. */
    val providers: Collection<RainProvider> get() = registered.values

    /**
     * Wallet-agnostic transaction-building helpers (EIP-712 typed-data + withdraw calldata).
     */
    @Deprecated(
        message = "The builder methods are now on RainSdk itself. Call rain.buildEIP712Message(...) " +
            "/ rain.buildWithdrawTransactionData(...) directly.",
        replaceWith = ReplaceWith("this")
    )
    val transactionBuilder: RainTransactionBuilder get() = builderImpl

    // --- Wallet-agnostic transaction building ---------------------------------------------
    // These need no wallet provider — only the configured RPC endpoints — so they're available
    // straight off the SDK without resolving a [provider].

    /**
     * Reads the collateral's current admin nonce — the value [buildEIP712Message] binds when
     * [buildEIP712Message]'s `nonce` is omitted.
     */
    @Throws(RainError::class)
    suspend fun getLatestNonce(chainId: Int, proxyAddress: String): BigInteger =
        builderImpl.getLatestNonce(chainId, proxyAddress)

    /**
     * Whether [walletAddress] is an admin of the collateral contract at [proxyAddress].
     *
     * @return the contract's answer, or `null` if the check could not be performed (RPC failure,
     *   or a collateral that exposes no `isAdmin`). Treat `null` as unknown and proceed, never as
     *   "not authorized".
     */
    suspend fun isCollateralAdmin(
        chainId: Int,
        proxyAddress: String,
        walletAddress: String,
    ): Boolean? = builderImpl.isCollateralAdmin(chainId, proxyAddress, walletAddress)

    /**
     * Builds the EIP-712 message the wallet signs to authorize a withdrawal, along with the salt
     * bound into it. Pass a null [nonce] to read the collateral's current nonce on chain.
     */
    @Throws(RainError::class)
    suspend fun buildEIP712Message(
        chainId: Int,
        walletAddress: String,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        nonce: BigInteger? = null,
    ): RainEIP712Message = builderImpl.buildEIP712Message(
        chainId = chainId,
        walletAddress = walletAddress,
        addresses = addresses,
        amount = amount,
        decimals = decimals,
        nonce = nonce,
    )

    /**
     * ABI-encodes the `withdrawAsset` call for the collateral controller. Pure encoding — no RPC,
     * so it needs no chain id.
     *
     * @param executorSignature Rain's authorization, from [fetchAdminSignature].
     * @param walletSalt The salt from [RainEIP712Message.salt], unchanged.
     * @param walletSignature The wallet's hex signature over [RainEIP712Message.message].
     */
    @Throws(RainError::class)
    fun buildWithdrawTransactionData(
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        executorSignature: RainAdminSignature,
        walletSalt: ByteArray,
        walletSignature: String,
    ): String = builderImpl.buildWithdrawTransactionData(
        addresses = addresses,
        amount = amount,
        decimals = decimals,
        executorSignature = executorSignature,
        walletSalt = walletSalt,
        walletSignature = walletSignature,
    )

    /**
     * Composes Rain-owned transaction parameters. Rain-owned so the public surface does not leak
     * Portal/Turnkey types. Pure composition — no wallet provider and no RPC involved.
     *
     * @param walletAddress Address of the sender wallet.
     * @param contractAddress Target smart contract address.
     * @param transactionData Hex-encoded calldata.
     */
    fun buildTransactionParameters(
        walletAddress: String,
        contractAddress: String,
        transactionData: String,
    ): RainTransactionParameters = RainTransactionParameters(
        from = walletAddress,
        to = contractAddress,
        value = "0x0",
        data = transactionData,
    )

    /**
     * Resolves the [RainClient] backed by the provider registered under [id], materializing the
     * vendor wallet on first access and caching it thereafter.
     *
     * @throws RainError.ProviderNotRegistered if no provider was registered for [id].
     */
    suspend fun provider(id: ProviderId): RainClient = mutex.withLock {
        clients[id]?.let { return it }
        val descriptor = registered[id]
            ?: throw RainError.ProviderNotRegistered("no provider registered for id '${id.value}'")
        val context = sharedContext
        val walletProvider = descriptor.create(context)
        RainSdkManager(
            walletProvider = walletProvider,
            rpcEndpoints = rpcEndpoints,
            tokenStore = context.tokenStore,
            transactionBuilder = builderImpl,
            providerId = descriptor.id,
            capabilities = descriptor.capabilities,
        ).also {
            clients[id] = it
            Timber.d("Rain SDK: Resolved provider '${id.value}'")
        }
    }

    /**
     * Resolves the first registered provider matching [predicate] (e.g. by capability) and returns
     * its [RainClient].
     *
     * @throws RainError.ProviderNotRegistered if no registered provider matches.
     */
    suspend fun first(predicate: (RainProvider) -> Boolean): RainClient {
        val match = registered.values.firstOrNull(predicate)
            ?: throw RainError.ProviderNotRegistered(
                "no registered provider matches the requested capability"
            )
        return provider(match.id)
    }

    // --- Rain API (issuing) --------------------------------------------------------------

    /** True once an Api-Key and userId have been supplied (builder or [configureRainApi]). */
    val isRainApiConfigured: Boolean get() = rainApiConfig.isConfigured

    /**
     * Sets or replaces the Rain program Api-Key and userId at runtime. The cached client
     * session token is discarded lazily — the next API call re-mints against the new pair.
     * The SDK never persists these values.
     */
    fun configureRainApi(apiKey: String, userId: String) {
        rainApiConfig.setCredentials(apiKey, userId)
    }

    /**
     * Fetches the user's collateral contracts (`GET /v1/issuing/users/{userId}/contracts`).
     *
     * Token `name`/`symbol`/`decimals` are enriched from the SDK token store (registry,
     * host-registered tokens, or an on-chain read) — best-effort, a failed lookup leaves them
     * null. Needs no wallet provider, only the configured Api-Key/userId and RPC endpoints.
     *
     * @throws RainError.ApiNotConfigured when no credentials were supplied.
     */
    @Throws(RainError::class)
    suspend fun fetchCollateralContracts(): List<RainCollateralContract> =
        rainApi().fetchCollateralContracts()

    /**
     * Convenience for the common single-contract case: the first collateral contract.
     *
     * @throws RainError.NoCollateralContracts when the user has none.
     */
    @Throws(RainError::class)
    suspend fun fetchCollateralContract(): RainCollateralContract =
        fetchCollateralContracts().firstOrNull() ?: throw RainError.NoCollateralContracts()

    /**
     * Fetches the admin withdrawal signature
     * (`GET /v1/issuing/users/{userId}/signatures/withdrawals`) that authorizes a
     * [RainClient.withdrawCollateral] call.
     *
     * @param amountBaseUnits Withdrawal amount in the token's base units.
     * @param adminAddress One of the contract's [RainCollateralContract.adminAddresses].
     * @throws RainError.SignatureNotReady when Rain has not produced the signature yet — retry
     *   after the carried `retryAfter` seconds.
     */
    @Throws(RainError::class)
    suspend fun fetchAdminSignature(
        chainId: Int,
        tokenAddress: String,
        amountBaseUnits: BigInteger,
        adminAddress: String,
        recipientAddress: String,
        isAmountNative: Boolean = true,
    ): RainAdminSignature = rainApi().fetchAdminSignature(
        chainId = chainId,
        tokenAddress = tokenAddress,
        amountBaseUnits = amountBaseUnits,
        adminAddress = adminAddress,
        recipientAddress = recipientAddress,
        isAmountNative = isAmountNative,
    )

    private fun rainApi(): RainApiService = synchronized(this) {
        rainApiService?.let { return@synchronized it }
        RainApiService(
            configStore = rainApiConfig,
            tokenStore = sharedContext.tokenStore,
            chainReader = sharedContext.evmChainReader,
        ).also { rainApiService = it }
    }

    /**
     * Tears down all resolved clients and clears the Rain API credentials. Idempotent.
     *
     * The chain configuration is immutable state fixed at [Builder.build], so this instance stays
     * usable: the next [provider] / [first] call re-resolves from scratch. Build a new [RainSdk]
     * via [builder] to change configuration.
     */
    fun reset() {
        clients.values.forEach { runCatching { it.reset() } }
        clients.clear()
        rainApiConfig.clear()
        rainApiService = null
        Timber.d("Rain SDK: Reset (resolved clients evicted; Rain API credentials cleared)")
    }

    companion object {
        /** Starts a new [Builder]. */
        fun builder(): Builder = Builder()
    }

    /**
     * Assembles a [RainSdk]. Module dependencies decide which providers can be registered — the
     * builder never names a vendor SDK itself.
     */
    class Builder internal constructor() {
        private val providers = LinkedHashMap<ProviderId, RainProvider>()
        private var rpcEndpoints: Map<Int, String> = emptyMap()
        private val seedTokens = mutableListOf<TokenInfo>()
        private var rainApiEnvironment: RainApiEnvironment = RainApiEnvironment.Dev
        private var rainApiCredentials: Pair<String, String>? = null

        /** Sets the `chainId → RPC URL` map every provider shares. Required. */
        fun rpcEndpoints(endpoints: Map<Int, String>): Builder = apply {
            rpcEndpoints = endpoints
        }

        /**
         * Sets the chains every provider shares, as [NetworkConfig] values. Replaces any
         * previously set endpoints rather than appending. A later duplicate [NetworkConfig.chainId]
         * wins.
         */
        fun rpcEndpoints(configs: List<NetworkConfig>): Builder = apply {
            rpcEndpoints = configs.associate { it.chainId to it.rpcUrl }
        }

        /** Registers a provider adapter. Re-registering the same id replaces the prior one. */
        fun register(provider: RainProvider): Builder = apply {
            providers[provider.id] = provider
        }

        /** Seeds the shared token store with extra token metadata. */
        fun registerTokens(tokens: List<TokenInfo>): Builder = apply {
            seedTokens += tokens
        }

        /** Selects the Rain issuing API environment. Defaults to [RainApiEnvironment.Dev]. */
        fun rainApiEnvironment(environment: RainApiEnvironment): Builder = apply {
            rainApiEnvironment = environment
        }

        /**
         * Optionally supplies the Rain program Api-Key and userId at build time — same effect
         * as calling [RainSdk.configureRainApi] on the built instance.
         */
        fun rainApiCredentials(apiKey: String, userId: String): Builder = apply {
            rainApiCredentials = apiKey to userId
        }

        /**
         * Zero registered providers is allowed: the SDK is then wallet-agnostic,
         * exposing [RainSdk.transactionBuilder] and the Rain API methods; resolving a
         * [RainSdk.provider] still throws [RainError.ProviderNotRegistered] until one is registered.
         *
         * @throws RainError.InvalidConfig if no RPC endpoints were configured, or the Rain API
         *   base URL doesn't parse.
         */
        fun build(): RainSdk {
            if (rpcEndpoints.isEmpty()) {
                throw RainError.InvalidConfig("At least one RPC endpoint is required")
            }
            if (rainApiEnvironment.baseUrl.toHttpUrlOrNull() == null) {
                throw RainError.InvalidConfig(
                    "Invalid Rain API base URL: ${rainApiEnvironment.baseUrl}"
                )
            }
            return RainSdk(
                rpcEndpoints = rpcEndpoints.toMap(),
                registered = providers.toMap(),
                seedTokens = seedTokens.toList(),
                rainApiEnvironment = rainApiEnvironment,
                initialRainApiCredentials = rainApiCredentials,
            )
        }
    }
}
