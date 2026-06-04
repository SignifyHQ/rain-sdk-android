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

/**
 * Owns per-chain token reference data plus a runtime enrichment cache.
 *
 * Seeded from [TokenRegistry] defaults and extendable at runtime via [register] (host apps
 * adding their own tokens). Unknown contract tokens are enriched on demand by reading
 * `decimals()` / `symbol()` through a [ChainReader], then cached so a given token is only
 * enriched once.
 *
 * A [Mutex] guards the registry and cache maps (the iOS counterpart uses an `actor`). The
 * lock is held only for in-memory map access — never across the enrichment RPC — so
 * concurrent `tokenInfo` calls for different tokens don't serialize behind each other's
 * network round-trips.
 */
internal class TokenMetadataStore(
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
     * Adds host-supplied tokens. A token replaces any existing entry with the same address
     * (case-insensitive) on the same chain.
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

        return mutex.withLock {
            // Another coroutine may have enriched the same token while we were off-lock.
            enrichmentCache[chainId]?.get(key)?.let { return@withLock it }
            enrichmentCache.getOrPut(chainId) { mutableMapOf() }[key] = enriched
            enriched
        }
    }

    // ---------- Enrichment ----------

    /**
     * Reads `decimals()` and `symbol()` in parallel. A failed `decimals()` falls back to the
     * default; a failed `symbol()` leaves the symbol `null`.
     */
    private suspend fun enrich(chainId: Int, address: String): TokenInfo = coroutineScope {
        val decimalsTask = async {
            runCatching { chainReader.getDecimals(chainId, address) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    RainClient.DEFAULT_ERC20_DECIMALS
                }
        }
        val symbolTask = async {
            runCatching { chainReader.getSymbol(chainId, address) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
        }

        TokenInfo(
            chainId = chainId,
            address = address,
            symbol = symbolTask.await(),
            decimals = decimalsTask.await(),
            name = null
        )
    }

    // ---------- Helpers ----------

    /** Must be called while holding [mutex]. */
    private fun upsert(token: TokenInfo) {
        val key = token.address.lowercase()
        val list = knownTokens.getOrPut(token.chainId) { mutableListOf() }
        val index = list.indexOfFirst { it.address.lowercase() == key }
        if (index >= 0) {
            list[index] = token
        } else {
            list.add(token)
        }
    }
}
