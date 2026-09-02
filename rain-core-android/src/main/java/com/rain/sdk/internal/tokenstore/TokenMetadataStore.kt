package com.rain.sdk.internal.tokenstore

import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.constants.TokenRegistry
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.models.NativeCurrency
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Owns per-chain token reference data plus a runtime enrichment cache.
 *
 * Seeded from [TokenRegistry] defaults and extendable at runtime via [register] (host apps
 * adding their own tokens). Unknown contract tokens are enriched on demand by reading
 * `decimals()` / `symbol()` through a [ChainReader], then cached so a given token is only
 * enriched once.
 *
 * A [Mutex] guards the registry and cache maps. The lock is held only for in-memory map access
 * — never across the enrichment RPC — so concurrent `tokenInfo` calls for different tokens don't
 * serialize behind each other's network round-trips.
 */
class TokenMetadataStore internal constructor(
    private val chainReader: ChainReader,
    seedTokens: List<TokenInfo> = emptyList()
) {
    private val mutex = Mutex()

    /**
     * Known tokens per chain: built-in registry plus host-registered. Insertion order is
     * preserved (registry order first, then registrations) so balance reads are deterministic.
     */
    private val knownTokens: MutableMap<Int, MutableList<TokenInfo>> =
        TokenRegistry.tokensByChainId
            .mapValues { (_, tokens) -> tokens.toMutableList() }
            .toMutableMap()

    /** Tokens discovered and enriched at runtime, keyed by chain ID then lowercased address. */
    private val enrichmentCache: MutableMap<Int, MutableMap<String, TokenInfo>> = mutableMapOf()

    init {
        seedTokens.forEach { upsert(it) }
    }

    /**
     * Adds host-supplied tokens. A token replaces an earlier host registration with the same
     * address (case-insensitive) on the same chain. Built-in registry tokens are never replaced.
     */
    suspend fun register(tokens: List<TokenInfo>) {
        mutex.withLock {
            tokens.forEach { upsert(it) }
        }
    }

    /** Native currency for a chain (gas token metadata). Solana clusters resolve SOL. */
    fun nativeCurrency(chainId: Int): NativeCurrency =
        if (SolanaChains.isSolanaChain(chainId)) SolanaChains.NATIVE_CURRENCY
        else TokenRegistry.nativeCurrency(chainId)

    /**
     * Native currency for a chain, or `null` when the chain is not in the registry. Unlike
     * [nativeCurrency], this never falls back to an ETH-like default, so callers that must not
     * show a wrong symbol (e.g. transaction history) can distinguish "unknown chain".
     */
    fun nativeCurrencyOrNull(chainId: Int): NativeCurrency? =
        if (SolanaChains.isSolanaChain(chainId)) SolanaChains.NATIVE_CURRENCY
        else TokenRegistry.nativeCurrencyByChainId[chainId]

    /** All known tokens for a chain (registry + host-registered), in deterministic order. */
    suspend fun registeredTokens(chainId: Int): List<TokenInfo> =
        mutex.withLock { knownTokens[chainId]?.toList() ?: emptyList() }

    /**
     * Resolves metadata for a contract token: known tokens first, then the enrichment cache,
     * then a one-time on-chain `decimals()` / `symbol()` read (cached on success).
     */
    suspend fun tokenInfo(chainId: Int, address: String): TokenInfo {
        val key = address.lowercase()

        mutex.withLock {
            knownTokens[chainId]?.firstOrNull { it.address.lowercase() == key }?.let { return it }
            enrichmentCache[chainId]?.get(key)?.let { return it }
        }

        // Enrich outside the lock so a slow RPC doesn't block lookups for other tokens.
        val enriched = enrich(chainId, address)

        // A fallback `decimals` is a guess, not a fact: caching it would pin a balance that is
        // wrong by orders of magnitude for the rest of the process. Retry on the next lookup.
        if (!enriched.decimalsResolved) return enriched.info

        return mutex.withLock {
            // Another coroutine may have enriched the same token while we were off-lock.
            enrichmentCache[chainId]?.get(key)?.let { return@withLock it }
            enrichmentCache.getOrPut(chainId) { mutableMapOf() }[key] = enriched.info
            enriched.info
        }
    }

    /**
     * A contract token's decimals, or null when unknown to the registry and the on-chain read
     * failed. Never substitutes the 18-decimal default: money paths must not scale by a guess.
     */
    suspend fun decimalsOrNull(chainId: Int, address: String): Int? {
        val key = address.lowercase()

        mutex.withLock {
            knownTokens[chainId]?.firstOrNull { it.address.lowercase() == key }
                ?.let { return it.decimals }
            enrichmentCache[chainId]?.get(key)?.let { return it.decimals }
        }

        // Enrich outside the lock so a slow RPC doesn't block lookups for other tokens.
        val enriched = enrich(chainId, address)
        if (!enriched.decimalsResolved) return null

        return mutex.withLock {
            enrichmentCache[chainId]?.get(key)?.let { return@withLock it.decimals }
            enrichmentCache.getOrPut(chainId) { mutableMapOf() }[key] = enriched.info
            enriched.info.decimals
        }
    }

    // ---------- Enrichment ----------

    /** An enrichment result plus whether `decimals` came from the chain or the fallback. */
    private data class Enriched(val info: TokenInfo, val decimalsResolved: Boolean)

    /**
     * Reads `decimals()`, `symbol()` and `name()` in parallel. A failed `decimals()` falls
     * back to the default; a failed `symbol()` / `name()` leaves that field `null`.
     */
    private suspend fun enrich(chainId: Int, address: String): Enriched = coroutineScope {
        val decimalsTask = async {
            runCatching { chainReader.getDecimals(chainId, address) }
                .fold(
                    onSuccess = { it to true },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        // Falling back here misreports the balance by orders of magnitude for
                        // any non-18-decimal token, so it must never fail silently.
                        Timber.w(
                            e,
                            "Rain SDK: decimals() read failed for token=$address " +
                                "chainId=$chainId — falling back to " +
                                "${RainClient.DEFAULT_ERC20_DECIMALS}"
                        )
                        RainClient.DEFAULT_ERC20_DECIMALS to false
                    }
                )
        }
        val symbolTask = async {
            runCatching { chainReader.getSymbol(chainId, address) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
        }
        val nameTask = async {
            runCatching { chainReader.getName(chainId, address) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
        }

        val (decimals, decimalsResolved) = decimalsTask.await()
        Enriched(
            info = TokenInfo(
                chainId = chainId,
                address = address,
                symbol = symbolTask.await(),
                decimals = decimals,
                name = nameTask.await()
            ),
            decimalsResolved = decimalsResolved
        )
    }

    // ---------- Helpers ----------

    /** Must be called while holding [mutex]. */
    private fun upsert(token: TokenInfo) {
        val key = token.address.lowercase()
        // The registry is the trusted source for its own tokens: a host-supplied `decimals` for
        // one would rescale every balance and approval against it, so the registration is dropped.
        val trusted = TokenRegistry.tokensFor(token.chainId).firstOrNull { it.address.lowercase() == key }
        if (trusted != null) {
            if (trusted != token) {
                Timber.w(
                    "Rain SDK: Ignoring registration of %s on chain %d: built-in token %s (%d decimals) cannot be overridden",
                    token.address, token.chainId, trusted.symbol ?: "?", trusted.decimals
                )
            }
            return
        }
        val list = knownTokens.getOrPut(token.chainId) { mutableListOf() }
        val index = list.indexOfFirst { it.address.lowercase() == key }
        if (index >= 0) {
            list[index] = token
        } else {
            list.add(token)
        }
    }
}
