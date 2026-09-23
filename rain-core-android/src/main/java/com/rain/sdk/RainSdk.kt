package com.rain.sdk

import com.rain.sdk.error.RainError
import com.rain.sdk.error.noRpcEndpointConfigured
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.core.ConfigManager
import com.rain.sdk.internal.core.RainSdkManager
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenInfoValidation
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.internal.utils.isValidEthereumAddress
import com.rain.sdk.internal.utils.isZeroAddress
import com.rain.sdk.models.NetworkConfig
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainEIP712Message
import com.rain.sdk.models.RainTransactionParameters
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for the modular Rain SDK.
 *
 * Built via [builder]; the host registers exactly the provider adapters it ships
 * (each a [com.rain.sdk.provider.ProviderDescriptor], such as `rain-portal-android`'s `PortalProvider`
 * or `rain-turnkey-android`'s `TurnkeyProvider`) and the chains it talks to. Nothing here references a concrete vendor
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
    private val registered: Map<ProviderId, ProviderDescriptor>,
    private val seedTokens: List<TokenInfo>,
    private val authPullConfig: RainAuthPullConfig?,
) {
    private val mutex = Mutex()

    private val errorMapper = ErrorMapper()

    // Concurrent so [reset] can evict safely without holding the resolution mutex.
    private val clients = ConcurrentHashMap<ProviderId, RainClient>()

    /**
     * Auth Pull targets for this instance, handed to every resolved client so an approval cannot
     * target another environment's chains, another token, or another spender.
     *
     * Narrowed to chains that actually have an RPC endpoint: the allowance read and the approval
     * both go out over [rpcEndpoints], so a configured chain with no endpoint could never work.
     */
    private val authPullTokenAddresses: Map<Int, String> =
        authPullConfig?.tokenAddresses?.filterKeys { it in rpcEndpoints }.orEmpty()

    /**
     * The chains Auth Pull is actually enabled on for *this* instance: the configured
     * [RainAuthPullConfig]'s chains intersected with the chains that have an RPC endpoint. Empty
     * when no [Builder.authPullConfig] was supplied.
     *
     * This, not the static [RainAuthPullChains.SANDBOX] / [RainAuthPullChains.PRODUCTION] sets, is
     * what the approval guard enforces. Gate host UI on it: the static sets answer for an
     * environment, this answers for the SDK the host built, and the two differ whenever a config is
     * narrower than its environment or an RPC endpoint is missing.
     */
    val authPullChainIds: Set<Int> = authPullTokenAddresses.keys.toSet()

    @Volatile
    private var closed = false

    /**
     * Withdrawal-building primitives bound to this instance's endpoints. Instance-scoped, so two
     * SDKs configured differently never read each other's chain configuration.
     */
    private val builderImpl = RainTransactionBuilderImpl(rpcEndpoints)

    init {
        // Fail fast on a bad URL / chain id, before anything is resolved.
        ConfigManager().validateRpcEndpoints(rpcEndpoints)
    }

    /**
     * Shared, vendor-free infrastructure handed to every provider. Built exactly once, after
     * endpoint validation, so every client and [tokenMetadata] see one [TokenMetadataStore].
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
    val descriptors: Collection<ProviderDescriptor> get() = registered.values

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
     * @param executorSignature Rain's authorization for this withdrawal, obtained by the host from the Rain API,
     *   `GET /v1/issuing/users/{userId}/signatures/withdrawals`.
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
        if (closed) throw RainError.SdkNotInitialized()
        clients[id]?.let { return it }
        val descriptor = registered[id]
            ?: throw RainError.ProviderNotRegistered("no provider registered for id '${id.value}'")
        val context = sharedContext
        // Init-time vendor failures (a rejected session token, most often) go through the same
        // error contract as every later call; a raw vendor exception here would bypass it exactly
        // when credentials tend to be wrong.
        val walletProvider = try {
            descriptor.create(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError) {
            throw e
        } catch (e: Exception) {
            throw errorMapper.mapProviderInitError(e)
        }
        RainSdkManager(
            walletProvider = walletProvider,
            rpcEndpoints = rpcEndpoints,
            tokenStore = context.tokenStore,
            transactionBuilder = builderImpl,
            providerId = descriptor.id,
            capabilities = descriptor.capabilities,
            chainReader = sharedContext.evmChainReader,
            authPullChainIds = authPullChainIds,
            authPullOperator = authPullConfig?.operatorAddress,
            authPullTokenAddresses = authPullTokenAddresses,
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
    suspend fun first(predicate: (ProviderDescriptor) -> Boolean): RainClient {
        val match = registered.values.firstOrNull(predicate)
            ?: throw RainError.ProviderNotRegistered(
                "no registered provider matches the requested capability"
            )
        return provider(match.id)
    }

    // --- Token metadata ------------------------------------------------------------------

    /**
     * Metadata (symbol, name, decimals) for a contract token known only by address, such as the
     * tokens listed in a collateral contract. Resolution order: the built-in registry,
     * host-registered tokens, then on-chain `decimals()` / `symbol()` / `name()` reads over the
     * configured RPC endpoint, cached once decimals resolve. Needs no wallet provider.
     *
     * Returns `null` when decimals could not be established (unknown token, failed read, RPC
     * unreachable), never a guessed default, since callers scale withdrawal and approval amounts
     * with it. `symbol` and `name` inside a non-null result may still be null. Solana chains
     * resolve from the registry and host-registered tokens only. A `null` result is not cached, so a
     * later call reads the chain again.
     *
     * @param chainId Numeric chain ID the token lives on; it must have a configured RPC endpoint.
     * @param address Token contract address, or the SPL mint on Solana chains.
     * @return The token's metadata, or `null` when its decimals could not be established.
     * @throws RainError.SdkNotInitialized after [close].
     * @throws RainError.InvalidConfig when no RPC endpoint was configured for [chainId], or when
     *   [address] is malformed for its chain family: on EVM chains `0x` followed by 40 hex characters
     *   with a correct EIP-55 checksum when mixed-case, on Solana chains base58 decoding to 32 bytes.
     *   Both checks assert the host's configuration, not whether this lookup would need the network:
     *   a chain the SDK was not built with is a configuration error even for a registry token. Also
     *   when the chain reports `decimals()` outside 0..77, a token no money path can scale by; nothing
     *   is cached then, and the same refusal meets every SDK path that needs the value.
     */
    @Throws(RainError::class)
    suspend fun tokenMetadata(chainId: Int, address: String): TokenInfo? {
        if (closed) throw RainError.SdkNotInitialized()
        if (chainId !in rpcEndpoints) throw noRpcEndpointConfigured(chainId)
        TokenInfoValidation.requireValidAddress(chainId, address)
        return sharedContext.tokenStore.tokenInfoOrNull(chainId, address)
    }

    /**
     * Registers token metadata after [Builder.build], without a resolved provider. The entries are
     * stored before this returns, so a [tokenMetadata] call that follows sees them, and the store is
     * shared, so every resolved client sees them too. Re-registering a host-added address replaces
     * its entry; a built-in registry token cannot be overridden.
     *
     * The same checks run on [Builder.registerTokens] seeds at [Builder.build] and on
     * [RainClient.registerTokens], which stores into the same shared store the same way. The chain id
     * is not checked against the configured endpoints; a later [tokenMetadata] for a chain the SDK
     * was not built with throws regardless.
     *
     * @param tokens Tokens to add to the shared token store; an empty list is a no-op.
     * @throws RainError.SdkNotInitialized after [close].
     * @throws RainError.InvalidConfig when an entry's address is malformed for its chain family (EVM:
     *   `0x` followed by 40 hex characters with a correct EIP-55 checksum when mixed-case; Solana:
     *   base58 decoding to 32 bytes), or its `decimals` lies outside 0..77. The whole list is
     *   validated first, so nothing is registered.
     */
    @Throws(RainError::class)
    suspend fun registerTokens(tokens: List<TokenInfo>) {
        if (closed) throw RainError.SdkNotInitialized()
        if (tokens.isEmpty()) return
        TokenInfoValidation.requireValid(tokens)
        sharedContext.tokenStore.register(tokens)
    }

    /**
     * Evicts the resolved clients. Idempotent.
     *
     * The chain configuration is immutable state fixed at [Builder.build], so this instance stays
     * usable: the next [provider] / [first] call re-resolves from scratch. Build a new [RainSdk]
     * via [builder] to change configuration.
     *
     * This deliberately leaves the registered providers running — their vendor clients and
     * session watchers survive, so a re-resolve is cheap. On logout, or whenever this instance
     * is being discarded, call [close] instead: reset alone leaves session watchers observing
     * the process-wide vendor singletons and still able to fire `onSessionExpired`.
     */
    fun reset() {
        clients.values.forEach { runCatching { it.reset() } }
        clients.clear()
        Timber.d("Rain SDK: Reset (resolved clients evicted)")
    }

    /**
     * Full teardown: [reset], then closes every registered provider so their vendor clients and
     * session watchers stop and their `onSessionExpired` hooks can never fire again. Idempotent.
     *
     * Terminal, unlike [reset]: [provider], [first], [tokenMetadata] and [registerTokens] throw
     * [RainError.SdkNotInitialized] afterwards. Build a new [RainSdk] via [builder] for the next login.
     */
    fun close() {
        closed = true
        reset()
        registered.values.forEach { runCatching { it.close() } }
        Timber.d("Rain SDK: Closed (providers torn down)")
    }

    companion object {
        /**
         * Why `Builder.build()` refuses the Rain wallet and the Turnkey provider together; one literal so
         * the test cannot drift. It names the vendor on purpose: the host registered `TurnkeyProvider`
         * by that name, so the name is what makes the message actionable. The wallet module's own
         * docs stay vendor-free, which is a different surface with a different reader.
         */
        internal const val RAIN_AND_TURNKEY_CONFLICT_MESSAGE: String =
            "The Rain wallet provider and the Turnkey provider cannot both be registered; " +
                "they share one process-wide wallet backend"

        /** Starts a new [Builder]. */
        fun builder(): Builder = Builder()
    }

    /**
     * Assembles a [RainSdk]. Module dependencies decide which providers can be registered — the
     * builder never names a vendor SDK itself.
     */
    class Builder internal constructor() {
        private val descriptors = LinkedHashMap<ProviderId, ProviderDescriptor>()
        private var rpcEndpoints: Map<Int, String> = emptyMap()
        private val seedTokens = mutableListOf<TokenInfo>()
        private var authPullConfig: RainAuthPullConfig? = null

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

        private val replaced = mutableListOf<ProviderDescriptor>()

        /**
         * Registers a provider adapter. Re-registering the same id replaces the prior descriptor;
         * [build] closes the replaced instance once the registry is valid, so a replaced descriptor's
         * session watcher does not outlive the registry and a failed build leaves the host's object
         * untouched. Registering the same instance twice is a no-op.
         */
        fun register(descriptor: ProviderDescriptor): Builder = apply {
            val previous = descriptors.put(descriptor.id, descriptor)
            // Registered again after being replaced: it is live, so it must not be closed at build.
            replaced.removeAll { it === descriptor }
            if (previous != null && previous !== descriptor) replaced += previous
        }

        /**
         * Seeds the shared token store with extra token metadata. Validated at [build] exactly as
         * [RainSdk.registerTokens] validates: a malformed address or `decimals` outside 0..77 fails
         * the build.
         */
        fun registerTokens(tokens: List<TokenInfo>): Builder = apply {
            seedTokens += tokens
        }

        /**
         * Enables Auth Pull for the exact operator and token contracts in [config]. Without this
         * call, approval, allowance, confirmation, and approval-fee methods fail closed.
         */
        fun authPullConfig(config: RainAuthPullConfig): Builder = apply {
            authPullConfig = config
        }

        /**
         * Zero registered providers is allowed: the SDK is then wallet-agnostic, exposing the
         * transaction-building helpers ([RainSdk.buildEIP712Message], [RainSdk.buildWithdrawTransactionData]),
         * [RainSdk.tokenMetadata] and [RainSdk.registerTokens]; resolving a [RainSdk.provider] still throws
         * [RainError.ProviderNotRegistered] until one is registered.
         *
         * @throws RainError.InvalidConfig if no RPC endpoints were configured, a seed token or the Auth
         *   Pull configuration is invalid, or both the Rain wallet provider and the Turnkey provider are
         *   registered: they drive one process-wide wallet backend, so an app uses one or the other.
         */
        fun build(): RainSdk {
            if (rpcEndpoints.isEmpty()) {
                throw RainError.InvalidConfig("At least one RPC endpoint is required")
            }
            TokenInfoValidation.requireValid(seedTokens)
            validateAuthPullConfig()
            requireOneWalletBackendProvider()
            // Ownership moves here: descriptors replaced during registration are closed only once the
            // registry is valid, and a throwing host `close()` cannot abort the build.
            replaced.forEach { runCatching { it.close() } }
            replaced.clear()
            return RainSdk(
                rpcEndpoints = rpcEndpoints.toMap(),
                registered = descriptors.toMap(),
                seedTokens = seedTokens.toList(),
                authPullConfig = authPullConfig,
            )
        }

        /**
         * Best-effort: a second RainSdk instance, or a provider that is never registered, can still
         * collide on the backend, and the backend's own configuration check reports that on the
         * first authentication call.
         */
        private fun requireOneWalletBackendProvider() {
            if (ProviderId.RAIN in descriptors && ProviderId.TURNKEY in descriptors) {
                throw RainError.InvalidConfig(RAIN_AND_TURNKEY_CONFLICT_MESSAGE)
            }
        }

        private fun validateAuthPullConfig() {
            val config = authPullConfig ?: return
            if (!config.operatorAddress.isValidEthereumAddress) {
                throw RainError.InvalidConfig("Invalid Auth Pull operator: ${config.operatorAddress}")
            }
            if (config.operatorAddress.isZeroAddress) {
                throw RainError.InvalidConfig("Auth Pull operator must not be the zero address")
            }
            if (config.tokenAddresses.isEmpty()) {
                throw RainError.InvalidConfig("Auth Pull must configure at least one token contract")
            }

            // A configuration may name the chains of the environment its kind stands for; a custom
            // configuration may draw on either environment's set, and nothing outside them.
            val unexpected = config.tokenAddresses.keys - RainAuthPullChains.supported(config.kind)
            if (unexpected.isNotEmpty()) {
                throw RainError.InvalidConfig(
                    "Auth Pull chains ${unexpected.sorted()} are not Auth Pull chains for this configuration's environment"
                )
            }
            config.tokenAddresses.forEach { (chainId, address) ->
                if (!address.isValidEthereumAddress || address.isZeroAddress) {
                    throw RainError.InvalidConfig(
                        "Invalid Auth Pull token contract for chainId=$chainId: $address"
                    )
                }
            }
            if (config.tokenAddresses.keys.none { it in rpcEndpoints }) {
                throw RainError.InvalidConfig(
                    "No RPC endpoint configured for any trusted Auth Pull chain"
                )
            }
        }
    }
}
