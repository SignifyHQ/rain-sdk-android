package com.rain.sdk.internal.tokenstore

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Mirrors the iOS `TokenMetadataStoreTests`: registry-seeded lookups skip enrichment,
 * unknown tokens enrich exactly once and cache, and host-registered tokens are returned
 * without an on-chain read.
 */
class TokenMetadataStoreTest {

    // Chain-1 registry USDC (checksummed in the registry; lookups are case-insensitive).
    private val usdcEthereum = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val unknown = "0xAbCdEf1234567890abcdef1234567890aBcDeF12"

    @Test
    fun `tokenInfo returns registry metadata without enrichment for a known token`() = runBlocking {
        val reader = MockChainReader(decimals = 99, symbol = "WRONG")
        val store = TokenMetadataStore(reader)

        val info = store.tokenInfo(chainId = 1, address = usdcEthereum)

        assertThat(info.symbol).isEqualTo("USDC")
        assertThat(info.decimals).isEqualTo(6)
        // Registry hit — no on-chain enrichment.
        assertThat(reader.decimalsCalls).isEmpty()
        assertThat(reader.symbolCalls).isEmpty()
    }

    @Test
    fun `tokenInfo enriches an unknown token once and caches the result`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val store = TokenMetadataStore(reader)

        val first = store.tokenInfo(chainId = 1, address = unknown)
        // Second lookup with a different-cased address must hit the cache.
        val second = store.tokenInfo(chainId = 1, address = unknown.lowercase())

        assertThat(first.decimals).isEqualTo(8)
        assertThat(first.symbol).isEqualTo("WBTC")
        assertThat(second).isEqualTo(first)
        // Enriched exactly once despite two lookups.
        assertThat(reader.decimalsCalls).hasSize(1)
        assertThat(reader.symbolCalls).hasSize(1)
    }

    @Test
    fun `registered token is returned without enrichment`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val store = TokenMetadataStore(reader)
        store.register(
            listOf(TokenInfo(chainId = 1, address = unknown, symbol = "HOST", decimals = 12, name = "Host Token"))
        )

        val info = store.tokenInfo(chainId = 1, address = unknown)

        assertThat(info.symbol).isEqualTo("HOST")
        assertThat(info.decimals).isEqualTo(12)
        assertThat(reader.decimalsCalls).isEmpty()
        assertThat(reader.symbolCalls).isEmpty()
    }

    @Test
    fun `registeredTokens returns registry tokens plus host registrations in order`() = runBlocking {
        val store = TokenMetadataStore(MockChainReader())
        val host = TokenInfo(chainId = 1, address = unknown, symbol = "HOST", decimals = 12)
        store.register(listOf(host))

        val tokens = store.registeredTokens(chainId = 1)

        assertThat(tokens).contains(host)
        // Registry first, host registrations appended.
        assertThat(tokens.last()).isEqualTo(host)
    }

    @Test
    fun `seedTokens passed to the constructor are registered`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val seed = TokenInfo(chainId = 999, address = unknown, symbol = "SEED", decimals = 4)
        val store = TokenMetadataStore(reader, seedTokens = listOf(seed))

        val info = store.tokenInfo(chainId = 999, address = unknown)

        assertThat(info).isEqualTo(seed)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `nativeCurrency resolves from the registry`() {
        val store = TokenMetadataStore(MockChainReader())
        assertThat(store.nativeCurrency(43114).symbol).isEqualTo("AVAX")
        assertThat(store.nativeCurrency(1).symbol).isEqualTo("ETH")
        // Unknown chain falls back to an ETH-like default.
        assertThat(store.nativeCurrency(123456).symbol).isEqualTo("ETH")
    }
}
