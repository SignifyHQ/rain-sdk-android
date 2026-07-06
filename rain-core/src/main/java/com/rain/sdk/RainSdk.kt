package com.rain.sdk

import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.config.RainConfig
import com.rain.sdk.internal.core.ConfigManager
import com.rain.sdk.internal.core.RainSdkManager
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.TokenInfo
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
 * ([com.rain.sdk.provider.RainProvider] descriptors such as `rain-portal`'s `PortalProvider` or
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
) {
    private val mutex = Mutex()
    private val clients = HashMap<ProviderId, RainClient>()

    @Volatile
    private var sharedContext: ProviderContext? = null

    init {
        // Validate endpoints up front (fail fast on a bad URL / chain id) and populate the
        // RainConfig singleton consumed by RainTransactionBuilderImpl, so [transactionBuilder]
        // works without resolving a wallet provider.
        ConfigManager().validateAndSetupRpcEndpoints(rpcEndpoints)
        RainConfig.getInstance().markInitialized()
    }

    /** Ids of every provider the host registered. */
    val providerIds: Set<ProviderId> get() = registered.keys

    /** The capability-advertising descriptors the host registered, for capability resolution. */
    val providers: Collection<RainProvider> get() = registered.values

    /**
     * Wallet-agnostic transaction-building helpers (EIP-712 typed-data + withdraw calldata). These
     * need no wallet provider — only the configured RPC endpoints — so they're available straight
     * off the SDK without resolving a [provider].
     */
    val transactionBuilder: RainTransactionBuilder get() = RainTransactionBuilderImpl

    /**
     * Resolves the [RainClient] backed by the provider registered under [id], materializing the
     * vendor wallet on first access and caching it thereafter.
     *
     * @throws RainError.InvalidConfig if no provider was registered for [id].
     */
    suspend fun provider(id: ProviderId): RainClient = mutex.withLock {
        clients[id]?.let { return it }
        val descriptor = registered[id]
            ?: throw RainError.InvalidConfig("No provider registered for id '${id.value}'")
        val context = buildContext()
        val walletProvider = descriptor.create(context)
        RainSdkManager(
            walletProvider = walletProvider,
            rpcEndpoints = rpcEndpoints,
            tokenStore = context.tokenStore,
        ).also {
            clients[id] = it
            Timber.d("Rain SDK: Resolved provider '${id.value}'")
        }
    }

    /**
     * Resolves the first registered provider matching [predicate] (e.g. by capability) and returns
     * its [RainClient].
     *
     * @throws RainError.InvalidConfig if no registered provider matches.
     */
    suspend fun first(predicate: (RainProvider) -> Boolean): RainClient {
        val match = registered.values.firstOrNull(predicate)
            ?: throw RainError.InvalidConfig("No registered provider matches the requested capability")
        return provider(match.id)
    }

    /**
     * Builds (once) the shared, vendor-free infrastructure handed to every provider. Endpoints are
     * already validated and stored in `init`.
     */
    private fun buildContext(): ProviderContext {
        sharedContext?.let { return it }
        val evm = EvmChainReader(rpcEndpoints = rpcEndpoints)
        val solana = SolanaChainReader(rpcEndpoints = rpcEndpoints)
        val tokenStore = TokenMetadataStore(chainReader = evm, seedTokens = seedTokens)
        return ProviderContext(
            rpcEndpoints = rpcEndpoints,
            tokenStore = tokenStore,
            evmChainReader = evm,
            solanaChainReader = solana,
        ).also { sharedContext = it }
    }

    /**
     * Clears resolved clients and Rain's stored chain configuration. Idempotent. After this the
     * SDK must be rebuilt via [builder] before further use.
     */
    fun reset() {
        clients.values.forEach { runCatching { it.reset() } }
        clients.clear()
        sharedContext = null
        RainConfig.getInstance().clear()
        Timber.d("Rain SDK: Reset")
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

        /** Sets the `chainId → RPC URL` map every provider shares. Required. */
        fun rpcEndpoints(endpoints: Map<Int, String>): Builder = apply {
            rpcEndpoints = endpoints
        }

        /** Registers a provider adapter. Re-registering the same id replaces the prior one. */
        fun register(provider: RainProvider): Builder = apply {
            providers[provider.id] = provider
        }

        /** Seeds the shared token store with extra token metadata. */
        fun registerTokens(tokens: List<TokenInfo>): Builder = apply {
            seedTokens += tokens
        }

        /**
         * @throws RainError.InvalidConfig if no RPC endpoints or no providers were registered.
         */
        fun build(): RainSdk {
            if (rpcEndpoints.isEmpty()) {
                throw RainError.InvalidConfig("At least one RPC endpoint is required")
            }
            if (providers.isEmpty()) {
                throw RainError.InvalidConfig("Register at least one provider")
            }
            return RainSdk(
                rpcEndpoints = rpcEndpoints.toMap(),
                registered = providers.toMap(),
                seedTokens = seedTokens.toList(),
            )
        }
    }
}
